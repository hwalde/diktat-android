package de.herbertwalde.diktat

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs

/**
 * Die schwebende Bedienung über anderen Apps:
 *  - BUBBLE: rundes Mikrofon-Symbol, verschiebbar, nur sichtbar wenn ein Textfeld fokussiert ist
 *  - RECORDING: Leiste  [X]  ~~~Waveform~~~  [✓]
 *  - BUSY: Leiste mit Fortschritt („Wird erkannt …“)
 *  - ERROR: Leiste mit Meldung, [X] verwerfen, [↻] erneut versuchen
 */
class OverlayController(
    private val context: Context,
    private val settings: Settings,
    private val listener: Listener,
) {
    interface Listener {
        fun onMicClicked()
        fun onCancel()
        fun onConfirm()
        fun onRetry()
    }

    enum class Mode { HIDDEN, BUBBLE, RECORDING, BUSY, ERROR }

    var mode = Mode.HIDDEN
        private set

    private val wm = context.getSystemService(WindowManager::class.java)
    private val dp = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private val isDark get() =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Primärfarbe des Systems (Material You / Samsung-Farbpalette). */
    private val primary get() = context.getColor(if (isDark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
    private val onPrimary get() = context.getColor(if (isDark) android.R.color.system_accent1_800 else android.R.color.white)

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var pill: View? = null
    private var pillParams: WindowManager.LayoutParams? = null

    var waveform: WaveformView? = null
        private set
    private var pillMessage: TextView? = null
    private var pillProgress: ProgressBar? = null
    private var pillConfirm: ImageButton? = null

    private fun baseParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Nie fokussierbar: Das Textfeld der anderen App behält Fokus und Tastatur.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val screenW get() = context.resources.displayMetrics.widthPixels
    private val screenH get() = context.resources.displayMetrics.heightPixels
    private val bubbleSize get() = px(52)

    private fun defaultBubbleX() = screenW - bubbleSize - px(28)
    private fun defaultBubbleY() = (screenH * 0.42).toInt()

    // ------------------------------------------------------------------ öffentliche Zustände

    fun showBubble() {
        removePill()
        if (bubble == null) addBubble()
        bubble?.visibility = View.VISIBLE
        mode = Mode.BUBBLE
    }

    fun hide() {
        if (mode == Mode.RECORDING || mode == Mode.BUSY || mode == Mode.ERROR) return
        removePill()
        bubble?.visibility = View.GONE
        mode = Mode.HIDDEN
    }

    /** Erzwingt das Ausblenden (z. B. beim Beenden des Dienstes). */
    fun destroy() {
        removePill()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        mode = Mode.HIDDEN
    }

    fun showRecording() {
        ensurePill()
        bubble?.visibility = View.GONE
        waveform?.reset()
        waveform?.visibility = View.VISIBLE
        pillMessage?.visibility = View.GONE
        pillProgress?.visibility = View.GONE
        pillConfirm?.setImageResource(R.drawable.ic_check)
        pillConfirm?.contentDescription = context.getString(R.string.cd_confirm)
        pillConfirm?.isEnabled = true
        pillConfirm?.alpha = 1f
        mode = Mode.RECORDING
    }

    fun showBusy() {
        ensurePill()
        waveform?.visibility = View.GONE
        pillProgress?.visibility = View.VISIBLE
        pillMessage?.visibility = View.VISIBLE
        pillMessage?.text = "Wird erkannt …"
        pillConfirm?.isEnabled = false
        pillConfirm?.alpha = 0.35f
        mode = Mode.BUSY
    }

    fun showError(message: String) {
        ensurePill()
        waveform?.visibility = View.GONE
        pillProgress?.visibility = View.GONE
        pillMessage?.visibility = View.VISIBLE
        pillMessage?.text = message
        pillConfirm?.setImageResource(R.drawable.ic_refresh)
        pillConfirm?.contentDescription = "Erneut versuchen"
        pillConfirm?.isEnabled = true
        pillConfirm?.alpha = 1f
        mode = Mode.ERROR
    }

    // ------------------------------------------------------------------ Bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = bubbleSize
        val view = ImageButton(context).apply {
            setImageResource(R.drawable.ic_mic)
            imageTintList = ColorStateList.valueOf(onPrimary)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(primary) }
            elevation = px(6).toFloat()
            contentDescription = context.getString(R.string.cd_mic)
            scaleType = android.widget.ImageView.ScaleType.CENTER
        }
        val container = FrameLayout(context).apply {
            // Etwas Rand, damit der Schatten nicht abgeschnitten wird.
            setPadding(px(14), px(14), px(14), px(14))
            clipToPadding = false
            addView(view, FrameLayout.LayoutParams(size, size))
        }
        val lp = baseParams(size + px(28), size + px(28)).apply {
            x = settings.bubbleX.takeIf { it >= 0 } ?: defaultBubbleX()
            y = settings.bubbleY.takeIf { it >= 0 } ?: defaultBubbleY()
        }
        clampToScreen(lp)

        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        clampToScreen(lp)
                        runCatching { wm.updateViewLayout(container, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (dragging) {
                        settings.bubbleX = lp.x; settings.bubbleY = lp.y
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        listener.onMicClicked()
                    }
                }
            }
            true
        }
        wm.addView(container, lp)
        bubble = container
        bubbleParams = lp
    }

    private fun clampToScreen(lp: WindowManager.LayoutParams) {
        lp.x = lp.x.coerceIn(0, maxOf(0, screenW - lp.width))
        lp.y = lp.y.coerceIn(px(24), maxOf(px(24), screenH - lp.height - px(24)))
    }

    // ------------------------------------------------------------------ Aufnahme-Leiste

    private fun ensurePill() {
        if (pill != null) return
        val height = px(60)
        val fg = context.getColor(R.color.pill_fg)

        val cancel = ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(fg)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isDark) 0x33FFFFFF else 0x14000000)
            }
            contentDescription = context.getString(R.string.cd_cancel)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                listener.onCancel()
            }
        }
        val confirm = ImageButton(context).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(onPrimary)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(primary) }
            contentDescription = context.getString(R.string.cd_confirm)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                if (mode == Mode.ERROR) listener.onRetry() else listener.onConfirm()
            }
        }
        val wave = WaveformView(context)
        val progress = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(primary)
            visibility = View.GONE
        }
        val message = TextView(context).apply {
            setTextColor(fg)
            textSize = 14f
            maxLines = 2
            visibility = View.GONE
        }
        val middle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(wave, LinearLayout.LayoutParams(0, px(36), 1f))
            addView(progress, LinearLayout.LayoutParams(px(22), px(22)).apply { marginEnd = px(10) })
            addView(message, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(8), px(8))
            background = GradientDrawable().apply {
                cornerRadius = height / 2f
                setColor(context.getColor(R.color.pill_bg))
                setStroke(px(1), if (isDark) 0x22FFFFFF else 0x14000000)
            }
            elevation = px(8).toFloat()
            addView(cancel, LinearLayout.LayoutParams(px(44), px(44)))
            addView(middle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = px(12); marginEnd = px(12)
            })
            addView(confirm, LinearLayout.LayoutParams(px(44), px(44)))
        }
        val container = FrameLayout(context).apply {
            setPadding(px(10), px(10), px(10), px(10))
            clipToPadding = false
            addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height))
        }
        val width = minOf(px(340), screenW - px(24))
        val lp = baseParams(width + px(20), height + px(20)).apply {
            x = (screenW - (width + px(20))) / 2
            // Auf Höhe des Mikrofon-Symbols, damit der Blick dort bleibt.
            y = (bubbleParams?.y ?: defaultBubbleY()) - px(4)
        }
        clampToScreen(lp)
        wm.addView(container, lp)
        pill = container; pillParams = lp
        waveform = wave; pillMessage = message; pillProgress = progress; pillConfirm = confirm
    }

    private fun removePill() {
        pill?.let { runCatching { wm.removeView(it) } }
        pill = null; pillParams = null; waveform = null; pillMessage = null; pillProgress = null; pillConfirm = null
    }
}
