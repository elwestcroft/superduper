package com.superduper.notes.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/** A page background pattern, drawn under the ink. */
enum class BackgroundStyle { NONE, DOTS, LINES, CROSS }

/**
 * Gap between rows (and, for DOTS/CROSS, between columns). Independent of [BackgroundWeight]
 * — spacing this tight is fine for a math-paper dot grid but is too cramped to write a line
 * of normal handwriting between, which is why this is its own control rather than baked
 * into a "thickness" preset.
 */
enum class BackgroundSpacing(val label: String, val pitchPx: Int) {
    NARROW("Narrow", 36),
    MEDIUM("Medium", 56),
    WIDE("Wide", 84),
}

/** How heavy a mark reads — dot radius, line stroke, or cross-arm stroke. */
enum class BackgroundWeight(val label: String, val markPx: Float) {
    FINE("Fine", 1.2f),
    MEDIUM("Medium", 1.7f),
    BOLD("Bold", 2.3f),
}

/**
 * Draws [style] into [clip] of [canvas], phase-locked to world Y ([scrollY] + view Y) so
 * the pattern stays put under the ink as the document scrolls, the way it would on paper.
 *
 * X needs no such lock — the document never scrolls horizontally (SPEC.md — a fixed-width
 * authored canvas) — but it does need to be measured from [viewWidth], the constant full
 * page width, rather than from [clip] itself: [clip] is often a partial band (an edit's
 * dirty rect, not the whole page), and computing the centering margin from its width would
 * give each repainted band its own phase, so the grid would step sideways at every partial
 * repaint's boundary. The caller has already clipped the canvas to [clip], so this only
 * needs the position of every mark on the full-width page — the ones outside the clip are
 * cheap to compute and simply don't paint anything.
 */
object PageBackground {

    private val linePaint = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; style = Paint.Style.FILL }
    private val crossPaint = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE }

    fun draw(
        canvas: Canvas,
        clip: Rect,
        scrollY: Int,
        viewWidth: Int,
        style: BackgroundStyle,
        spacing: BackgroundSpacing,
        weight: BackgroundWeight,
    ) {
        if (style == BackgroundStyle.NONE) return
        val pitch = spacing.pitchPx

        val worldTop = scrollY + clip.top
        var worldY = (worldTop / pitch) * pitch
        if (worldY < worldTop) worldY += pitch
        val worldBottom = scrollY + clip.bottom

        // Centre the columns on the page: as many full pitch-steps as fit, with the
        // leftover space split evenly as a margin on each side, rather than starting the
        // first column at x=0 and letting the last column run off wherever it lands.
        val cols = viewWidth / pitch
        val margin = (viewWidth - cols * pitch) / 2f

        when (style) {
            BackgroundStyle.LINES -> {
                linePaint.strokeWidth = weight.markPx
                while (worldY <= worldBottom) {
                    val y = (worldY - scrollY).toFloat()
                    canvas.drawLine(clip.left.toFloat(), y, clip.right.toFloat(), y, linePaint)
                    worldY += pitch
                }
            }
            BackgroundStyle.DOTS -> {
                dotPaint.strokeWidth = weight.markPx
                val r = weight.markPx * 0.9f
                while (worldY <= worldBottom) {
                    val y = (worldY - scrollY).toFloat()
                    for (i in 0..cols) canvas.drawCircle(margin + i * pitch, y, r, dotPaint)
                    worldY += pitch
                }
            }
            BackgroundStyle.CROSS -> {
                // A small + at every intersection, not a full grid — the mark that shows
                // through is a crosshair, not a line, so it stays out of the way of ink
                // passing near it the way a solid grid line does not.
                crossPaint.strokeWidth = weight.markPx
                val half = pitch * 0.16f
                while (worldY <= worldBottom) {
                    val y = (worldY - scrollY).toFloat()
                    for (i in 0..cols) {
                        val x = margin + i * pitch
                        canvas.drawLine(x - half, y, x + half, y, crossPaint)
                        canvas.drawLine(x, y - half, x, y + half, crossPaint)
                    }
                    worldY += pitch
                }
            }
            BackgroundStyle.NONE -> {}
        }
    }
}
