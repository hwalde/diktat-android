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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

class DiktatAccessibilityService : AccessibilityService(), OverlayController.Listener {

    companion object {
        private const val TAG = "Diktat"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1

        /** Apps mit Rich-Text-Editor: dort nie ACTION_SET_TEXT (würde Formatierung/Checklisten/Bilder verlieren). */
        private val RICH_TEXT_APPS = setOf("com.samsung.android.app.notes", "com.google.android.keep")

        /** Für die Einstellungs-Seite: läuft der Dienst gerade? */
        @Volatile var running = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var overlay: OverlayController
    private var recorder: AudioRecorder? = null
    private var recordingStartedAt = 0L

    /**
     * Das Textfeld, in das der Text am Ende eingefügt wird. [targetPasteOnly] = true bei Apps mit eigenem Editor
     * (z. B. Samsung Notes), deren Feld sich nicht als bearbeitbar meldet: Dort wird nur eingefügt (ACTION_PASTE),
     * nie der Feldinhalt ersetzt – sonst könnte eine Notiz überschrieben werden.
     */
    private var target: AccessibilityNodeInfo? = null
    private var targetPasteOnly = false
    private var targetPackage: String? = null
    /** Aufnahme, die gerade transkribiert wird bzw. fehlgeschlagen ist (für Retry). */
    private var currentEntryId: Long? = null

    private val hideRunnable = Runnable { overlay.hide() }
    /** Fehler-Leiste schließen, wenn der Nutzer das Feld verlassen hat (die Aufnahme bleibt im Verlauf). */
    private val dismissErrorRunnable = Runnable {
        if (overlay.mode == OverlayController.Mode.ERROR && findTarget() == null) {
            currentEntryId = null
            overlay.reset()
        }
    }

    override fun onServiceConnected() {
        running = true
        overlay = OverlayController(this, app.settings, this)
        app.warmUp()
        refreshFocus()
        app.interruptedEntryId?.let {
            app.interruptedEntryId = null
            toast("Diktat wurde vom System neu gestartet. Die laufende Aufnahme ist im Verlauf gespeichert.")
        }
    }

    override fun onDestroy() {
        running = false
        recorder?.let { rec ->
            recorder = null
            saveInterruptedRecording(rec)
        }
        if (::overlay.isInitialized) overlay.destroy()
        super.onDestroy()
    }

    /**
     * Android kann die Bedienungshilfe jederzeit beenden und neu verbinden (z. B. wenn ein anderer Dienst die
     * Bedienungshilfen kurz übernimmt). Die laufende Aufnahme geht dann nicht verloren: Sie wird gespeichert und
     * im Hintergrund erkannt. Eingefügt wird nichts, der Text steht im Verlauf.
     */
    private fun saveInterruptedRecording(rec: AudioRecorder) {
        val pcm = rec.stop()
        stopForegroundForMic()
        if (pcm.size < AudioRecorder.SAMPLE_RATE / 5 * 2) return
        val id = System.currentTimeMillis()
        Wav.write(app.history.audioFile(id), pcm)
        app.history.put(
            HistoryStore.Entry(
                id, id, SystemClock.elapsedRealtime() - recordingStartedAt, "", HistoryStore.Status.PENDING,
                targetApp = targetPackage,
            ),
        )
        app.interruptedEntryId = id
        app.worker.execute { app.transcribeEntry(id) }
    }

    override fun onInterrupt() {}

    // ------------------------------------------------------------------ Fokus-Erkennung

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        refreshFocus()
    }

    private class Target(val node: AccessibilityNodeInfo, val pasteOnly: Boolean)

    private fun isOwnNonTestField(node: AccessibilityNodeInfo) =
        node.packageName == packageName && node.viewIdResourceName != "$packageName:id/testField"

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: return null
        // Eigene App nur für das Testfeld auf der Einstellungsseite.
        if (isOwnNonTestField(node)) return null
        if (!node.isEditable || node.isPassword || editorIsPassword()) return null
        return node
    }

    /** Passwortfeld laut Eingabetyp der Tastatur (greift auch bei Apps mit eigenem Editor, z. B. Banking). */
    private fun editorIsPassword(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val type = runCatching { inputMethod?.currentInputEditorInfo?.inputType }.getOrNull() ?: return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun keyboardVisible(): Boolean = runCatching {
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    /**
     * Ermittelt das Ziel-Feld. Normalfall: das fokussierte, bearbeitbare Feld. Sonderfall: Die Tastatur ist offen,
     * aber die App meldet ihr Feld nicht als bearbeitbar (eigene Editoren wie Samsung Notes) – dann gilt das
     * fokussierte Element als Ziel im reinen Einfüge-Modus.
     */
    private fun findTarget(): Target? {
        focusedEditable()?.let { return Target(it, pasteOnly = false) }
        if (!keyboardVisible()) return null
        val focused = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: return null
        if (isOwnNonTestField(focused) || focused.isPassword || editorIsPassword()) return null
        return Target(focused, pasteOnly = true)
    }

    private fun refreshFocus() {
        if (!::overlay.isInitialized) return
        val found = findTarget()
        when (overlay.mode) {
            OverlayController.Mode.RECORDING, OverlayController.Mode.BUSY -> {
                // Während Aufnahme/Erkennung bleibt das Ziel fest; ein neu fokussiertes Feld wird zum Ziel.
                if (found != null) setTarget(found)
            }
            OverlayController.Mode.ERROR -> {
                main.removeCallbacks(dismissErrorRunnable)
                if (found != null) setTarget(found) else main.postDelayed(dismissErrorRunnable, 600)
            }
            else -> {
                if (found != null) {
                    main.removeCallbacks(hideRunnable)
                    setTarget(found)
                    overlay.showBubble()
                } else {
                    // Kurz warten: Beim Wechsel zwischen Feldern verliert das alte Feld den Fokus vor dem neuen.
                    main.removeCallbacks(hideRunnable)
                    main.postDelayed(hideRunnable, 400)
                }
            }
        }
    }

    private fun setTarget(t: Target) {
        target = t.node
        targetPasteOnly = t.pasteOnly
        targetPackage = t.node.packageName?.toString()
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
        overlay.reset()
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
            toast("Aufnahme zu kurz")
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
                // Eingefügt wird nur, wenn beim Bestätigen (noch oder wieder) ein Textfeld den Fokus hat.
                // Sonst bleibt der Text im Verlauf und liegt zusätzlich in der Zwischenablage.
                val now = findTarget()
                if (now == null) {
                    copyToClipboard(entry.text)
                    overlay.reset()
                    toast("Kein Textfeld ausgewählt – Text ist im Verlauf gespeichert und in der Zwischenablage.")
                    refreshFocus()
                    return
                }
                setTarget(now)
                insertText(entry.text) { ok ->
                    overlay.showBubble()
                    if (!ok) {
                        copyToClipboard(entry.text)
                        toast("Einfügen nicht möglich – Text liegt in der Zwischenablage.")
                    } else if (entry.status == HistoryStore.Status.SUSPICIOUS) {
                        toast("Text wirkt unvollständig. In der Diktat-App unter Verlauf „Erneut erkennen“ wählen – das Ergebnis liegt dann in der Zwischenablage.")
                    }
                    refreshFocus()
                }
            }
            HistoryStore.Status.EMPTY -> overlay.showError("Nichts verstanden")
            else -> overlay.showError("Erkennung fehlgeschlagen")
        }
    }

    // ------------------------------------------------------------------ Einfügen

    /**
     * Fügt [text] an der Cursor-Position des Ziel-Felds ein (ersetzt eine Markierung) und meldet das Ergebnis an [done].
     * 1. Weg: ACTION_SET_TEXT mit dem zusammengesetzten Feldinhalt – lässt die Zwischenablage unberührt.
     * 2. Weg (Feld bleibt unverändert, z. B. manche Rich-Text-Editoren): Zwischenablage + ACTION_PASTE.
     * Manche Apps (z. B. Chrome) übernehmen SET_TEXT verzögert. Deshalb wird bis zu 600 ms nachgeprüft, bevor
     * der 2. Weg greift – sonst landet der Text doppelt im Feld.
     * Bis zu 1,2 s wird nachgeprüft; erst dann gilt das Feld als unverändert.
     */
    private fun insertText(text: String, done: (Boolean) -> Unit) {
        val node = target?.takeIf { it.refresh() } ?: return done(false)
        // 1. Weg überall: wie eine Tastatur über die Eingabeverbindung an der Cursor-Position schreiben.
        //    Ersetzt nie Feldinhalt – sicher auch für Rich Text (Formatierung, Checklisten, Bilder in Notizen).
        if (commitViaInputMethod(text)) return done(true)
        val supportsSetText = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        if (targetPasteOnly || !node.isEditable || !supportsSetText || node.packageName in RICH_TEXT_APPS) {
            // Eigener Editor: nur einfügen, nie den Inhalt setzen – und nur in das Feld mit dem Cursor.
            return done(pasteViaClipboard(node, "$text "))
        }
        fun fieldText(n: AccessibilityNodeInfo) = if (n.isShowingHintText) "" else n.text?.toString() ?: ""
        val current = fieldText(node)
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) { start = current.length; end = current.length }
        if (start > end) { val t = start; start = end; end = t }

        val before = current.substring(0, start)
        val after = current.substring(end)
        val needsSpaceBefore = before.isNotEmpty() && !before.last().isWhitespace()
        val needsSpaceAfter = after.isNotEmpty() && !after.first().isWhitespace() && after.first() !in ".,!?;:)"
        val insert = (if (needsSpaceBefore) " " else "") + text + (if (needsSpaceAfter) " " else "")
        val expected = before + insert + after
        val cursor = before.length + insert.length

        val setOk = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expected) },
        )
        if (!setOk) {
            Log.i(TAG, "SET_TEXT abgelehnt, versuche Eingabeverbindung bzw. Zwischenablage")
            return done(commitViaInputMethod(text) || pasteViaClipboard(node, insert))
        }
        val checks = longArrayOf(0, 100, 250, 600, 1200)
        fun check(i: Int) {
            val now = if (node.refresh()) fieldText(node) else null
            when {
                now != null && now != current -> {
                    if (now == expected) {
                        node.performAction(
                            AccessibilityNodeInfo.ACTION_SET_SELECTION,
                            Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                            },
                        )
                    }
                    done(true)
                }
                i + 1 < checks.size -> main.postDelayed({ check(i + 1) }, checks[i + 1] - checks[i])
                else -> {
                    Log.i(TAG, "SET_TEXT ohne Wirkung, versuche Eingabeverbindung bzw. Zwischenablage")
                    done(commitViaInputMethod(text) || pasteViaClipboard(node, insert))
                }
            }
        }
        check(0)
    }

    /**
     * Schreibt [text] über die Eingabeverbindung des fokussierten Felds (Android 13+, flagInputMethodEditor) –
     * genau wie eine Tastatur. Funktioniert auch bei Editoren, die SET_TEXT und PASTE nicht unterstützen.
     */
    private fun commitViaInputMethod(text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val ic = runCatching { inputMethod?.currentInputConnection }.getOrNull() ?: return false
        return runCatching {
            // commitText meldet keinen Erfolg zurück. Deshalb vorher prüfen, ob die Verbindung lebt:
            // Eine ungültige Verbindung (Tastatur zu, Feld gewechselt) liefert keinen Umgebungstext.
            val surrounding = ic.getSurroundingText(1, 1, 0)
                ?: return false.also { Log.i(TAG, "Eingabeverbindung ohne Antwort – nicht verwendet") }
            val sel = surrounding.selectionStart.coerceIn(0, surrounding.text.length)
            val beforeCursor = surrounding.text.subSequence(0, sel).toString()
            val prefix = if (beforeCursor.isNotEmpty() && !beforeCursor.last().isWhitespace()) " " else ""
            ic.commitText(prefix + text, 1, null)
            Log.i(TAG, "Eingefügt über die Eingabeverbindung")
            true
        }.getOrElse { Log.w(TAG, "Eingabeverbindung fehlgeschlagen: ${it.message}"); false }
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
