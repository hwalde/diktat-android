package de.herbertwalde.diktat

import android.app.Application
import android.content.Context
import java.util.concurrent.Executors

class DiktatApp : Application() {

    lateinit var models: ModelManager
    lateinit var engine: SpeechEngine
    lateinit var history: HistoryStore
    lateinit var settings: Settings

    /** Ein einziger Thread für Modell-Laden und Transkription: das Modell braucht ~1 GB RAM und ist nicht parallel nutzbar. */
    val worker = Executors.newSingleThreadExecutor { Thread(it, "diktat-asr") }

    /** Aufnahme, die beim Beenden der Bedienungshilfe gesichert wurde. Beim Neustart gibt es dazu einen Hinweis. */
    @Volatile var interruptedEntryId: Long? = null

    override fun onCreate() {
        super.onCreate()
        models = ModelManager(this)
        engine = SpeechEngine(models)
        history = HistoryStore(this)
        settings = Settings(this)
        warmUp()
    }

    /** Modell im Hintergrund laden, damit der erste Diktat-Klick nicht warten muss. */
    fun warmUp() {
        if (models.isReady() && !engine.isLoaded) worker.execute { runCatching { engine.load() } }
    }

    /**
     * Transkribiert eine gespeicherte Aufnahme und aktualisiert den Verlauf. Läuft auf dem aufrufenden Thread.
     * [retry] = true zerlegt die Aufnahme kleiner, um verschluckte Passagen zurückzuholen.
     */
    fun transcribeEntry(id: Long, retry: Boolean = false): HistoryStore.Entry {
        val base = history.get(id) ?: error("Aufnahme $id nicht gefunden")
        val updated = try {
            val samples = Wav.readFloats(history.audioFile(id))
            val res = engine.transcribe(samples, maxChunkSec = if (retry) 12 else 25)
            val text = TextPostProcessor.process(res.text, settings.customWords)
            base.copy(
                text = text,
                error = null,
                status = when {
                    text.isBlank() -> HistoryStore.Status.EMPTY
                    res.suspicious -> HistoryStore.Status.SUSPICIOUS
                    else -> HistoryStore.Status.OK
                },
            )
        } catch (t: Throwable) {
            base.copy(status = HistoryStore.Status.FAILED, error = t.message ?: t.javaClass.simpleName)
        }
        history.put(updated)
        return updated
    }
}

val Context.app get() = applicationContext as DiktatApp

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var customWords: List<String>
        get() = prefs.getString("custom_words", "")!!.lines().map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("custom_words", v.joinToString("\n")).apply()

    /** Position des schwebenden Mikrofons (in Pixeln, -1 = Standard). */
    var bubbleX: Int
        get() = prefs.getInt("bubble_x", -1)
        set(v) = prefs.edit().putInt("bubble_x", v).apply()
    var bubbleY: Int
        get() = prefs.getInt("bubble_y", -1)
        set(v) = prefs.edit().putInt("bubble_y", v).apply()
}
