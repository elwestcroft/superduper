package com.superduper.notes.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.superduper.notes.doc.TchFile
import com.superduper.notes.doc.TchRaster

/**
 * The app-owned document surface: every stroke that is no longer being written.
 *
 * This is the half of the architecture we previously handed to the firmware, and handing
 * it over was the mistake. `PWCoreCtrl` is a one-screen writing surface; asking it to hold
 * a scrolling document meant swapping its contents on every step, which is what produced
 * the jank, the stale windows and the vertical-line artifacts.
 *
 * Here instead the app keeps a viewport-sized bitmap of the document and the engine draws
 * only the live stroke on top of it. Scrolling is then a **blit-shift plus a band
 * re-render**: the pixels that stay on screen are memcpy'd to their new position and only
 * the newly exposed strip is rasterised. That is the technique both working e-ink
 * infinite-canvas apps use (Notable's `PageView.updateScroll`, Notate's tile manager), and
 * it is why they can afford to scroll continuously — cost scales with the band, not the
 * screen.
 */
class DryLayer(private val width: Int, private val height: Int) {

    private var bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(Color.WHITE) }

    /** Scratch used to shift pixels; swapped with [bitmap] rather than reallocated. */
    private var spare: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private val srcRect = Rect()
    private val dstRect = Rect()

    fun draw(canvas: Canvas) = canvas.drawBitmap(bitmap, 0f, 0f, null)

    /**
     * Shift the surface by [dy] document pixels and return the region newly exposed, in
     * view coordinates — the only part that needs rasterising.
     *
     * Positive [dy] means scrolling **down** the document, so content moves **up**.
     */
    fun shift(dy: Int): Rect {
        if (dy == 0 || kotlin.math.abs(dy) >= height) {
            // Nothing survives the move; the caller must re-render everything.
            bitmap.eraseColor(Color.WHITE)
            return Rect(0, 0, width, height)
        }
        val c = Canvas(spare)
        c.drawColor(Color.WHITE)
        if (dy > 0) {
            // Content moves up: keep rows [dy, height) and place them at [0, height-dy).
            srcRect.set(0, dy, width, height)
            dstRect.set(0, 0, width, height - dy)
            c.drawBitmap(bitmap, srcRect, dstRect, null)
        } else {
            val d = -dy
            srcRect.set(0, 0, width, height - d)
            dstRect.set(0, d, width, height)
            c.drawBitmap(bitmap, srcRect, dstRect, null)
        }
        // Swap rather than copy back — the old surface becomes next call's scratch.
        val old = bitmap
        bitmap = spare
        spare = old

        return if (dy > 0) Rect(0, height - dy, width, height) else Rect(0, 0, width, -dy)
    }

    /**
     * Rasterise [strokes] (already translated into view coordinates) into [band] only.
     * Clipping to the band is what keeps a scroll step proportional to how far you moved.
     *
     * [scrollY] is the world Y that view Y=0 currently maps to — needed only to phase-lock
     * [background] to the document rather than the screen; the white fill and the ink
     * itself don't care about it.
     */
    fun renderBand(
        band: Rect,
        strokes: List<TchFile.Stroke>,
        penWidth: Float,
        scrollY: Int = 0,
        background: BackgroundStyle = BackgroundStyle.NONE,
        backgroundSpacing: BackgroundSpacing = BackgroundSpacing.MEDIUM,
        backgroundWeight: BackgroundWeight = BackgroundWeight.MEDIUM,
    ) {
        if (band.isEmpty) return
        val c = Canvas(bitmap)
        c.save()
        c.clipRect(band)
        c.drawColor(Color.WHITE)
        PageBackground.draw(c, band, scrollY, width, background, backgroundSpacing, backgroundWeight)
        TchRaster.drawInto(c, strokes, penWidth)
        c.restore()
    }

    /** Draw a single finished stroke, in view coordinates, without touching anything else. */
    fun commitStroke(stroke: TchFile.Stroke, penWidth: Float) {
        TchRaster.drawInto(Canvas(bitmap), listOf(stroke), penWidth)
    }

    /**
     * Composite the engine's own pixels for a finished stroke.
     *
     * Preferred over [commitStroke]: it is the same rendering the user just watched appear,
     * so nothing changes weight or shape when the pen lifts. Our rasteriser stays as the
     * fallback and for re-drawing strokes scrolled back into view.
     */
    fun commitEnginePixels(source: Bitmap, rect: Rect) {
        val clipped = Rect(rect)
        if (!clipped.intersect(0, 0, minOf(width, source.width), minOf(height, source.height))) return
        // Engine ink is opaque black on white; DST_IN-style masking is unnecessary because
        // the source region already contains this stroke over the same white ground.
        Canvas(bitmap).drawBitmap(source, clipped, clipped, null)
    }

    fun clear() = bitmap.eraseColor(Color.WHITE)

    fun release() {
        bitmap.recycle(); spare.recycle()
    }
}
