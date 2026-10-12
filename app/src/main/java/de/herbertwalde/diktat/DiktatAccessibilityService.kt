package de.herbertwalde.diktat

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class DiktatAccessibilityService : AccessibilityService(), OverlayController.Listener {

    companion object {
        private const val TAG = "Diktat"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1

        /** Für die Einstellungs-Seite: läuft der Dienst gerade? */
        @Volatile var running = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var overlay: OverlayController
    private var recorder: AudioRecorder? = null
    private var recordingStartedAt = 0L

    /** Das Textfeld, in das der Text am Ende eingefügt wird. */
    private var target: AccessibilityNodeInfo? = null
    private var targetPackage: String? = null
    /** Aufnahme, die gerade transkribiert wird bzw. fehlgeschlagen ist (für Retry). */
    private var currentEntryId: Long? = null

    private val hideRunnable = Runnable { overlay.hide() }

    override fun onServiceConnected() {
        running = true
        overlay = OverlayController(this, app.settings, this)
        app.warmUp()
        refreshFocus()
    }

    override fun onDestroy() {
        running = false
        recorder?.cancel()
        if (::overlay.isInitialized) overlay.destroy()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ------------------------------------------------------------------ Fokus-Erkennung

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        refreshFocus()
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: return null
        // Eigene App nur für das Testfeld auf der Einstellungsseite.
        if (node.packageName == packageName && node.viewIdResourceName != "$packageName:id/testField") return null
        if (!node.isEditable || node.isPassword) return null
        return node
    }

    private fun refreshFocus() {
        if (!::overlay.isInitialized) return
        val node = focusedEditable()
        when (overlay.mode) {
            OverlayController.Mode.RECORDING, OverlayController.Mode.BUSY, OverlayController.Mode.ERROR -> {
                // Während der Aufnahme bleibt das Ziel-Feld fest; ein neues fokussiertes Feld wird zum Ziel.
                if (node != null) { target = node; targetPackage = node.packageName?.toString() }
            }
            else -> {
                if (node != null) {
                    main.removeCallbacks(hideRunnable)
                    target = node
                    targetPackage = node.packageName?.toString()
                    overlay.showBubble()
                } else {
                    // Kurz warten: Beim Wechsel zwischen Feldern verliert das alte Feld den Fokus vor dem neuen.
                    main.removeCallbacks(hideRunnable)
                    main.postDelayed(hideRunnable, 400)
                }
            }
        }
    }

    // ------------------------------------------------------------------ Aufnahme

    override fun onMicClicked() {
        if (!app.models.isReady()) {
            toast("Das Sprachmodell fehlt noch. Bitte die Diktat-App öffnen und das Modell laden.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Diktat hat keine Mikrofon-Berechtigung. Bitte die Diktat-App öffnen.")
            return
        }
        startForegroundForMic()
        overlay.showRecording()
        val wave = overlay.waveform
        recorder = AudioRecorder { level -> wave?.push(level) }.also {
            try {
                it.start()
            } catch (t: Throwable) {
                Log.e(TAG, "Aufnahme-Start fehlgeschlagen", t)
                recorder = null
                stopForegroundForMic()
                overlay.showBubble()
                toast("Mikrofon nicht verfügbar: ${t.message}")
                return
            }
        }
        recordingStartedAt = SystemClock.elapsedRealtime()
        app.warmUp()
    }

    override fun onCancel() {
        recorder?.cancel()
        recorder = null
        stopForegroundForMic()
        currentEntryId = null
        overlay.showBubble()
        refreshFocus()
    }

    override fun onConfirm() {
        val rec = recorder ?: return
        recorder = null
        val pcm = rec.stop()
        val peak = rec.peakRms
        stopForegroundForMic()
        val duration = SystemClock.elapsedRealtime() - recordingStartedAt

        if (pcm.size < AudioRecorder.SAMPLE_RATE / 5 * 2) { // < 0,2 s
            overlay.showBubble(); refreshFocus(); return
        }
        val id = System.currentTimeMillis()
        Wav.write(app.history.audioFile(id), pcm)
        app.history.put(
            HistoryStore.Entry(id, id, duration, "", HistoryStore.Status.PENDING, targetApp = targetPackage),
        )
        currentEntryId = id
        if (peak < 0.0005f) {
            // Android liefert Stille, wenn das Mikrofon für Hintergrund-Apps gesperrt ist.
            Log.w(TAG, "Aufnahme ist stumm (peak=$peak)")
        }
        transcribe(id, retry = false)
    }

    override fun onRetry() {
        val id = currentEntryId ?: return overlay.showBubble()
        transcribe(id, retry = true)
    }

    private fun transcribe(id: Long, retry: Boolean) {
        overlay.showBusy()
        app.worker.execute {
            val entry = app.transcribeEntry(id, retry)
            main.post { onTranscribed(entry) }
        }
    }

    private fun onTranscribed(entry: HistoryStore.Entry) {
        // Inzwischen abgebrochen: Ergebnis bleibt im Verlauf, wird aber nicht eingefügt.
        if (entry.id != currentEntryId) return
        when (entry.status) {
            HistoryStore.Status.OK, HistoryStore.Status.SUSPICIOUS -> {
                currentEntryId = null
                val ok = insertText(entry.text)
                overlay.showBubble()
                if (!ok) {
                    copyToClipboard(entry.text)
                    toast("Einfügen nicht möglich – Text liegt in der Zwischenablage.")
                } else if (entry.status == HistoryStore.Status.SUSPICIOUS) {
                    toast("Text wirkt unvollständig. In der Diktat-App kannst du „Erneut erkennen“ wählen.")
                }
                refreshFocus()
            }
            HistoryStore.Status.EMPTY -> overlay.showError("Nichts verstanden")
            else -> overlay.showError("Erkennung fehlgeschlagen")
        }
    }

    // ------------------------------------------------------------------ Einfügen

    /**
     * Fügt [text] an der Cursor-Position des Ziel-Felds ein (ersetzt eine Markierung).
     * 1. Weg: ACTION_SET_TEXT mit dem zusammengesetzten Feldinhalt – lässt die Zwischenablage unberührt.
     * 2. Weg (z. B. Browser/Rich-Text-Editoren): Zwischenablage + ACTION_PASTE, danach alte Zwischenablage zurück.
     */
    private fun insertText(text: String): Boolean {
        val node = target?.takeIf { it.refresh() } ?: focusedEditable() ?: return false
        val showingHint = node.isShowingHintText
        val current = if (showingHint) "" else node.text?.toString() ?: ""
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) { start = current.length; end = current.length }
        if (start > end) { val t = start; start = end; end = t }

        val before = current.substring(0, start)
        val after = current.substring(end)
        val needsSpaceBefore = before.isNotEmpty() && !before.last().isWhitespace() && !before.endsWith("\n")
        val needsSpaceAfter = after.isNotEmpty() && !after.first().isWhitespace() && after.first() !in ".,!?;:)"
        val insert = (if (needsSpaceBefore) " " else "") + text + (if (needsSpaceAfter) " " else "")
        val expected = before + insert + after

        val setOk = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expected) },
        )
        if (setOk && node.refresh() && node.text?.toString() == expected) {
            val cursor = before.length + insert.length
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
            return true
        }
        Log.i(TAG, "SET_TEXT nicht übernommen (ok=$setOk), versuche Einfügen über die Zwischenablage")
        return pasteViaClipboard(node, insert)
    }

    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(ClipboardManager::class.java)
        val previous = runCatching { cm.primaryClip }.getOrNull()
        cm.setPrimaryClip(ClipData.newPlainText("Diktat", text).apply {
            // Android 13+: keine Vorschau-Einblendung für unseren Zwischenschritt
            description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        })
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (ok && previous != null) main.postDelayed({ runCatching { cm.setPrimaryClip(previous) } }, 800)
        return ok
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Diktat", text))
    }

    // ------------------------------------------------------------------ Mikrofon im Vordergrund

    /**
     * Android erlaubt Mikrofonzugriff nur „im Vordergrund“. Wir melden die Aufnahme deshalb als
     * Vordergrund-Dienst (Typ Mikrofon) an – so zeigt Android auch den grünen Mikrofon-Punkt.
     */
    private fun startForegroundForMic() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Aufnahme läuft", NotificationManager.IMPORTANCE_LOW))
            }
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Diktat hört zu")
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground nicht möglich: ${t.message}")
        }
    }

    private fun stopForegroundForMic() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
