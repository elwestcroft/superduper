package com.superduper.notes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.superduper.notes.doc.PenStyle

/**
 * Icon toolbar for the ink surface (SPEC.md §6.2).
 *
 * Icons rather than labels, following the device's own conventions: seven text buttons
 * overflowed a 1404 px panel and cropped the last one, and text forces every control to be
 * as wide as its longest word. Icons make the row fixed-width and scannable.
 *
 * E-ink drives the visual language. Pure black on white, no ripples, no elevation, no state
 * animations — every one of those costs a panel refresh and renders as mud at 16 grey
 * levels. Selection is shown by inverting the whole cell, which is unambiguous where a
 * tint or underline would not be.
 */
class InkToolbar(context: Context) : LinearLayout(context) {

    /**
     * Tools, named for what they do rather than which engine mode backs them.
     *
     * SPOT erases exactly what you drag over, splitting strokes; AREA removes whole
     * strokes you scribble across or draw a loop around. That split matches the native
     * app, and is the distinction that makes two erasers worth having.
     */
    enum class Tool(val drawObjType: Int) { PEN(0), PEN2(0), ERASE_SPOT(5), ERASE_AREA(2), LASSO(1) }

    /**
     * One pen slot: which nib, and how thick.
     *
     * Two slots, each remembering its own pair, after the native app's design — the point
     * is switching between a fine hand and a heavy one without a trip through a menu. The second defaults to the brush, by the user's choice; the tech pen (constant
     * width) sits beside it in the nib picker. Both are plain strokes the engine and
     * TchRaster agree on.
     */
    class PenSlot(var style: PenStyle, var width: Float)

    val slots = mapOf(
        Tool.PEN to PenSlot(PenStyle.PEN, 6.2f),
        Tool.PEN2 to PenSlot(PenStyle.FIXED, 7.2f),
    )

    /** Raised when a slot's nib or thickness changes, or a slot is selected. */
    var onPenSlot: (PenSlot) -> Unit = {}

    var onTool: (Tool) -> Unit = {}
    var onUndo: () -> Unit = {}
    var onRedo: () -> Unit = {}
    var onClear: () -> Unit = {}
    var onRefresh: () -> Unit = {}
    var onExport: () -> Unit = {}
    /** Passed the gear cell itself, so the caller can anchor a popup on it. */
    var onSettings: (View) -> Unit = {}

    private val cells = mutableMapOf<Tool, IconCell>()
    private lateinit var positionLabel: TextView
    private lateinit var gearCell: View

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(8), dp(4), dp(8), dp(4))

        Tool.entries.forEach { tool ->
            val slot = slots[tool]
            val cell = IconCell(context, iconFor(tool), label = { slot?.let { "%.1f".format(it.width) } }) {
                // Tapping the already-active pen slot opens its chooser; tapping an
                // inactive one just switches to it. That way one tap is always "use this
                // pen" and never an accidental menu.
                val wasActive = cells[tool]?.active == true
                select(tool)
                onTool(tool)
                if (slot != null) {
                    onPenSlot(slot)
                    if (wasActive) openPopup(tool, slot)
                }
            }
            cells[tool] = cell
            addView(cell, cellParams())
        }

        addView(divider(), LayoutParams(dp(1), LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(6); marginEnd = dp(6); topMargin = dp(8); bottomMargin = dp(8)
        })

        addView(IconCell(context, ToolIcons::undo) { onUndo() }, cellParams())
        addView(IconCell(context, ToolIcons::redo) { onRedo() }, cellParams())
        addView(IconCell(context, ToolIcons::clear) { onClear() }, cellParams())
        addView(IconCell(context, ToolIcons::refresh) { onRefresh() }, cellParams())

        // Third group: what you do to the page. Export and settings are designed with the
        // set so the family is coherent; their behaviour is not built yet, and until it is
        // a tap says so in the log rather than pretending.
        addView(divider(), LayoutParams(dp(1), LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(6); marginEnd = dp(6); topMargin = dp(8); bottomMargin = dp(8)
        })
        addView(IconCell(context, ToolIcons::export) { onExport() }, cellParams())
        val gearCell = IconCell(context, ToolIcons::gear) { onSettings(this.gearCell) }
        this.gearCell = gearCell
        addView(gearCell, cellParams())

        // Position readout takes the remaining width, so the icon row can never be
        // squeezed off the edge the way the text buttons were.
        positionLabel = TextView(context).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
            setPadding(dp(8), 0, dp(4), 0)
        }
        addView(positionLabel, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        select(Tool.PEN)
    }

    fun select(tool: Tool) {
        cells.forEach { (t, cell) -> cell.active = t == tool }
    }

    fun setPageLabel(text: String) {
        positionLabel.text = text.replace('\n', ' ')
    }

    private fun iconFor(tool: Tool): (Canvas, Float, Paint) -> Unit = when (tool) {
        Tool.PEN -> ToolIcons::pen
        // The second slot shows the nib it currently holds, so the bar tells you what it
        // will draw with before you tap it.
        Tool.PEN2 -> { c, sz, p -> ToolIcons.nib(c, sz, p, slots[Tool.PEN2]?.style ?: PenStyle.FIXED) }
        Tool.ERASE_SPOT -> ToolIcons::eraseStroke
        Tool.ERASE_AREA -> ToolIcons::eraseArea
        Tool.LASSO -> ToolIcons::lasso
    }

    private var popup: PenPopup? = null

    private fun openPopup(tool: Tool, slot: PenSlot) {
        popup?.dismiss()
        // The first slot is the pen proper; the second is where the other nibs live, which
        // is why only it offers a nib row.
        // Only the nibs the engine draws as a plain stroke are offered, because only those
        // can be re-rendered to match it. MARKER and PENCIL both use textured renderers:
        // PWRandomDrawMark stamps a ~25% stipple bitmap, and PWRandomDrawPencil scatters
        // pi*r*r*density/2 random dots per point (drawPencilPoints0). Painting either as a
        // solid line put wet and dry ink permanently out of step — measured at 1-4 px off,
        // and visible as ink changing weight the moment anything triggered a redraw.
        // They return when TchRaster can reproduce those textures.
        val nibs = if (tool == Tool.PEN) listOf(PenStyle.PEN)
        else listOf(PenStyle.FIXED, PenStyle.BRUSH)
        popup = PenPopup(context, nibs) { style, width ->
            slot.style = style
            slot.width = width
            cells[tool]?.invalidate()
            onPenSlot(slot)
        }.also { it.show(cells[tool]!!, slot.style, slot.width) }
    }

    fun dismissPopup() { popup?.dismiss(); popup = null }

    private fun divider() = View(context).apply { setBackgroundColor(Color.BLACK) }

    private fun cellParams() = LayoutParams(dp(56), LayoutParams.MATCH_PARENT).apply {
        marginEnd = dp(2)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * A single icon button.
     *
     * Custom rather than a styled Button so the icon can be drawn at exact stroke weight
     * and the pressed state can invert instantly, with no ripple animation for the panel
     * to chase.
     */
    private class IconCell(
        context: Context,
        private val icon: (Canvas, Float, Paint) -> Unit,
        private val label: () -> String? = { null },
        private val onClick: () -> Unit,
    ) : View(context) {

        // Named `active`, not `selected`: a `selected` property would collide with
        // View.setSelected(boolean) at the JVM signature level.
        var active: Boolean = false
            set(value) { field = value; invalidate() }

        private var pressedDown = false

        private val stroke = Paint().apply {
            // Anti-aliasing is worth it here: icons are static, so they are drawn once and
            // then live at rest, where a quality refresh renders smooth edges properly.
            // Ink is the opposite case and stays 1-bit.
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 2.6f * context.resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // 11 dp semibold: the weight number is the one piece of state the bar shows without
        // a tap, and at 9 dp regular it was the least legible thing on the panel.
        private val labelPaint = Paint().apply {
            isAntiAlias = true
            textSize = 11f * context.resources.displayMetrics.density
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            val on = active || pressedDown
            canvas.drawColor(if (on) Color.BLACK else Color.WHITE)
            stroke.color = if (on) Color.WHITE else Color.BLACK

            val text = label()
            // The thickness number sits under the nib, as it does on the native bar — it
            // is the one piece of state you want visible without opening anything.
            val size = minOf(width, height) * (if (text != null) 0.50f else 0.62f)
            canvas.save()
            canvas.translate((width - size) / 2f, (height - size) / 2f - if (text != null) size * 0.18f else 0f)
            icon(canvas, size, stroke)
            canvas.restore()
            if (text != null) {
                labelPaint.color = if (on) Color.WHITE else Color.BLACK
                canvas.drawText(text, width / 2f, height * 0.88f, labelPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressedDown = true; invalidate() }
                MotionEvent.ACTION_UP -> {
                    pressedDown = false; invalidate()
                    if (event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) onClick()
                }
                MotionEvent.ACTION_CANCEL -> { pressedDown = false; invalidate() }
            }
            return true
        }
    }

    companion object {
        const val HEIGHT_DP = 60
    }
}
