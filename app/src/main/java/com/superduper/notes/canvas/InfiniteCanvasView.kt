package com.superduper.notes.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Milestone-0 seed of the infinite canvas (SPEC.md §4.8, §12) — the `SoftwareWetInk`
 * baseline of SPEC.md §4.6.
 *
 * It is deliberately NOT the tiled engine yet. It is the minimal correct stylus input
 * pipeline (SPEC.md §4.2-4.4) plus latency probes, so the first sideload can measure
 * what the Nomad actually gives a third-party app:
 *   - stylus-only inking (TOOL_TYPE_STYLUS / TOOL_TYPE_ERASER gate = palm rejection rule 1)
 *   - unbuffered dispatch during strokes
 *   - historical-batch draining (no polygonal strokes)
 *   - 1-bit black-on-white wet ink
 *   - genuine dirty-rect surface updates, and input->post latency logging
 *
 * Why SurfaceView and not a plain View: View.invalidate(l,t,r,b) is deprecated precisely
 * because a hardware-accelerated View ignores the supplied rectangle and recomputes its
 * own damage area, so dirty-rect drawing -- the whole e-ink strategy in SPEC.md §4.4 --
 * cannot be expressed through it. SurfaceHolder.lockCanvas(Rect) does honour the rect, so
 * ink lands in a backing bitmap and only the damaged region is blitted and posted.
 *
 * Phase 3 replaces the single screen-sized backing bitmap with the Viewport/TilePipeline/
 * DocModel architecture in SPEC.md §5-§7.
 */
class InfiniteCanvasView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private companion object {
        const val TAG = "SuperDuper"

        /**
         * How many past damage rectangles to re-blit. The surface is a swap chain, so a
         * buffer handed back by lockCanvas() holds content from N frames ago; unioning the
         * last N damage rects keeps every buffer converged. 3 covers triple buffering.
         */
        const val SWAP_CHAIN_DEPTH = 3
    }

    // Wet ink: strict 1-bit, no anti-aliasing while the stroke is live (SPEC.md §4.4).
    private val wetPaint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Everything committed so far. Phase 3: replaced by DocModel + tile cache. */
    private var backing: Bitmap? = null
    private var backingCanvas: Canvas? = null

    private var activePath: Path? = null
    private var lastX = 0f
    private var lastY = 0f

    // Pre-allocated: MotionEvent handling must not allocate (SPEC.md §4.2 rule 5).
    private val segmentBounds = RectF()
    private val damage = Rect()
    private val blitRect = Rect()
    private val recentDamage = ArrayDeque<Rect>()

    // Latency probe state (Milestone 0).
    private var strokeStartUptimeMs = 0L
    private var samplesInStroke = 0
    private var maxSampleToPostMs = 0L

    init {
        holder.addCallback(this)
    }

    // --- surface lifecycle ---

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // ARGB_8888 for the seed's full-screen buffer; the tile pipeline uses ALPHA_8
        // masks instead (SPEC.md §5.4).
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        backing = bmp
        backingCanvas = Canvas(bmp)
        recentDamage.clear()
        blitAll()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        backingCanvas = null
        backing?.recycle()
        backing = null
    }

    // --- input (SPEC.md §4.2) ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Palm rejection rule 1: fingers never ink (SPEC.md §4.3). Finger pan/zoom
        // gestures arrive with Phase 3's GestureRouter.
        val tool = event.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) {
            return false
        }
        val canvas = backingCanvas ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Deliver stylus samples as they arrive rather than batched to vsync.
                requestUnbufferedDispatch(event)
                strokeStartUptimeMs = SystemClock.uptimeMillis()
                samplesInStroke = 0
                maxSampleToPostMs = 0
                lastX = event.x
                lastY = event.y
                activePath = Path().apply { moveTo(lastX, lastY) }
            }

            MotionEvent.ACTION_MOVE -> {
                val path = activePath ?: return true
                segmentBounds.setEmpty()
                // Drain the historical batch oldest-first, then the current sample,
                // or the stroke goes polygonal (SPEC.md §4.2 rule 3).
                for (h in 0 until event.historySize) {
                    extendStroke(path, event.getHistoricalX(0, h), event.getHistoricalY(0, h))
                }
                extendStroke(path, event.x, event.y)
                samplesInStroke += event.historySize + 1

                // Redraw only the damaged span of the live path into the backing bitmap.
                val r = wetPaint.strokeWidth + 2f
                damage.set(
                    (segmentBounds.left - r).toInt(), (segmentBounds.top - r).toInt(),
                    (segmentBounds.right + r).toInt(), (segmentBounds.bottom + r).toInt()
                )
                canvas.save()
                canvas.clipRect(damage)
                canvas.drawPath(path, wetPaint)
                canvas.restore()
                blit(damage)

                // Probe: sample timestamp -> buffer posted (SPEC.md §4.8). This excludes
                // the EPD waveform time, which is the dominant term and is only
                // measurable with an external camera.
                val sampleToPost = SystemClock.uptimeMillis() - event.eventTime
                if (sampleToPost > maxSampleToPostMs) maxSampleToPostMs = sampleToPost
            }

            MotionEvent.ACTION_UP -> {
                activePath = null
                val ms = SystemClock.uptimeMillis() - strokeStartUptimeMs
                val rate = if (ms > 0) samplesInStroke * 1000L / ms else 0
                Log.i(
                    TAG,
                    "stroke: $samplesInStroke samples in ${ms}ms ($rate/s), " +
                        "worst sample->post ${maxSampleToPostMs}ms"
                )
            }

            MotionEvent.ACTION_CANCEL -> {
                // System gesture takeover: abort the in-flight stroke and repaint from
                // the backing bitmap, discarding the partial path (SPEC.md §4.2 rule 6).
                activePath = null
                blitAll()
            }
        }
        return true
    }

    private fun extendStroke(path: Path, x: Float, y: Float) {
        path.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
        segmentBounds.union(minOf(lastX, x), minOf(lastY, y))
        segmentBounds.union(maxOf(lastX, x), maxOf(lastY, y))
        lastX = x
        lastY = y
    }

    // --- output ---

    /** Blit [region] plus the last few damage rects, so every swap-chain buffer converges. */
    private fun blit(region: Rect) {
        val bmp = backing ?: return
        blitRect.set(region)
        for (past in recentDamage) blitRect.union(past)
        if (!blitRect.intersect(0, 0, bmp.width, bmp.height)) return

        val surface = holder.lockCanvas(blitRect) ?: return
        try {
            surface.drawBitmap(bmp, blitRect, blitRect, null)
        } finally {
            holder.unlockCanvasAndPost(surface)
        }

        recentDamage.addLast(Rect(region))
        while (recentDamage.size >= SWAP_CHAIN_DEPTH) recentDamage.removeFirst()
    }

    /** Full-surface repaint — also the §4.5 ghosting lever (clean screen / tool change). */
    fun blitAll() {
        val bmp = backing ?: return
        recentDamage.clear()
        val surface = holder.lockCanvas() ?: return
        try {
            surface.drawBitmap(bmp, 0f, 0f, null)
        } finally {
            holder.unlockCanvasAndPost(surface)
        }
    }
}
