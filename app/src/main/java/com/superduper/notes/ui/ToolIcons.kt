package com.superduper.notes.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.superduper.notes.doc.PenStyle

/**
 * Toolbar glyphs. One construction rule per glyph; one line weight for all of them.
 *
 * Every icon is authored on a 24-unit grid with a 2-unit stroke, round caps and joins, and
 * a 2-unit safe margin — the same numbers as the design spec they were drawn from — and is
 * scaled to the cell by [u]. Fill appears only where it carries meaning (pen and brush
 * tips: ink). Siblings differ in exactly one element: pen ↔ tech ↔ brush share a barrel,
 * lasso ↔ area eraser share a dashed loop. Anti-aliased, because icons are static and live
 * at rest on the panel, where a quality refresh renders smooth edges properly; ink itself
 * stays 1-bit (SPEC.md §4.4).
 */
object ToolIcons {

    /** Grid unit → pixels for a glyph box of size [s]. */
    private inline fun u(v: Float, s: Float) = v / 24f * s

    private fun fill(p: Paint) = Paint(p).apply { style = Paint.Style.FILL }
    private fun dashed(p: Paint, s: Float) = Paint(p).apply {
        pathEffect = DashPathEffect(floatArrayOf(u(3.2f, s), u(3.0f, s)), 0f)
    }

    // ---- pens: a shared barrel, three tips ----------------------------------------------

    private fun barrel(c: Canvas, s: Float, p: Paint) {
        c.drawRoundRect(RectF(u(9.5f, s), u(3.5f, s), u(14.5f, s), u(15.5f, s)), u(1.3f, s), u(1.3f, s), p)
    }

    /** Pen: 45° barrel with a filled triangular tip — the only fill in the set, meaning ink. */
    fun pen(c: Canvas, s: Float, p: Paint) {
        c.save(); c.rotate(45f, s / 2f, s / 2f)
        barrel(c, s, p)
        c.drawPath(Path().apply {
            moveTo(u(9.5f, s), u(15.5f, s)); lineTo(u(14.5f, s), u(15.5f, s)); lineTo(u(12f, s), u(20.5f, s)); close()
        }, fill(p))
        c.restore()
    }

    /** Tech pen: same barrel, straight tube nib. One element changed — constant width. */
    fun tech(c: Canvas, s: Float, p: Paint) {
        c.save(); c.rotate(45f, s / 2f, s / 2f)
        barrel(c, s, p)
        c.drawLine(u(12f, s), u(15.5f, s), u(12f, s), u(20.8f, s), p)
        c.restore()
    }

    /** Brush: same barrel, soft filled belly. */
    fun brush(c: Canvas, s: Float, p: Paint) {
        c.save(); c.rotate(45f, s / 2f, s / 2f)
        barrel(c, s, p)
        c.drawPath(Path().apply {
            moveTo(u(9.6f, s), u(15.5f, s))
            cubicTo(u(9.6f, s), u(18.6f, s), u(11.3f, s), u(21.4f, s), u(12f, s), u(22.2f, s))
            cubicTo(u(12.7f, s), u(21.4f, s), u(14.4f, s), u(18.6f, s), u(14.4f, s), u(15.5f, s))
            close()
        }, fill(p))
        c.restore()
    }

    /** The nib a pen slot currently holds. Styles not offered in the picker fall back to the pen. */
    fun nib(c: Canvas, s: Float, p: Paint, style: PenStyle) = when (style) {
        PenStyle.BRUSH -> brush(c, s, p)
        PenStyle.FIXED -> tech(c, s, p)
        PenStyle.PEN, PenStyle.PENCIL, PenStyle.MARKER -> pen(c, s, p)
    }

    // ---- erasers and lasso -----------------------------------------------------------------

    /**
     * Spot eraser: a 2:1 rectangular block with its sleeve line, over a stroke with a gap.
     * The gap is the meaning — this one takes part of a line.
     */
    fun eraseStroke(c: Canvas, s: Float, p: Paint) {
        c.save(); c.rotate(-45f, u(12f, s), u(10.5f, s))
        c.drawRoundRect(RectF(u(6.5f, s), u(7.75f, s), u(17.5f, s), u(13.25f, s)), u(0.6f, s), u(0.6f, s), p)
        c.drawLine(u(10.2f, s), u(7.75f, s), u(10.2f, s), u(13.25f, s), p)
        c.restore()
        c.drawLine(u(3f, s), u(20f, s), u(8.5f, s), u(20f, s), p)
        c.drawLine(u(15.5f, s), u(20f, s), u(21f, s), u(20f, s), p)
    }

    /** Area eraser: the dashed loop you draw, and a cross for what happens inside it. */
    fun eraseArea(c: Canvas, s: Float, p: Paint) {
        c.drawCircle(u(12f, s), u(12f, s), u(8.2f, s), dashed(p, s))
        c.drawLine(u(9.6f, s), u(9.6f, s), u(14.4f, s), u(14.4f, s), p)
        c.drawLine(u(14.4f, s), u(9.6f, s), u(9.6f, s), u(14.4f, s), p)
    }

    /** Lasso: the same dashed loop, with a rope tail and no cross. Select, don't remove. */
    fun lasso(c: Canvas, s: Float, p: Paint) {
        c.drawOval(RectF(u(3.5f, s), u(4.2f, s), u(19.5f, s), u(16.2f, s)), dashed(p, s))
        c.drawPath(Path().apply {
            moveTo(u(17.3f, s), u(14.4f, s))
            cubicTo(u(19.9f, s), u(16.1f, s), u(19.4f, s), u(19.2f, s), u(16.6f, s), u(21f, s))
        }, p)
    }

    // ---- history ---------------------------------------------------------------------------

    fun undo(c: Canvas, s: Float, p: Paint) = curvedArrow(c, s, p, flip = false)
    fun redo(c: Canvas, s: Float, p: Paint) = curvedArrow(c, s, p, flip = true)

    /**
     * Undo: a U-turn, the standard form (Apple's arrow.uturn.backward, Material's undo).
     *
     * Two straight legs joined by one semicircle, with the head on the straight leg. That
     * is the structural fix for the problem geometry alone kept losing: a chevron on the end
     * of an arc shares the arc's curvature, so one barb always ends up lying along it. On a
     * straight leg the barbs have nothing to collide with. Redo is this mirrored.
     */
    private fun curvedArrow(c: Canvas, s: Float, p: Paint, flip: Boolean) {
        c.save()
        if (flip) { c.translate(s, 0f); c.scale(-1f, 1f) }
        val r = u(5f, s)
        c.drawPath(Path().apply {
            moveTo(u(18.4f, s), u(18f, s))
            lineTo(u(18.4f, s), u(11f, s))
            // Semicircle over the top from the right leg to the left leg.
            arcTo(RectF(u(13.4f, s) - r, u(11f, s) - r, u(13.4f, s) + r, u(11f, s) + r), 0f, -180f, false)
            lineTo(u(8.4f, s), u(17f, s))
        }, p)
        c.drawLine(u(5.2f, s), u(13.8f, s), u(8.4f, s), u(17f, s), p)
        c.drawLine(u(8.4f, s), u(17f, s), u(11.6f, s), u(13.8f, s), p)
        c.restore()
    }

    // ---- page ------------------------------------------------------------------------------

    /** Clear page: lid, handle, body. No ribs — at 24 units they turn to noise. */
    fun clear(c: Canvas, s: Float, p: Paint) {
        c.drawLine(u(4f, s), u(7f, s), u(20f, s), u(7f, s), p)
        c.drawPath(Path().apply {
            moveTo(u(9.5f, s), u(7f, s)); lineTo(u(9.5f, s), u(4.8f, s)); lineTo(u(14.5f, s), u(4.8f, s)); lineTo(u(14.5f, s), u(7f, s))
        }, p)
        c.drawPath(Path().apply {
            moveTo(u(6f, s), u(7f, s)); lineTo(u(7f, s), u(20f, s)); lineTo(u(17f, s), u(20f, s)); lineTo(u(18f, s), u(7f, s))
        }, p)
    }

    /**
     * Refresh panel: a 270° arc, then a 3.5-unit straight stub along the direction of travel,
     * with a symmetric ±42° chevron on the stub. Same principle as the arrows — the head sits
     * on a straight segment, never on the curve. One arrow: the panel refreshes once.
     */
    fun refresh(c: Canvas, s: Float, p: Paint) {
        val cx = u(12f, s); val cy = u(13f, s); val r = u(6.5f, s)
        c.drawPath(Path().apply {
            arcTo(RectF(cx - r, cy - r, cx + r, cy + r), -70f, 270f, true)
            lineTo(u(7.09f, s), u(7.49f, s))
        }, p)
        c.drawLine(u(3.73f, s), u(9.27f, s), u(7.09f, s), u(7.49f, s), p)
        c.drawLine(u(7.09f, s), u(7.49f, s), u(8.51f, s), u(11.01f, s), p)
    }

    /** Export: a tray open at the top, and an arrow leaving it — out, not up. */
    fun export(c: Canvas, s: Float, p: Paint) {
        c.drawPath(Path().apply {
            moveTo(u(6f, s), u(12.5f, s)); lineTo(u(6f, s), u(19.5f, s)); lineTo(u(18f, s), u(19.5f, s)); lineTo(u(18f, s), u(12.5f, s))
        }, p)
        c.drawLine(u(12f, s), u(15f, s), u(12f, s), u(4.5f, s), p)
        c.drawLine(u(8.5f, s), u(8f, s), u(12f, s), u(4.5f, s), p)
        c.drawLine(u(12f, s), u(4.5f, s), u(15.5f, s), u(8f, s), p)
    }

    /**
     * Settings: one toothed outline plus a hub. Six flat-topped teeth with radial flanks,
     * outer radius 9.4, root radius 7.2, tooth and gap 30° each. The teeth are part of the
     * silhouette — spokes protruding from a ring read as a ship's wheel, which is what the
     * first draft was. Six rather than eight: at a 2-unit stroke on a 24-unit grid, eight
     * teeth merge into a scalloped ring.
     */
    fun gear(c: Canvas, s: Float, p: Paint) {
        val cx = u(12f, s); val cy = u(12f, s)
        val ro = u(9.4f, s); val ri = u(7.2f, s)
        val path = Path()
        var first = true
        for (k in 0 until 6) {
            val a = Math.toRadians(-90.0 + 60.0 * k)
            for ((r, da) in listOf(ro to -15.0, ro to 15.0, ri to 15.0, ri to 45.0)) {
                val t = a + Math.toRadians(da)
                val x = cx + r * Math.cos(t).toFloat(); val y = cy + r * Math.sin(t).toFloat()
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
        }
        path.close()
        c.drawPath(path, p)
        c.drawCircle(cx, cy, u(2.6f, s), p)
    }
}
