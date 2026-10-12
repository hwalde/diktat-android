package de.herbertwalde.diktat

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlin.math.sqrt

/** Parakeet v3 über sherpa-onnx (dieselbe ONNX-Modellfamilie, die Handy auf dem Desktop nutzt). */
class SpeechEngine(private val models: ModelManager) {

    data class Result(val text: String, val suspicious: Boolean)

    private var recognizer: OfflineRecognizer? = null
    private val lock = Any()

    val isLoaded get() = recognizer != null

    /** Lädt das Modell (einige Sekunden). Idempotent und threadsicher. */
    fun load() = synchronized(lock) {
        if (recognizer != null) return
        check(models.isReady()) { "Sprachmodell ist noch nicht heruntergeladen." }
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = models.file("encoder.int8.onnx"),
                    decoder = models.file("decoder.int8.onnx"),
                    joiner = models.file("joiner.int8.onnx"),
                ),
                tokens = models.file("tokens.txt"),
                modelType = "nemo_transducer",
                numThreads = 4,
            ),
        )
        recognizer = OfflineRecognizer(config = config)
    }

    /**
     * Transkribiert die Samples. Lange Aufnahmen werden an leisen Stellen in Stücke von höchstens [maxChunkSec]
     * zerlegt, weil Parakeet bei langen Eingaben dazu neigt, Passagen stillschweigend auszulassen.
     * Der Retry nutzt kürzere Stücke, also eine andere Zerlegung.
     */
    fun transcribe(samples: FloatArray, maxChunkSec: Int = 25): Result = synchronized(lock) {
        load()
        val rec = recognizer!!
        val rate = AudioRecorder.SAMPLE_RATE
        val parts = splitAtSilence(samples, rate, maxChunkSec)
        val text = parts.joinToString(" ") { part ->
            val stream = rec.createStream()
            try {
                // Etwas Stille am Ende verhindert, dass das letzte Wort abgeschnitten wird.
                stream.acceptWaveform(part + FloatArray(rate / 2), rate)
                rec.decode(stream)
                rec.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }.trim()
        Result(text, isSuspicious(text, samples, rate))
    }

    /** Heuristik gegen stillschweigend verschluckte Passagen: viel Sprache, aber auffällig wenige Wörter. */
    private fun isSuspicious(text: String, samples: FloatArray, rate: Int): Boolean {
        val speechSec = voicedSeconds(samples, rate)
        if (speechSec < 4) return false
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        return words / speechSec < 0.9 // normales Sprechen liegt bei ~2–3 Wörtern/s
    }

    private fun frameRms(samples: FloatArray, from: Int, to: Int): Float {
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i]
        return sqrt(sum / maxOf(1, to - from)).toFloat()
    }

    private fun voicedSeconds(samples: FloatArray, rate: Int): Double {
        val frame = rate / 50
        var voiced = 0
        var i = 0
        while (i + frame <= samples.size) {
            if (frameRms(samples, i, i + frame) > 0.02f) voiced++
            i += frame
        }
        return voiced * frame.toDouble() / rate
    }

    private fun splitAtSilence(samples: FloatArray, rate: Int, maxChunkSec: Int): List<FloatArray> {
        val max = maxChunkSec * rate
        if (samples.size <= max) return listOf(samples)
        val parts = mutableListOf<FloatArray>()
        val window = rate / 5 // 200 ms
        var start = 0
        while (samples.size - start > max) {
            // Die leiseste Stelle im letzten Drittel des erlaubten Stücks suchen.
            var bestPos = start + max
            var bestRms = Float.MAX_VALUE
            var pos = start + max * 2 / 3
            while (pos + window <= start + max) {
                val r = frameRms(samples, pos, pos + window)
                if (r < bestRms) { bestRms = r; bestPos = pos + window / 2 }
                pos += window / 2
            }
            parts += samples.copyOfRange(start, bestPos)
            start = bestPos
        }
        parts += samples.copyOfRange(start, samples.size)
        return parts
    }
}
