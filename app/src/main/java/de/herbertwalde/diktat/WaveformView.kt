package de.herbertwalde.diktat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Laufende Pegel-Balken (neueste rechts), wie bei Wispr Flow. */
class WaveformView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val barWidth = 3f * density
    private val gap = 2.5f * density
    private val levels = FloatArray(80)
    private var head = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.wave)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = barWidth
    }

    fun reset() {
        levels.fill(0f); head = 0; invalidate()
    }

    /** RMS-Pegel 0..1, wird aus dem Aufnahme-Thread aufgerufen. */
    fun push(rms: Float) {
        // Wurzel-Skalierung, damit auch leises Sprechen sichtbar ausschlägt.
        val v = min(1f, sqrt(rms * 12f))
        post {
            levels[head] = v
            head = (head + 1) % levels.size
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val step = barWidth + gap
        val count = min(levels.size, (width / step).toInt())
        val mid = height / 2f
        val maxHalf = height / 2f - barWidth
        for (i in 0 until count) {
            // i = 0 ist der neueste Wert ganz rechts
            val idx = (head - 1 - i + levels.size * 2) % levels.size
            val half = max(barWidth / 2f, levels[idx] * maxHalf)
            val x = width - barWidth / 2f - i * step
            canvas.drawLine(x, mid - half, x, mid + half, paint)
        }
    }
}
