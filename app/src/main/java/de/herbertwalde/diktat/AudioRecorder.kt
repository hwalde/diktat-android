package de.herbertwalde.diktat

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Nimmt 16 kHz Mono auf (Format, das Parakeet erwartet) und meldet laufend den Pegel für die Waveform. */
class AudioRecorder(private val onLevel: (Float) -> Unit) {

    companion object {
        const val SAMPLE_RATE = 16000
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()

    /** Größter gemessener RMS-Pegel. Bleibt er ~0, hat Android das Mikrofon stummgeschaltet. */
    @Volatile var peakRms = 0f
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE / 5 * 2),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Mikrofon konnte nicht geöffnet werden" }
        pcm.reset()
        peakRms = 0f
        running = true
        record.startRecording()
        worker = thread(name = "diktat-recorder") {
            val buf = ShortArray(SAMPLE_RATE / 20) // 50 ms
            val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            try {
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    bytes.clear()
                    var sum = 0.0
                    for (i in 0 until n) {
                        bytes.putShort(buf[i])
                        val v = buf[i] / 32768.0
                        sum += v * v
                    }
                    synchronized(pcm) { pcm.write(bytes.array(), 0, n * 2) }
                    val rms = sqrt(sum / n).toFloat()
                    if (rms > peakRms) peakRms = rms
                    onLevel(rms)
                }
            } finally {
                record.stop()
                record.release()
            }
        }
    }

    /** Stoppt die Aufnahme und liefert die Samples als 16-bit-PCM (little endian). */
    fun stop(): ByteArray {
        running = false
        worker?.join(1000)
        worker = null
        return synchronized(pcm) { pcm.toByteArray() }
    }

    fun cancel() {
        stop()
        pcm.reset()
    }
}

object Wav {
    fun write(file: File, pcm16: ByteArray, sampleRate: Int = AudioRecorder.SAMPLE_RATE) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm16.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm16.size)
        }
        file.outputStream().use { it.write(header.array()); it.write(pcm16) }
    }

    /** Liest eine 16-bit-Mono-WAV-Datei als Float-Samples in [-1, 1] (sucht den data-Block, Zusatzblöcke werden übersprungen). */
    fun readFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            if (id == "data") return pcmToFloats(bytes.copyOfRange(pos + 8, minOf(bytes.size, pos + 8 + len)))
            pos += 8 + len + (len and 1)
        }
        error("Keine Audiodaten in ${file.name}")
    }

    fun pcmToFloats(pcm16: ByteArray): FloatArray {
        val sb = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(sb.remaining()) { sb.get(it) / 32768f }
    }
}
