package de.herbertwalde.diktat

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * Parakeet TDT 0.6B v3 (multilingual, int8) im sherpa-onnx-Format. Die vier Dateien werden einzeln von
 * Hugging Face geladen (DownloadManager: läuft im Hintergrund weiter, setzt Abbrüche fort) und per SHA-256 geprüft.
 */
class ModelManager(private val context: Context) {

    data class ModelFile(val name: String, val size: Long, val sha256: String?)

    companion object {
        private const val BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/"
        val FILES = listOf(
            ModelFile("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
            ModelFile("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
            ModelFile("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
            ModelFile("tokens.txt", 93_939, null),
        )
        val TOTAL_BYTES = FILES.sumOf { it.size }
        private const val PREFS = "model"
    }

    sealed class State {
        data object Missing : State()
        data class Downloading(val bytes: Long, val total: Long) : State()
        data object Verifying : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }

    /** Endgültiger Ablageort (intern, nur für die App lesbar). */
    val modelDir: File = File(context.filesDir, "parakeet-v3").apply { mkdirs() }

    /** Download-Zwischenablage (DownloadManager darf nur in externe App-Ordner schreiben). */
    private val downloadDir: File = File(context.getExternalFilesDir(null), "download").apply { mkdirs() }

    private val dm = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isReady(): Boolean = FILES.all { File(modelDir, it.name).length() == it.size }

    fun file(name: String) = File(modelDir, name).absolutePath

    fun startDownload() {
        FILES.forEach { f ->
            if (File(modelDir, f.name).length() == f.size) return@forEach
            val running = prefs.getLong(f.name, -1L)
            if (running >= 0 && statusOf(running) in listOf(
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED,
                    DownloadManager.STATUS_SUCCESSFUL,
                )
            ) return@forEach
            File(downloadDir, f.name).delete()
            val req = DownloadManager.Request(Uri.parse(BASE + f.name + "?download=true"))
                .setTitle("Diktat: Sprachmodell (${f.name})")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, null, "download/" + f.name)
                .setAllowedOverMetered(true)
            prefs.edit().putLong(f.name, dm.enqueue(req)).apply()
        }
    }

    private fun statusOf(id: Long): Int {
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return -1
            return c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }

    private fun bytesOf(id: Long): Long {
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return 0
            return c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        }
    }

    /**
     * Fragt den Fortschritt ab und übernimmt fertige Dateien (Prüfsumme, dann Verschieben ins interne Verzeichnis).
     * Wird von der UI periodisch aufgerufen; blockiert während der Prüfung (Hintergrund-Thread verwenden).
     */
    fun poll(): State {
        if (isReady()) return State.Ready
        var done = 0L
        var anyActive = false
        for (f in FILES) {
            val target = File(modelDir, f.name)
            if (target.length() == f.size) { done += f.size; continue }
            val id = prefs.getLong(f.name, -1L)
            if (id < 0) continue
            when (statusOf(id)) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val tmp = File(downloadDir, f.name)
                    if (tmp.length() != f.size || (f.sha256 != null && sha256(tmp) != f.sha256)) {
                        tmp.delete(); prefs.edit().remove(f.name).apply()
                        return State.Failed("${f.name} ist beschädigt angekommen. Bitte erneut laden.")
                    }
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                    dm.remove(id)
                    prefs.edit().remove(f.name).apply()
                    done += f.size
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove(f.name).apply()
                    return State.Failed("Download von ${f.name} fehlgeschlagen. Internetverbindung prüfen und erneut versuchen.")
                }
                -1 -> prefs.edit().remove(f.name).apply()
                else -> { anyActive = true; done += bytesOf(id) }
            }
        }
        if (isReady()) return State.Ready
        return if (anyActive) State.Downloading(done, TOTAL_BYTES) else State.Missing
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
