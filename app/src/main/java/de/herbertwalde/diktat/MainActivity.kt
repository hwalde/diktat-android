package de.herbertwalde.diktat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.herbertwalde.diktat.databinding.ActivityMainBinding
import de.herbertwalde.diktat.databinding.ItemHistoryBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val main = Handler(Looper.getMainLooper())
    private val adapter = HistoryAdapter()
    private val historyListener: () -> Unit = { main.post { reloadHistory() } }
    private var polling = false
    private val retrying = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.stepMicButton.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        b.stepModelButton.setOnClickListener {
            app.models.startDownload()
            startPolling()
        }
        b.stepServiceButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        b.dictionary.setText(app.settings.customWords.joinToString("\n"))
        b.saveDictionary.setOnClickListener {
            app.settings.customWords = b.dictionary.text.toString().lines()
            Toast.makeText(this, "Wörterbuch gespeichert (${app.settings.customWords.size} Begriffe)", Toast.LENGTH_SHORT).show()
        }

        b.history.layoutManager = LinearLayoutManager(this)
        b.history.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        app.history.addListener(historyListener)
        reloadHistory()
        updateSetup()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        app.history.removeListener(historyListener)
        polling = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateSetup()
    }

    // ------------------------------------------------------------------ Einrichtung

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun serviceEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java)
        return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun updateSetup(model: ModelManager.State? = null) {
        val mic = micGranted()
        b.stepMicText.text = if (mic) "✓  Mikrofon erlaubt" else "1.  Mikrofon erlauben"
        b.stepMicButton.visibility = if (mic) View.GONE else View.VISIBLE

        val state = model ?: if (app.models.isReady()) ModelManager.State.Ready else null
        when (state) {
            ModelManager.State.Ready -> {
                b.stepModelText.text = "✓  Sprachmodell Parakeet v3 bereit"
                b.stepModelButton.visibility = View.GONE
                b.modelProgress.visibility = View.GONE
            }
            is ModelManager.State.Downloading -> {
                val mb = state.bytes / 1_000_000; val total = state.total / 1_000_000
                b.stepModelText.text = "2.  Sprachmodell wird geladen … $mb von $total MB"
                b.stepModelButton.visibility = View.GONE
                b.modelProgress.visibility = View.VISIBLE
                b.modelProgress.setProgressCompat((state.bytes * 1000 / state.total).toInt(), true)
            }
            ModelManager.State.Verifying -> {
                b.stepModelText.text = "2.  Sprachmodell wird geprüft …"
                b.stepModelButton.visibility = View.GONE
            }
            is ModelManager.State.Failed -> {
                b.stepModelText.text = "2.  ${state.message}"
                b.stepModelButton.visibility = View.VISIBLE
                b.stepModelButton.text = "Erneut laden"
                b.modelProgress.visibility = View.GONE
            }
            else -> {
                b.stepModelText.text = "2.  Sprachmodell laden (einmalig, am besten im WLAN)"
                b.stepModelButton.visibility = View.VISIBLE
                b.modelProgress.visibility = View.GONE
            }
        }

        val svc = serviceEnabled()
        b.stepServiceText.text = if (svc) "✓  Bedienungshilfe „Diktat“ aktiv" else "3.  Bedienungshilfe „Diktat“ einschalten"
        b.stepServiceHint.visibility = if (svc) View.GONE else View.VISIBLE
        b.stepServiceButton.visibility = if (svc) View.GONE else View.VISIBLE
    }

    /** Fragt den Download-Fortschritt jede Sekunde ab, solange die Seite sichtbar ist. */
    private fun startPolling() {
        if (polling || app.models.isReady()) return
        polling = true
        thread(name = "diktat-poll") {
            while (polling) {
                val st = app.models.poll()
                main.post { updateSetup(st) }
                if (st is ModelManager.State.Ready) {
                    app.warmUp()
                    break
                }
                if (st is ModelManager.State.Failed || st is ModelManager.State.Missing) break
                Thread.sleep(1000)
            }
            polling = false
        }
    }

    // ------------------------------------------------------------------ Verlauf

    private fun reloadHistory() {
        val entries = app.history.all()
        adapter.submit(entries)
        b.historyEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun retry(id: Long) {
        retrying += id
        adapter.notifyDataSetChanged()
        app.worker.execute {
            val e = app.transcribeEntry(id, retry = true)
            main.post {
                retrying -= id
                reloadHistory()
                if (e.status == HistoryStore.Status.OK || e.status == HistoryStore.Status.SUSPICIOUS) {
                    copy(e.text)
                }
            }
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Diktat", text))
        Toast.makeText(this, "Kopiert", Toast.LENGTH_SHORT).show()
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {
        private var items: List<HistoryStore.Entry> = emptyList()

        fun submit(list: List<HistoryStore.Entry>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val v: ItemHistoryBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            val busy = e.id in retrying || e.status == HistoryStore.Status.PENDING
            val time = DateUtils.getRelativeTimeSpanString(e.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            val secs = (e.durationMs / 1000).coerceAtLeast(1)
            val status = when {
                busy -> " · wird erkannt …"
                e.status == HistoryStore.Status.FAILED -> " · fehlgeschlagen"
                e.status == HistoryStore.Status.EMPTY -> " · nichts verstanden"
                e.status == HistoryStore.Status.SUSPICIOUS -> " · evtl. unvollständig"
                else -> ""
            }
            h.v.meta.text = "$time · $secs s$status"
            h.v.text.text = when {
                e.text.isNotBlank() -> e.text
                e.status == HistoryStore.Status.FAILED -> "Fehler: ${e.error ?: "unbekannt"}"
                else -> "—"
            }
            h.v.copy.isEnabled = e.text.isNotBlank()
            h.v.copy.setOnClickListener { copy(e.text) }
            h.v.retry.isEnabled = !busy && app.history.audioFile(e.id).exists()
            h.v.retry.setOnClickListener { retry(e.id) }
            h.v.delete.isEnabled = !busy
            h.v.delete.setOnClickListener { app.history.delete(e.id) }
        }
    }
}
