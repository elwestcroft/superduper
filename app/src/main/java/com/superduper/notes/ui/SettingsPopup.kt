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
import com.superduper.notes.canvas.BackgroundSpacing
import com.superduper.notes.canvas.BackgroundStyle

/**
 * The page-background chooser that opens from the gear icon. Same construction as
 * [PenPopup] — every row shown at once, plain black-on-white, no elevation or animation —
 * for the reasons given there: a handful of options each, and an e-ink panel reads any
 * fade-in as a smear.
 */
class SettingsPopup(
    private val ctx: Context,
    private val onPick: (BackgroundStyle, BackgroundSpacing) -> Unit,
) {
    private var window: PopupWindow? = null

    fun show(anchor: View, current: BackgroundStyle, currentSpacing: BackgroundSpacing) {
        dismiss()

        val density = anchor.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val content = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        fun rule() = View(anchor.context).apply { setBackgroundColor(Color.BLACK) }
        fun spacer() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = dp(8); bottomMargin = dp(8)
        }

        content.addView(label("Page", density))
        val styleRow = LinearLayout(anchor.context).apply { orientation = LinearLayout.HORIZONTAL }
        BackgroundStyle.entries.forEach { style ->
            styleRow.addView(
                StyleCell(anchor.context, style, style == current) {
                    onPick(style, currentSpacing); dismiss()
                },
                LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(4) }
            )
        }
        content.addView(styleRow)
        content.addView(rule(), spacer())

        content.addView(label("Spacing", density))
        val spacingRow = LinearLayout(anchor.context).apply { orientation = LinearLayout.HORIZONTAL }
        BackgroundSpacing.entries.forEach { sp ->
            spacingRow.addView(
                SpacingCell(anchor.context, current, sp, sp == currentSpacing) {
                    onPick(current, sp); dismiss()
                },
                LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(4) }
            )
        }
        content.addView(spacingRow)
        window = PopupWindow(content, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            animationStyle = 0
            elevation = 0f
            setBackgroundDrawable(null)
            isOutsideTouchable = true
            showAsDropDown(anchor, 0, dp(2), Gravity.END)
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

    /** One page style, previewed as a small sample of the pattern rather than named alone. */
    private class StyleCell(
        context: Context,
        private val style: BackgroundStyle,
        private val active: Boolean,
        private val onClick: () -> Unit,
    ) : View(context) {

        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.6f * context.resources.displayMetrics.density }
        private val text = Paint().apply {
            isAntiAlias = true
            textSize = 8f * context.resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(if (active) Color.BLACK else Color.WHITE)
            val fg = if (active) Color.WHITE else Color.BLACK
            mark.color = fg
            text.color = fg
            val d = resources.displayMetrics.density
            val cy = height * 0.38f
            val pitch = 9f * d
            when (style) {
                BackgroundStyle.NONE -> {} // blank sample is the point
                BackgroundStyle.LINES -> {
                    mark.style = Paint.Style.STROKE
                    canvas.drawLine(width * 0.2f, cy, width * 0.8f, cy, mark)
                }
                BackgroundStyle.DOTS -> {
                    mark.style = Paint.Style.FILL
                    var x = width * 0.28f
                    while (x <= width * 0.72f + 1f) { canvas.drawCircle(x, cy, 1.6f * d, mark); x += pitch }
                }
                BackgroundStyle.CROSS -> {
                    mark.style = Paint.Style.STROKE
                    val half = 3f * d
                    var x = width * 0.28f
                    while (x <= width * 0.72f + 1f) {
                        canvas.drawLine(x - half, cy, x + half, cy, mark)
                        canvas.drawLine(x, cy - half, x, cy + half, mark)
                        x += pitch
                    }
                }
            }
            canvas.drawText(shortName(), width / 2f, height * 0.86f, text)
        }

        private fun shortName() = when (style) {
            BackgroundStyle.NONE -> "Blank"
            BackgroundStyle.DOTS -> "Dots"
            BackgroundStyle.LINES -> "Lines"
            BackgroundStyle.CROSS -> "Cross"
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) onClick()
            return true
        }
    }

    /**
     * One spacing choice, previewed at [style]'s own pattern but drawn with an actual
     * pitch proportional to [spacing] — narrow/medium/wide read as visibly different gaps,
     * not just a label.
     */
    private class SpacingCell(
        context: Context,
        private val style: BackgroundStyle,
        private val spacing: BackgroundSpacing,
        private val active: Boolean,
        private val onClick: () -> Unit,
    ) : View(context) {

        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.4f * context.resources.displayMetrics.density }
        private val text = Paint().apply {
            isAntiAlias = true
            textSize = 8f * context.resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(if (active) Color.BLACK else Color.WHITE)
            val fg = if (active) Color.WHITE else Color.BLACK
            mark.color = fg
            text.color = fg
            val d = resources.displayMetrics.density
            val cy = height * 0.38f
            // Scaled down from the real pitch so all three still fit the cell, but kept
            // proportional so the relative gap is honest.
            val pitch = spacing.pitchPx * d * 0.16f
            val style0 = if (style == BackgroundStyle.NONE) BackgroundStyle.DOTS else style
            val usable = width * 0.68f
            val cols = (usable / pitch).toInt().coerceAtLeast(1)
            val span = cols * pitch
            val startX = (width - span) / 2f
            when (style0) {
                BackgroundStyle.LINES -> {
                    mark.style = Paint.Style.STROKE
                    var y = cy - span / 2f
                    var i = 0
                    while (i <= cols) { canvas.drawLine(width * 0.2f, y, width * 0.8f, y, mark); y += pitch; i++ }
                }
                BackgroundStyle.DOTS -> {
                    mark.style = Paint.Style.FILL
                    for (i in 0..cols) canvas.drawCircle(startX + i * pitch, cy, 1.5f * d, mark)
                }
                else -> {
                    mark.style = Paint.Style.STROKE
                    val half = pitch * 0.22f
                    for (i in 0..cols) {
                        val x = startX + i * pitch
                        canvas.drawLine(x - half, cy, x + half, cy, mark)
                        canvas.drawLine(x, cy - half, x, cy + half, mark)
                    }
                }
            }
            canvas.drawText(spacing.label, width / 2f, height * 0.86f, text)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) onClick()
            return true
        }
    }

}
