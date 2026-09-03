package com.superduper.notes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.superduper.notes.doc.PenStyle

/**
 * The nib-and-thickness chooser that opens from a pen slot, after the native app's own.
 *
 * Two rows: which nib, and how thick. Both are shown at once rather than nested behind a
 * second tap, because every extra tap on this panel costs a refresh — and with only a
 * handful of options in each row there is nothing to gain by hiding one.
 *
 * Deliberately plain: black on white, no elevation, no animation, no ripple. A
 * [PopupWindow] with an animation style would fade in over several frames, and on a
 * 16-grey panel that reads as a smear rather than a transition.
 */
class PenPopup(
    private val ctx: Context,
    private val nibs: List<PenStyle>,
    private val onPick: (PenStyle, Float) -> Unit,
) {

    /**
     * The device's own pen-width ladder.
     *
     * `PWPenBase.DEFAULT_PENWIDTH_MAP_78 = {3.1, 3.5, 5.1, 7.2, 9.8, 12.3}` — the table
     * `initWidthMapArrayIfNeed:90-95` selects for a 1404-wide panel above 250 dpi, which
     * this one is (`ro.sf.lcd_density` reads 300). These are therefore the weights the
     * firmware itself considers the right steps for this hardware, so they are used rather
     * than a round-numbered ladder of our own.
     *
     * 3.5 is dropped: it and 3.1 quantise to the same rendered width, so offering both
     * would give two steps that look identical.
     */
    private val widths = listOf(3.1f, 5.1f, 7.2f, 9.8f, 12.3f)

    private var window: PopupWindow? = null

    fun show(anchor: View, current: PenStyle, currentWidth: Float) {
        dismiss()

        val density = anchor.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val content = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        // A border, drawn as a hairline child rather than a shape drawable: on e-ink a
        // 1 px black rule is crisper than any rounded, shadowed background.
        fun rule() = View(anchor.context).apply { setBackgroundColor(Color.BLACK) }

        if (nibs.size > 1) {
            content.addView(label("Nib", density))
            val nibRow = LinearLayout(anchor.context).apply { orientation = LinearLayout.HORIZONTAL }
            nibs.forEach { style ->
                nibRow.addView(
                    NibCell(anchor.context, style, style == current) {
                        onPick(style, currentWidth); dismiss()
                    },
                    LinearLayout.LayoutParams(dp(64), dp(52)).apply { marginEnd = dp(4) }
                )
            }
            content.addView(nibRow)
            content.addView(rule(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(8); bottomMargin = dp(8)
            })
        }

        content.addView(label("Thickness", density))
        val widthRow = LinearLayout(anchor.context).apply { orientation = LinearLayout.HORIZONTAL }
        widths.forEach { w ->
            widthRow.addView(
                WidthCell(anchor.context, w, kotlin.math.abs(w - currentWidth) < 0.05f) {
                    onPick(current, w); dismiss()
                },
                LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(4) }
            )
        }
        content.addView(widthRow)

        window = PopupWindow(content, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            // No animation and no elevation, for the reason in the class comment.
            animationStyle = 0
            elevation = 0f
            setBackgroundDrawable(null)
            isOutsideTouchable = true
            showAsDropDown(anchor, 0, dp(2), Gravity.START)
        }
    }

    fun dismiss() {
        window?.dismiss()
        window = null
    }

    val isShowing: Boolean get() = window?.isShowing == true

    private fun label(text: String, density: Float) = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.BLACK)
        textSize = 11f
        setPadding(0, 0, 0, (4 * density).toInt())
    }


    /** One nib choice, showing the nib's own mark rather than a word. */
    private class NibCell(
        context: Context,
        private val style: PenStyle,
        private val active: Boolean,
        private val onClick: () -> Unit,
    ) : View(context) {

        private val paint = Paint().apply { isAntiAlias = true }
        private val text = Paint().apply {
            isAntiAlias = true
            textSize = 9f * context.resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(if (active) Color.BLACK else Color.WHITE)
            val fg = if (active) Color.WHITE else Color.BLACK
            paint.color = fg
            text.color = fg

            // A short sample stroke in the nib's own character: tapered for the nibs that
            // respond to speed, flat for the ones that do not. It says more about how the
            // nib behaves than its name does.
            val cy = height * 0.42f
            val x0 = width * 0.18f
            val x1 = width * 0.82f
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            val steps = 12
            for (i in 0 until steps) {
                val t0 = i / steps.toFloat()
                val t1 = (i + 1) / steps.toFloat()
                paint.strokeWidth = sampleWidth(t0)
                canvas.drawLine(x0 + (x1 - x0) * t0, cy, x0 + (x1 - x0) * t1, cy, paint)
            }
            canvas.drawText(shortName(), width / 2f, height * 0.86f, text)
        }

        /** The stroke sample's width along its length, in device px. */
        private fun sampleWidth(t: Float): Float {
            val d = resources.displayMetrics.density
            return when (style) {
                // Tapers: thick under a slow start, thinner as the sample speeds up.
                PenStyle.PEN -> (3.4f - 1.6f * t) * d
                PenStyle.BRUSH -> (4.0f - 2.2f * t) * d
                // Flat: neither pressure nor speed changes these.
                PenStyle.PENCIL -> 3.0f * d
                PenStyle.MARKER -> 4.6f * d
                PenStyle.FIXED -> 2.2f * d
            }
        }

        private fun shortName() = when (style) {
            PenStyle.PEN -> "Pen"
            PenStyle.PENCIL -> "Pencil"
            PenStyle.BRUSH -> "Brush"
            PenStyle.MARKER -> "Marker"
            PenStyle.FIXED -> "Tech"
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) onClick()
            return true
        }
    }

    /** One thickness choice, shown as a dot of that weight over its number. */
    private class WidthCell(
        context: Context,
        private val width0: Float,
        private val active: Boolean,
        private val onClick: () -> Unit,
    ) : View(context) {

        private val dot = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        private val text = Paint().apply {
            isAntiAlias = true
            textSize = 9f * context.resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(if (active) Color.BLACK else Color.WHITE)
            val fg = if (active) Color.WHITE else Color.BLACK
            dot.color = fg
            text.color = fg
            // The dot is the actual rendered weight, scaled up so the smaller steps are
            // still tellable apart at a glance.
            val r = width0 * resources.displayMetrics.density * 0.42f
            canvas.drawCircle(width / 2f, height * 0.40f, r, dot)
            canvas.drawText("%.1f".format(width0), width / 2f, height * 0.88f, text)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) onClick()
            return true
        }
    }
}
