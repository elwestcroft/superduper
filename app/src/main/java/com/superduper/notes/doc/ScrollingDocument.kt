package com.superduper.notes.doc

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.superduper.notes.canvas.BackgroundSpacing
import com.superduper.notes.canvas.BackgroundStyle
import com.superduper.notes.canvas.BackgroundWeight
import com.superduper.notes.canvas.DryLayer
import com.superduper.notes.eink.EinkRefresh
import com.superduper.notes.eink.NativeInkView
import com.superduper.notes.eink.SELECTION_PAD
import com.superduper.notes.history.Command
import com.superduper.notes.history.CommandStack
import java.io.File

/**
 * A continuously scrollable document (SPEC.md §0, revised after Phase 0).
 *
 * **Ownership.** The app owns the document and the scroll; the firmware engine draws only
 * the live stroke. This is the inverse of the previous design, where the document lived
 * inside the engine and every scroll swapped its contents — which fought a one-screen
 * writing surface and produced stale windows, artifacts and 550 ms steps.
 *
 * It is also the architecture both working e-ink infinite-canvas apps converged on
 * (Notable, Notate on Boox): keep your own viewport bitmap, pause the vendor ink layer for
 * the duration of a gesture, blit-shift underneath it, and resume.
 *
 * **Scroll cost is proportional to distance, not screen area.** Shifting reuses the pixels
 * that stay on screen and rasterises only the newly exposed band, so a small step is
 * genuinely cheap — the property that lets scrolling read as motion rather than pagination.
 */
class ScrollingDocument(
    context: Context,
    private val ink: NativeInkView,
    private val viewportHeight: Int,
    private val viewportWidth: Int,
) {

    private val dir = File(context.filesDir, "doc").apply { mkdirs() }
    private val docFile = File(dir, "document.sdoc")
    private val prefs = context.getSharedPreferences("doc", Context.MODE_PRIVATE)

    /** Every stroke, in world coordinates. */
    private val strokes = mutableListOf<TchFile.Stroke>()

    private val dry = DryLayer(viewportWidth, viewportHeight)

    private val history = CommandStack()

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    /**
     * One entry of ink history.
     *
     * Both adding and erasing are the same operation with the sign flipped — a set of
     * strokes joins or leaves the document — so a single command type covers both and the
     * inverse is free.
     */
    private inner class InkEdit(
        private val added: List<TchFile.Stroke>,
        private val removed: List<TchFile.Stroke>,
        override val label: String,
    ) : Command {
        override val weight: Int
            get() = (added.sumOf { it.points.size } + removed.sumOf { it.points.size }) * TchFile.POINT_SIZE

        override fun apply() {
            strokes.removeAll(removed.toSet())
            strokes.addAll(added)
            afterEdit(added + removed)
        }

        override fun revert() {
            strokes.removeAll(added.toSet())
            strokes.addAll(removed)
            afterEdit(added + removed)
        }
    }

    /** Recompute extent and repaint after history moves the model underneath us. */
    private fun afterEdit(changed: Collection<TchFile.Stroke>) {
        val scrollBefore = scrollY
        // History has moved the model under any selection: the selected strokes may have
        // just been removed or replaced. Left in place, the selection's bounding box
        // collapsed to a degenerate rect and drew a stray line of marching ants that never
        // went away. A history step always ends the selection.
        selection.clear()
        ink.selectionBox = null
        contentBottom = strokes.maxOfOrNull { it.maxY } ?: 0
        clampScroll()
        ink.clearAll()
        markDirty()
        repaintChanged(changed, scrollBefore, "history")
        EinkRefresh.scheduleSettle(ink, PROMPT_SETTLE_MS, countTowardFullRefresh = false)
        // Undo/redo is one of two gestures that leave stale pen-engine pixels behind (the
        // other is moveSelection). See NativeInkView.forceSyncAfterEdit for what was tried and
        // ruled out first; this is the only thing that has ever cleared it.
        ink.forceSyncAfterEdit()
    }

    fun undo(): Boolean = history.undo() != null
    fun redo(): Boolean = history.redo() != null

    /** World Y of the top of the viewport. */
    var scrollY: Int = prefs.getInt(KEY_SCROLL, 0)
        private set

    var backgroundStyle: BackgroundStyle =
        runCatching { BackgroundStyle.valueOf(prefs.getString(KEY_BG_STYLE, null) ?: "") }
            .getOrDefault(BackgroundStyle.NONE)
        private set

    var backgroundSpacing: BackgroundSpacing =
        runCatching { BackgroundSpacing.valueOf(prefs.getString(KEY_BG_SPACING, null) ?: "") }
            .getOrDefault(BackgroundSpacing.MEDIUM)
        private set

    /**
     * Mark weight is fixed at BOLD rather than user-adjustable, for now — see the
     * SettingsPopup weight row that used to sit here; removed at the user's request after
     * it was tested and Bold was the only setting anyone wanted. The enum and the drawing
     * support for other weights stay in PageBackground in case that changes.
     */
    private val backgroundWeight = BackgroundWeight.BOLD

    /** Change the page background and repaint everything currently on screen with it. */
    fun setBackground(style: BackgroundStyle, spacing: BackgroundSpacing) {
        backgroundStyle = style
        backgroundSpacing = spacing
        prefs.edit()
            .putString(KEY_BG_STYLE, style.name)
            .putString(KEY_BG_SPACING, spacing.name)
            .apply()
        renderAll("background changed")
    }

    private var contentBottom: Int = 0

    val step: Int get() = (viewportHeight * STEP_FRACTION).toInt()

    fun open() {
        strokes.clear()
        val result = DocFile.read(docFile)
        strokes.addAll(result.strokes)
        // If the document could not be read, refuse to write over it. Saving a partial or
        // empty model back is what turns an unreadable file into a lost one.
        readOnly = !result.ok
        if (readOnly) Log.e(TAG, "DOC: document unreadable — saving disabled to protect it")
        contentBottom = strokes.maxOfOrNull { it.maxY } ?: 0
        // A restored position can point past content that has since been erased.
        scrollY = scrollY.coerceIn(0, maxOf(0, contentBottom))
        recoverViewport()
        ink.dryLayer = dry
        ink.onStrokeFinished = ::onStrokeFinished
        // Take the engine's own pixels for the stroke just drawn, so nothing changes
        // appearance at pen-up.
        ink.onEraseProgress = { batch ->
            Log.i(TAG, "DIAG onEraseProgress: ${batch.size} pts")
            erasePartial(batch, live = true)
        }
        ink.onEraseCancelled = { flushEraseGesture("spot erase (cancelled)") }
        ink.selectionHit = { x, y -> selectionHit(x, y) }
        ink.onSelectionMoved = { dx, dy -> moveSelection(dx, dy) }
        ink.onEngineStrokeRendered = { bmp, rect ->
            // ONLY in pen mode. In the outline modes the engine draws the lasso/erase circle
            // so it appears at native speed, and those pixels are scaffolding — committing
            // them would stamp the circle permanently into the document.
            if (ink.penMode == NativeInkView.PenMode.PEN) {
                if (measureInk) engineInkPixels = countInk(bmp, rect)
                dry.commitEnginePixels(bmp, rect)
                engineCommittedRect = Rect(rect)
                ink.invalidate()
            }
        }
        renderAll("open")
    }

    /**
     * Commit a finished stroke.
     *
     * The engine has already drawn it as wet ink; we take the same geometry (captured on
     * the UI thread, with pressure), store it in world space, paint it into the dry layer,
     * and clear the engine's buffer so the stroke is not drawn twice. Ordering matters:
     * paint before clearing, or the ink visibly blinks.
     */
    /**
     * One selected stroke, plus which of its points the lasso enclosed.
     *
     * The mask rather than the stroke alone, so a lasso can take part of a long stroke.
     * Keeping it as a mask is also what makes selecting **non-destructive**: splitting a
     * stroke the moment it was selected would permanently fragment the document every time
     * the user lassoed something and thought better of it. The split happens in
     * [moveSelection] and [deleteSelection], where an edit is actually intended.
     */
    private class Selected(val stroke: TchFile.Stroke, val mask: BooleanArray) {
        /** True when the lasso enclosed the whole stroke, so no split is needed. */
        val whole: Boolean get() = mask.all { it }
        val insideCount: Int get() = mask.count { it }
    }

    /** Strokes currently selected by the lasso, drawn with a marker outline. */
    private val selection = mutableListOf<Selected>()

    /**
     * Drop enclosed runs too short to be ink the user meant to select.
     *
     * Mutates [mask] in place and returns whether anything worth selecting survived.
     *
     * A lasso's edge inevitably clips a few samples off strokes passing nearby: measured on
     * a real document, two of five lassos selected 11 and 13 points spread over 1 and 3
     * strokes — slivers the user never saw, which then drew a tiny selection box somewhere
     * unexpected. A point count is the wrong filter for this (at 482 Hz a slow pen puts
     * dozens of samples in a few pixels), so the test is the run's own path length.
     */
    private fun pruneSliverRuns(stroke: TchFile.Stroke, mask: BooleanArray): Boolean {
        var any = false
        var runStart = -1

        fun close(endExclusive: Int) {
            if (runStart < 0) return
            // A run spanning the WHOLE stroke isn't a candidate for "the lasso's edge
            // clipped a few points off this" — there's no boundary crossing to clip at,
            // the entire stroke is either circled or it isn't. Applying the sliver filter
            // here dropped short strokes the user plainly circled — a dot, a tick, a small
            // letter — just because they were short, not because they were grazed.
            if (runStart == 0 && endExclusive == mask.size) {
                any = true
                runStart = -1
                return
            }
            var span = 0.0
            for (k in runStart + 1 until endExclusive) {
                val a = stroke.points[k - 1]
                val b = stroke.points[k]
                val dx = (b.x - a.x).toDouble()
                val dy = (stroke.yOf(b) - stroke.yOf(a)).toDouble()
                span += kotlin.math.sqrt(dx * dx + dy * dy)
            }
            if (span < MIN_SELECT_SPAN_PX || (endExclusive - runStart) < MIN_SELECT_POINTS) {
                for (k in runStart until endExclusive) mask[k] = false
            } else {
                any = true
            }
            runStart = -1
        }

        mask.indices.forEach { k -> if (mask[k]) { if (runStart < 0) runStart = k } else close(k) }
        close(mask.size)
        return any
    }

    /**
     * Split [sel] into the pieces its mask implies.
     *
     * Returns the enclosed runs and the surviving outside runs separately, so callers can
     * move or drop the former while leaving the latter exactly where they were. Runs
     * shorter than two points are dropped: a single point carries no segment and the
     * firmware's own area erase discards them for the same reason (SPEC §6.4).
     */
    private fun splitSelected(sel: Selected): Pair<List<TchFile.Stroke>, List<TchFile.Stroke>> {
        val inside = mutableListOf<TchFile.Stroke>()
        val outside = mutableListOf<TchFile.Stroke>()
        val stroke = sel.stroke
        var runStart = 0
        var runInside = sel.mask[0]

        fun flush(endExclusive: Int) {
            val len = endExclusive - runStart
            if (len >= 2) {
                val pts = (runStart until endExclusive).map { k ->
                    floatArrayOf(
                        stroke.points[k].x.toFloat(),
                        stroke.yOf(stroke.points[k]).toFloat(),
                        stroke.points[k].pressure,
                    )
                }
                TchFile.buildStroke(pts, viewportWidth, viewportHeight, stroke.style.objType, stroke.widthTenths)?.let {
                    if (runInside) inside.add(it) else outside.add(it)
                }
            }
        }

        for (k in 1 until sel.mask.size) {
            if (sel.mask[k] != runInside) {
                flush(k)
                runStart = k
                runInside = sel.mask[k]
            }
        }
        flush(sel.mask.size)
        return inside to outside
    }

    /** Set when the engine's pixels for the in-flight stroke have already been taken. */
    private var engineCommittedRect: Rect? = null

    /**
     * Strokes removed and added so far by the spot-erase gesture in progress.
     *
     * One undo entry per GESTURE, not per batch. The live path used to record an InkEdit
     * on every 12 px batch, so a one-second scrub wrote 50-150 entries against
     * CommandStack.MAX_ENTRIES = 100 — silently evicting the user's earlier history — and
     * undo then stepped back one batch at a time. A survivor piece that a later batch
     * splits again is removed from [gestureAdded] rather than added to [gestureRemoved],
     * so the final entry holds only strokes that existed before the gesture and pieces
     * that exist after it.
     */
    private val gestureRemoved = LinkedHashSet<TchFile.Stroke>()
    private val gestureAdded = LinkedHashSet<TchFile.Stroke>()

    private fun foldEraseBatch(removed: List<TchFile.Stroke>, added: List<TchFile.Stroke>) {
        removed.forEach { r -> if (!gestureAdded.remove(r)) gestureRemoved.add(r) }
        gestureAdded.addAll(added)
    }

    /** Close the gesture: record its single undo entry and reset. Safe to call when empty. */
    private fun flushEraseGesture(label: String) {
        if (gestureRemoved.isEmpty() && gestureAdded.isEmpty()) return
        history.record(InkEdit(gestureAdded.toList(), gestureRemoved.toList(), label))
        Log.i(TAG, "DOC: $label -> one undo entry (${gestureRemoved.size} removed, ${gestureAdded.size} added)")
        gestureRemoved.clear(); gestureAdded.clear()
    }

    /**
     * Debug: check the ported pen-width math against the engine's own rendering.
     *
     * Armed over adb (`--es tool penwidth`). While it is on, every finished stroke logs the
     * width the engine actually drew beside the width [TchRaster] predicts for the same
     * points, so the port is verified against pixels rather than by eye — an eyeball
     * comparison is what made the previous hand-fitted curve chase its own tail.
     */
    var measureInk: Boolean = false

    /** Ink pixels in the engine's rendering of the stroke in flight; -1 when not measured. */
    private var engineInkPixels: Int = -1

    /** Count the engine's dark pixels in [rect]. Its ink is opaque black on white. */
    private fun countInk(bmp: android.graphics.Bitmap, rect: Rect): Int {
        val r = Rect(rect)
        if (!r.intersect(0, 0, bmp.width, bmp.height)) return 0
        val px = IntArray(r.width() * r.height())
        bmp.getPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height())
        var dark = 0
        px.forEach { c ->
            if ((c ushr 24) != 0 &&
                (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) < 384
            ) dark++
        }
        return dark
    }

    /**
     * Log measured-vs-predicted ink weight for one stroke.
     *
     * Mean width is estimated as ink area over path length, which is robust to the stroke's
     * shape in a way that sampling a cross-section is not. The prediction is the same
     * average over [TchRaster]'s per-point widths, weighted by segment length and reading a
     * hairline (0) as the 1 px it actually paints.
     *
     * Both sums run over the engine's *kept* points, not every stored sample. The engine
     * draws straight lines between the samples that survive its ±4 px filter, so that is
     * the path its ink covers; measuring against the raw list adds up the pen's jitter as
     * though it were distance. On a slow stroke — where consecutive integer coordinates
     * differ by a pixel or less — that inflated the denominator enough to make correctly
     * predicted 3 px ink measure as 1.8, which read as a systematic over-prediction that
     * was really an artefact of this estimator.
     */
    private fun reportInkWidth(stroke: TchFile.Stroke) {
        val pixels = engineInkPixels
        engineInkPixels = -1
        if (pixels <= 0 || stroke.points.size < 2) return
        val w = TchRaster.widthsFor(stroke, ink.penWidth)
        val kept = TchRaster.keptIndices(stroke)
        if (kept.size < 2) return
        var length = 0.0
        var weighted = 0.0
        for (k in 1 until kept.size) {
            val a = stroke.points[kept[k - 1]]
            val b = stroke.points[kept[k]]
            val dx = (b.x - a.x).toDouble()
            val dy = (b.y - a.y).toDouble()
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            length += d
            weighted += d * (if (w[kept[k]] == 0f) 1f else w[kept[k]])
        }
        if (length < 1.0) return
        val measured = pixels / length
        val predicted = weighted / length
        Log.i(
            TAG,
            "INKWIDTH: measured=%.2fpx predicted=%.2fpx delta=%.2f  (ink=%d px, len=%.0f, std=%.1f, n=%d kept=%d)"
                .format(measured, predicted, measured - predicted, pixels, length, ink.penWidth, w.size, kept.size)
        )
    }

    /** Set by the toolbar when the lasso tool is active. */
    var lassoMode: Boolean = false
        set(value) { field = value; if (!value) clearSelection() }

    /**
     * Keep the viewport inside the document after an edit shrinks it.
     *
     * Without this, clearing while scrolled deep left a blank viewport at that depth — and
     * the next stroke was committed there, silently reinstating the empty space above it.
     */
    private fun clampScroll() {
        val max = maxOf(0, contentBottom)
        if (scrollY > max) {
            scrollY = max
            scrollDirty = true
        }
    }

    /** Force a full re-render, e.g. after changing pen weight. */
    fun redraw() = renderAll("redraw")

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        // Just drop the overlay. Nothing in the document changed.
        ink.selectionBox = null
    }

    /** Bounds of the current selection in view coordinates, or null. */
    fun selectionBoundsInView(): Rect? {
        if (selection.isEmpty()) return null
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        // Over the enclosed points only. Using each stroke's full bounds would put the box
        // around the whole of every partly-selected stroke, which is not what was picked up.
        selection.forEach { sel ->
            sel.mask.indices.forEach { k ->
                if (sel.mask[k]) {
                    val p = sel.stroke.points[k]
                    val vy = sel.stroke.yOf(p) - scrollY
                    if (p.x < minX) minX = p.x
                    if (p.x > maxX) maxX = p.x
                    if (vy < minY) minY = vy
                    if (vy > maxY) maxY = vy
                }
            }
        }
        if (minX > maxX) return null
        val pad = 24
        return Rect(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }

    /**
     * Is a view-space point close enough to the selection to count as picking it up?
     *
     * Proximity to the ink itself, deliberately not to its bounding box. A lasso that
     * catches strokes spread down the page has a bounding box covering most of the screen,
     * and testing against that box turned *every* later pen-down into a drag — including
     * the next lasso the user meant to draw. That silently hauled the whole selection
     * across the document, which is what made strokes from far up the page appear in the
     * viewport as if from nowhere.
     */
    fun selectionHit(x: Float, y: Float): Boolean {
        if (selection.isEmpty()) return false
        // The handle is the box the user can actually see. Requiring the pen to land within
        // a few tens of pixels of enclosed ink was the opposite mistake to the original
        // bug: correct in principle, but a drag started in the visible empty space inside
        // the box did nothing at all, which reads as the tool ignoring you.
        //
        // The box is safe to use now in a way it was not before, because it is computed
        // from the enclosed points only. The screen-swallowing box that captured every
        // pen-down came from taking the full bounds of every partly-selected stroke.
        return selectionBoundsInView()?.contains(x.toInt(), y.toInt()) == true
    }

    /**
     * Move the selection by a view-space delta.
     *
     * Rebuilds each selected stroke at the new position rather than mutating points: point
     * records are shared between translated copies, so mutating them in place would corrupt
     * other references and silently invalidate cached bounds. One undo entry covers the
     * whole move.
     */
    fun moveSelection(dx: Int, dy: Int): Boolean {
        if (selection.isEmpty() || (dx == 0 && dy == 0)) return false

        // Clamp so a drag cannot push ink off the top of the document. Nothing scrolls
        // above y=0, so content moved past it becomes unreachable — an upward drag used to
        // strand strokes at negative y where no scroll position could reach them.
        var minSelY = Int.MAX_VALUE
        selection.forEach { sel ->
            sel.mask.indices.forEach { k ->
                if (sel.mask[k]) {
                    val wy = sel.stroke.yOf(sel.stroke.points[k])
                    if (wy < minSelY) minSelY = wy
                }
            }
        }
        val clampedDy = if (minSelY + dy < 0) -minSelY else dy
        if (dx == 0 && clampedDy == 0) return false
        if (clampedDy != dy) Log.i(TAG, "DOC: move clamped dy $dy -> $clampedDy (top of document)")

        val removed = mutableListOf<TchFile.Stroke>()
        val added = mutableListOf<TchFile.Stroke>()
        val newSelection = mutableListOf<TchFile.Stroke>()
        // Where the selection was, so the vacated area can be cleaned at quality too.
        val boxBefore = selectionBoundsInView()

        selection.forEach { sel ->
            removed.add(sel.stroke)
            if (sel.whole) {
                val pts = sel.stroke.points.map { p ->
                    floatArrayOf(
                        (p.x + dx).toFloat(),
                        (sel.stroke.yOf(p) + clampedDy).toFloat(),
                        p.pressure,
                    )
                }
                TchFile.buildStroke(pts, viewportWidth, viewportHeight, sel.stroke.style.objType, sel.stroke.widthTenths)?.let {
                    added.add(it); newSelection.add(it)
                }
            } else {
                // Split now: the enclosed runs travel, the rest stays put.
                val (inside, outside) = splitSelected(sel)
                outside.forEach { added.add(it) }
                inside.forEach { piece ->
                    val pts = piece.points.map { p ->
                        floatArrayOf(
                            (p.x + dx).toFloat(),
                            (piece.yOf(p) + clampedDy).toFloat(),
                            p.pressure,
                        )
                    }
                    TchFile.buildStroke(pts, viewportWidth, viewportHeight, piece.style.objType, piece.widthTenths)?.let {
                        added.add(it); newSelection.add(it)
                    }
                }
            }
        }
        if (added.isEmpty()) return false

        strokes.removeAll(removed.toSet())
        strokes.addAll(added)
        selection.clear()
        // The moved pieces stay selected, now as whole strokes in their own right.
        newSelection.forEach { selection.add(Selected(it, BooleanArray(it.points.size) { true })) }
        contentBottom = strokes.maxOfOrNull { it.maxY } ?: 0
        history.record(InkEdit(added, removed, "move ${newSelection.size}"))
        markDirty()
        // Repaint ONLY where ink actually changed: where the selection was, and where it is
        // now. renderAll repainted every visible stroke through TchRaster, swapping the
        // engine's own pixels for ours on ink nowhere near the move — a fixed-nib stroke on
        // the far side of the page visibly changed weight when an unrelated selection was
        // dragged. renderExposed clips to the rect and clears it to white first, so this is a
        // genuine regional repaint; strokes that merely overlap the region are re-rendered,
        // which is unavoidable, but nothing outside it is touched.
        val dirty = Rect()
        boxBefore?.let { dirty.union(it) }
        val boxAfter = selectionBoundsInView()
        boxAfter?.let { dirty.union(it) }
        val pad = SELECTION_PAD + INK_OVERHANG
        dirty.inset(-pad, -pad)
        if (dirty.intersect(0, 0, viewportWidth, viewportHeight)) {
            renderExposed(dirty)
            // GL, not GC: quality repaint of the region without the black/white cycle.
            ink.refreshRegionAtQuality(dirty, pad = 8)
        }
        ink.selectionBox = boxAfter
        Log.i(TAG, "DOC: moved ${newSelection.size} by ($dx,$clampedDy) region=$dirty")
        // Full settle, for the reason given in lassoSelect: the whole-view quality repaint is
        // what re-syncs the EPD after a regional update, and skipping it made erased ink
        // ghost under the next pen stroke over the vacated area.
        EinkRefresh.scheduleSettle(ink, PROMPT_SETTLE_MS, countTowardFullRefresh = false)
        // The lasso outline was drawn live by the engine's own fast path at boxBefore; a plain
        // repaint doesn't clear whatever it leaves behind there, and it showed up as a
        // persistent line of marching ants at the vacated spot. See
        // NativeInkView.forceSyncAfterEdit.
        ink.forceSyncAfterEdit()
        return true
    }

    fun deleteSelection(): Int {
        if (selection.isEmpty()) return 0
        val removed = mutableListOf<TchFile.Stroke>()
        val added = mutableListOf<TchFile.Stroke>()
        var deletedPieces = 0

        selection.forEach { sel ->
            removed.add(sel.stroke)
            if (sel.whole) {
                deletedPieces++
            } else {
                // Only the enclosed runs go; the rest of the stroke survives as its own
                // shorter strokes, so lassoing the middle of a line leaves the two ends.
                val (inside, outside) = splitSelected(sel)
                added.addAll(outside)
                deletedPieces += inside.size
            }
        }
        val scrollBefore = scrollY
        strokes.removeAll(removed.toSet())
        strokes.addAll(added)
        selection.clear()
        contentBottom = strokes.maxOfOrNull { it.maxY } ?: 0
        clampScroll()
        history.record(InkEdit(added, removed, "lasso delete $deletedPieces"))
        markDirty()
        repaintChanged(removed + added, scrollBefore, "lasso deleted $deletedPieces")
        return deletedPieces
    }

    /** Close the lasso path and select everything it encloses. */
    private fun lassoSelect(points: List<FloatArray>) {
        if (points.size < 3) { ink.clearAll(); return }
        val poly = FloatArray(points.size * 2)
        var vMinX = Float.MAX_VALUE; var vMaxX = -Float.MAX_VALUE
        var vMinY = Float.MAX_VALUE; var vMaxY = -Float.MAX_VALUE
        points.forEachIndexed { i, p ->
            poly[i * 2] = p[0]
            poly[i * 2 + 1] = p[1] + scrollY
            // The outline's own extent, in view space — the region the engine dirtied and
            // therefore the only part of the panel that needs cleaning afterwards.
            if (p[0] < vMinX) vMinX = p[0]
            if (p[0] > vMaxX) vMaxX = p[0]
            if (p[1] < vMinY) vMinY = p[1]
            if (p[1] > vMaxY) vMaxY = p[1]
        }
        val outlineRect = Rect(vMinX.toInt(), vMinY.toInt(), vMaxX.toInt(), vMaxY.toInt())
        selection.clear()
        strokes.forEach { st ->
            val mask = StrokeHitTest.insideMask(st, poly)
            if (pruneSliverRuns(st, mask)) selection.add(Selected(st, mask))
        }
        ink.clearAll()
        val partial = selection.count { !it.whole }
        Log.i(
            TAG,
            "DOC: lasso selected ${selection.size} of ${strokes.size} " +
                "($partial partial, ${selection.sumOf { it.insideCount }} points)"
        )
        if (selection.isEmpty()) diagnoseEmptyLasso(poly)
        // Selecting changes no strokes, so nothing in the dry layer needs repainting — and
        // repainting it was actively harmful: renderAll swapped the engine's own pixels for
        // TchRaster's on every visible stroke, so drawing a lasso changed the weight of ink
        // that had nothing to do with it. Only the overlay box changes here.
        ink.selectionBox = selectionBoundsInView()
        Log.i(TAG, "DOC: lasso ${selection.size} (overlay only, no repaint)")
        // Settle now, not in 500 ms, and clean only where the outline was.
        //
        // The lasso outline is drawn by the engine straight to the panel — it has to be,
        // per SPEC §0.0a, or it would not keep up with the pen. Clearing the engine's
        // buffer does not remove those pixels from the display; only a repaint through the
        // quality path does. The settle's debounce exists so a run of erase strokes stays
        // in fast mode, but a lasso ends at pen-up, and waiting meant the outline sat there
        // looking like committed ink while the selection box arrived half a second late.
        //
        // The residue is cleared by a GC repaint of the outline's own rectangle rather than
        // a forced full update. Forcing the full update did clear it, but a full update is
        // a black flash across the whole panel — EinkRefresh's own note records that
        // running it every settle "brought back the black flash", and it does.
        ink.refreshRegionAtQuality(outlineRect)
        // Full settle — reset the waveform AND repaint the view through the quality path.
        // The mode-only settle (repaint = false) was tried to avoid a flash that was in fact
        // caused by the GC waveform, since fixed. Without the whole-view repaint the EPD
        // controller's image shadow is left inconsistent after the regional GL update, and
        // the next fast-waveform stroke drawn over that region briefly shows the OLD pixels
        // — erased ink ghosting under the pen. Erase, which kept its full settle, never
        // ghosted: that is the discriminator.
        EinkRefresh.scheduleSettle(ink, PROMPT_SETTLE_MS, countTowardFullRefresh = false)
    }

    /**
     * Why did a lasso select nothing?
     *
     * Only runs on an empty selection, so it costs nothing in normal use. Reports the
     * lasso's own extent and then, for the strokes whose bounds actually overlap it, how
     * many of their points the winding test placed inside. That distinguishes the three
     * candidate causes without guesswork: a coordinate-space mismatch (no overlap at all),
     * a too-strict enclosure threshold (overlap, high inside fraction, still rejected), or
     * a genuinely empty region (overlap, near-zero inside fraction).
     */
    private fun diagnoseEmptyLasso(poly: FloatArray) {
        var pMinX = Float.MAX_VALUE; var pMaxX = -Float.MAX_VALUE
        var pMinY = Float.MAX_VALUE; var pMaxY = -Float.MAX_VALUE
        var i = 0
        while (i < poly.size) {
            if (poly[i] < pMinX) pMinX = poly[i]
            if (poly[i] > pMaxX) pMaxX = poly[i]
            if (poly[i + 1] < pMinY) pMinY = poly[i + 1]
            if (poly[i + 1] > pMaxY) pMaxY = poly[i + 1]
            i += 2
        }
        Log.i(
            TAG,
            "LASSODIAG: poly n=${poly.size / 2} world x=${pMinX.toInt()}..${pMaxX.toInt()} " +
                "y=${pMinY.toInt()}..${pMaxY.toInt()} scrollY=$scrollY"
        )
        val overlapping = strokes.filter {
            it.maxY >= pMinY && it.minY <= pMaxY && it.maxX >= pMinX && it.minX <= pMaxX
        }
        Log.i(TAG, "LASSODIAG: ${overlapping.size} strokes overlap the lasso bounds")
        overlapping.take(6).forEach { st ->
            val inside = StrokeHitTest.insideCount(st, poly)
            Log.i(
                TAG,
                "LASSODIAG:   stroke n=${st.points.size} x=${st.minX}..${st.maxX} " +
                    "y=${st.minY}..${st.maxY} inside=$inside " +
                    "(${100 * inside / st.points.size.coerceAtLeast(1)}%, need 80%)"
            )
        }
        if (overlapping.isEmpty() && strokes.isNotEmpty()) {
            val near = strokes.minByOrNull { kotlin.math.abs(it.minY - pMinY) }!!
            Log.i(
                TAG,
                "LASSODIAG: nearest stroke instead at x=${near.minX}..${near.maxX} " +
                    "y=${near.minY}..${near.maxY} — check coordinate spaces"
            )
        }
    }

    /** Which eraser the toolbar has selected. */
    enum class EraseMode { SPOT, AREA }

    var eraseMode: EraseMode = EraseMode.SPOT

    private fun onStrokeFinished(points: List<FloatArray>) {
        if (lassoMode) { lassoSelect(points); return }
        if (ink.erasing) {
            if (eraseMode == EraseMode.SPOT) {
                // Streaming already erased along the drag; this catches the final samples
                // and settles the display.
                erasePartial(points)
            } else {
                eraseWhole(points)
            }
            return
        }
        val world = points.map { floatArrayOf(it[0], it[1] + scrollY, it[2]) }
        val stroke = TchFile.buildStroke(
            world, viewportWidth, viewportHeight,
            ink.penStyle.objType, (ink.penWidth * 10f).toInt(),
        ) ?: return
        if (measureInk) reportInkWidth(stroke)
        strokes.add(stroke)
        contentBottom = maxOf(contentBottom, stroke.maxY)

        // If the engine already handed us its rendering for this stroke, keep it — it is
        // exactly what the user watched appear. Only rasterise ourselves as a fallback.
        if (engineCommittedRect == null) {
            dry.commitStroke(stroke.translated(-scrollY), ink.penWidth)
        }
        engineCommittedRect = null
        ink.invalidate()
        ink.clearAll()
        history.record(InkEdit(listOf(stroke), emptyList(), "draw"))
        markDirty()
    }

    /**
     * Delete every stroke the eraser path touched.
     *
     * Whole-stroke deletion, matching the firmware's tap-erase (`objType 2`) rather than
     * its trail eraser: it is predictable, it keeps the model clean, and it is what the
     * user reaches for most. Splitting a stroke where the eraser crosses it is SPEC §6.4's
     * area mode and can follow once this is proven.
     *
     * The sweep between consecutive samples matters — the pen reports at ~480 Hz but a
     * fast swipe still moves far enough between samples to jump a thin stroke.
     */
    /**
     * Spot eraser: remove exactly the parts of strokes the eraser passed over.
     *
     * Each affected stroke is split at the covered spans and its surviving runs become
     * shorter strokes, so dragging through the middle of a line leaves two pieces rather
     * than deleting the whole thing. This is the behaviour of the native app's spot eraser,
     * and it is why per-point coverage is computed instead of a simple hit test.
     *
     * Runs shorter than two points are dropped — a single orphaned sample renders as a dot
     * the user did not draw.
     */
    private fun erasePartial(points: List<FloatArray>, live: Boolean = false) {
        if (points.isEmpty()) return
        val t0 = SystemClock.uptimeMillis()
        val path = FloatArray(points.size * 2)
        points.forEachIndexed { i, p ->
            path[i * 2] = p[0]
            path[i * 2 + 1] = p[1] + scrollY
        }

        val removed = mutableListOf<TchFile.Stroke>()
        val added = mutableListOf<TchFile.Stroke>()

        strokes.toList().forEach { stroke ->
            val mask = StrokeHitTest.coveredPoints(stroke, path, ERASER_RADIUS)
            if (mask.none { it }) return@forEach
            removed.add(stroke)

            var runStart = -1
            fun flush(endExclusive: Int) {
                if (runStart < 0) return
                val len = endExclusive - runStart
                // A surviving fragment starts a brand-new stroke at whatever point the
                // erase left behind — its predecessor, which the width formula needs to
                // judge pen speed, was just erased. TchRaster treats a stroke's first
                // point as a fresh pen-down with no speed data, which is correct for a
                // real stroke start but not for a cut — so a fragment's very first pixels
                // render at the wrong width. For a long fragment that's invisible; for a
                // short one (an eraser grazing a stroke rather than genuinely trimming it)
                // it dominates the whole visible sliver — measured on device as fat
                // mismatched dots trailing into thin lines. Same fix as lasso's
                // pruneSliverRuns: below a minimum length, it isn't ink the user meant to
                // keep, so drop it with the rest of the erased stroke instead of leaving a
                // visibly wrong-width stub.
                if (len >= MIN_ERASE_FRAGMENT_POINTS) {
                    var span = 0.0
                    for (k in runStart + 1 until endExclusive) {
                        val a = stroke.points[k - 1]
                        val b = stroke.points[k]
                        val dx = (b.x - a.x).toDouble()
                        val dy = (stroke.yOf(b) - stroke.yOf(a)).toDouble()
                        span += kotlin.math.sqrt(dx * dx + dy * dy)
                    }
                    if (span >= MIN_ERASE_FRAGMENT_SPAN_PX) {
                        val pts = (runStart until endExclusive).map { k ->
                            floatArrayOf(
                                stroke.points[k].x.toFloat(),
                                stroke.yOf(stroke.points[k]).toFloat(),
                                stroke.points[k].pressure,
                            )
                        }
                        TchFile.buildStroke(pts, viewportWidth, viewportHeight, stroke.style.objType, stroke.widthTenths)
                            ?.let { added.add(it) }
                    }
                }
                runStart = -1
            }
            mask.indices.forEach { k ->
                if (mask[k]) flush(k) else if (runStart < 0) runStart = k
            }
            flush(mask.size)
        }

        val tHit = SystemClock.uptimeMillis()
        if (removed.isEmpty()) {
            // Pen-up with nothing new to remove still closes the gesture's undo entry.
            if (!live) { ink.clearAll(); flushEraseGesture("spot erase") }
            return
        }
        strokes.removeAll(removed.toSet())
        strokes.addAll(added)
        contentBottom = strokes.maxOfOrNull { it.maxY } ?: 0
        if (!live) { clampScroll(); ink.clearAll() }
        // Accumulate into the gesture; the entry is recorded once, at pen-up or cancel.
        foldEraseBatch(removed, added)
        if (!live) flushEraseGesture("spot erase")
        markDirty()

        if (live) {
            // Repaint only the region the eraser just passed through — not the full
            // extent of whatever strokes it touched. It used to be the latter: the band
            // was the union of removed+added strokes' own bounding boxes, so erasing one
            // point off a stroke that ran corner to corner repainted the whole diagonal
            // (measured: up to 1214x928, ERASEPROF's "band" figure) even though only a
            // dot-sized gap actually changed. A split fragment's pixels away from the cut
            // are identical to what was already on screen — same points, same width — so
            // there is nothing to redraw there; only the touched neighbourhood needs it.
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            points.forEach { p ->
                if (p[0] < minX) minX = p[0]
                if (p[0] > maxX) maxX = p[0]
                if (p[1] < minY) minY = p[1]
                if (p[1] > maxY) maxY = p[1]
            }
            // Padded by the eraser's own hit radius (the gap can extend that far past the
            // sampled path) plus INK_OVERHANG (how far a stroke can paint beyond its point
            // bounds), both already in view space — points are, this needs no scrollY term.
            val pad = ERASER_RADIUS + INK_OVERHANG
            val band = Rect(
                (minX - pad).toInt().coerceIn(0, viewportWidth),
                (minY - pad).toInt().coerceIn(0, viewportHeight),
                (maxX + pad).toInt().coerceIn(0, viewportWidth),
                (maxY + pad).toInt().coerceIn(0, viewportHeight),
            )
            if (!band.isEmpty) {
                renderExposed(band)
                // The eink-aware invalidate, on the fast waveform. The deprecated
                // invalidate(l,t,r,b) this replaced is discarded by a hardware-accelerated
                // view, so the panel never showed the erase until pen-up.
                ink.refreshRegionFast(band)
            }
            // Where the live budget goes, per batch: hit-testing every stroke, or the
            // repaint. Read with `adb logcat -s SuperDuper | grep ERASEPROF`.
            Log.i(
                TAG,
                "ERASEPROF: batch=${points.size}pts strokes=${strokes.size} hit=${tHit - t0}ms " +
                    "total=${SystemClock.uptimeMillis() - t0}ms removed=${removed.size} added=${added.size} " +
                    "band=${band.width()}x${band.height()}"
            )
        } else {
            repaintChanged(removed + added, scrollY, "spot erased ${removed.size}->${added.size} in ${SystemClock.uptimeMillis() - t0}ms")
        }
    }

    /**
     * Area eraser: remove whole strokes the eraser touched or enclosed.
     *
     * Scribble across things and they go; draw a loop around things and they go. Both are
     * whole-object deletion, which is what makes it feel different from the spot eraser
     * rather than just coarser.
     */
    private fun eraseWhole(points: List<FloatArray>) {
        if (points.isEmpty()) return
        val t0 = SystemClock.uptimeMillis()
        val doomed = HashSet<TchFile.Stroke>()

        // Touched.
        var prevX = points[0][0]
        var prevY = points[0][1] + scrollY
        strokes.forEach { if (StrokeHitTest.hits(it, prevX, prevY, ERASER_RADIUS)) doomed.add(it) }
        for (i in 1 until points.size) {
            val x = points[i][0]
            val y = points[i][1] + scrollY
            strokes.forEach {
                if (it !in doomed && StrokeHitTest.hitsSweep(it, prevX, prevY, x, y, ERASER_RADIUS)) {
                    doomed.add(it)
                }
            }
            prevX = x; prevY = y
        }

        // Enclosed, so circling something deletes it even if the loop never crosses it.
        if (points.size >= 3) {
            val poly = FloatArray(points.size * 2)
            points.forEachIndexed { i, p ->
                poly[i * 2] = p[0]
                poly[i * 2 + 1] = p[1] + scrollY
            }
            strokes.forEach {
                if (it !in doomed && StrokeHitTest.enclosed(it, poly, 0.7f)) doomed.add(it)
            }
        }

        if (doomed.isEmpty()) {
            Log.i(TAG, "DOC: area erase hit nothing")
            ink.clearAll()
            return
        }
        val scrollBefore = scrollY
        strokes.removeAll(doomed)
        contentBottom = strokes.maxOfOrNull { it.maxY } ?: 0
        clampScroll()
        ink.clearAll()
        history.record(InkEdit(emptyList(), doomed.toList(), "area erase ${doomed.size}"))
        markDirty()
        repaintChanged(doomed, scrollBefore, "area erased ${doomed.size} in ${SystemClock.uptimeMillis() - t0}ms")
    }

    private fun eraseAlongLegacy(points: List<FloatArray>) {
        if (points.isEmpty()) return
        val t0 = SystemClock.uptimeMillis()
        val doomed = HashSet<TchFile.Stroke>()

        var prevX = points[0][0]
        var prevY = points[0][1] + scrollY
        strokes.forEach { s ->
            if (StrokeHitTest.hits(s, prevX, prevY, ERASER_RADIUS)) doomed.add(s)
        }
        for (i in 1 until points.size) {
            val x = points[i][0]
            val y = points[i][1] + scrollY
            strokes.forEach { s ->
                if (s !in doomed && StrokeHitTest.hitsSweep(s, prevX, prevY, x, y, ERASER_RADIUS)) {
                    doomed.add(s)
                }
            }
            prevX = x; prevY = y
        }

        if (doomed.isEmpty()) {
            Log.i(TAG, "DOC: erase hit nothing")
            ink.clearAll()
            return
        }
        val scrollBefore = scrollY
        strokes.removeAll(doomed)
        contentBottom = strokes.maxOfOrNull { it.maxY } ?: 0
        clampScroll()
        ink.clearAll()
        // One gesture is one undo entry, however many strokes it swept away — undoing an
        // eraser drag stroke-by-stroke would be miserable.
        history.record(InkEdit(emptyList(), doomed.toList(), "erase ${doomed.size}"))
        markDirty()
        repaintChanged(doomed, scrollBefore, "erased ${doomed.size} in ${SystemClock.uptimeMillis() - t0}ms")
    }

    fun scrollBy(delta: Int) {
        val target = (scrollY + delta).coerceIn(0, maxOf(0, contentBottom))
        if (target == scrollY) {
            Log.i(TAG, "DOC: at ${if (delta > 0) "end" else "start"} (y=$scrollY, bottom=$contentBottom)")
            return
        }
        val t0 = SystemClock.uptimeMillis()
        val dy = target - scrollY

        // No engine call here. This used to call setPWEnabled(false) to "pause the ink
        // layer", which is a verified no-op on this firmware — the field is never read.
        // Scrolling works because the app owns the pixels, not because the engine paused.
        val exposed = dry.shift(dy)
        scrollY = target
        // Written at gesture end rather than per 24px quantum, and again on save — a
        // position lost on sleep looks exactly like lost work, because the content is
        // off-screen below.
        scrollDirty = true
        renderExposed(exposed)
        ink.invalidate()

        Log.i(TAG, "DOC: scroll dy=$dy -> y=$scrollY band=${exposed.height()}px " +
            "in ${SystemClock.uptimeMillis() - t0}ms (bottom=$contentBottom)")
    }

    private var scrollDirty = false

    /** Persist the viewport position once a gesture ends. */
    fun commitScrollPosition() {
        if (!scrollDirty) return
        scrollDirty = false
        prefs.edit().putInt(KEY_SCROLL, scrollY).apply()
    }

    /**
     * Where to open when the saved position would show nothing.
     *
     * Reopening at y=0 with content far below reads as "everything is gone", which is the
     * worst possible failure to *appear* to have. If the saved position shows no ink but
     * the document has some, land on the last thing written instead.
     */
    private fun recoverViewport() {
        val visibleHere = strokes.any { it.maxY >= scrollY && it.minY <= scrollY + viewportHeight }
        if (visibleHere || strokes.isEmpty()) return
        val lastY = strokes.maxOf { it.maxY }
        scrollY = maxOf(0, lastY - viewportHeight / 2)
        scrollDirty = true
        Log.w(TAG, "DOC: saved position showed no ink; recovered to y=$scrollY (content ends $lastY)")
    }

    fun pageDown() = scrollBy(step)
    fun pageUp() = scrollBy(-step)

    /** Rasterise only the strokes intersecting a newly exposed band. */
    private fun renderExposed(band: Rect) {
        // Inflate by the widest a stroke can paint beyond its point bounds — half the
        // maximum stroke width plus the round cap. Filtering on raw point bounds left
        // 1-3px white slices through ink after a scroll, because the band was cleared to
        // white while a stroke whose points sat just outside it still painted into it.
        val top = scrollY + band.top - INK_OVERHANG
        val bottom = scrollY + band.bottom + INK_OVERHANG
        val visible = strokes
            .filter { it.maxY >= top && it.minY <= bottom }
            .map { it.translated(-scrollY) }
        dry.renderBand(band, visible, ink.penWidth, scrollY, backgroundStyle, backgroundSpacing, backgroundWeight)
    }

    /**
     * Repaint only the region covered by [changed] strokes, at quality.
     *
     * The cure for the wet/dry seam, applied to every operation that changes strokes.
     * renderAll re-renders every visible stroke through TchRaster, which swaps the engine's
     * own pixels for ours on ink that did not change — visible as that ink shifting weight
     * on every erase and every undo. Repainting only the changed strokes' bounds confines
     * that to ink that genuinely had to be redrawn (SPEC §6.5a).
     *
     * [scrollBefore] guards the one case where regional is wrong: if clampScroll moved the
     * viewport because the content shrank, everything on screen shifted and only a full
     * repaint is correct. Callers capture scrollY before clamping and pass it here.
     */
    private fun repaintChanged(changed: Collection<TchFile.Stroke>, scrollBefore: Int, why: String) {
        if (scrollBefore != scrollY) { renderAll("$why (viewport moved)"); return }
        if (changed.isEmpty()) { ink.selectionBox = selectionBoundsInView(); return }
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        changed.forEach {
            if (it.minX < minX) minX = it.minX
            if (it.maxX > maxX) maxX = it.maxX
            if (it.minY < minY) minY = it.minY
            if (it.maxY > maxY) maxY = it.maxY
        }
        val dirty = Rect(minX, minY - scrollY, maxX, maxY - scrollY)
        dirty.inset(-INK_OVERHANG, -INK_OVERHANG)
        if (dirty.intersect(0, 0, viewportWidth, viewportHeight)) {
            renderExposed(dirty)
            ink.refreshRegionAtQuality(dirty, pad = 8)
        }
        ink.selectionBox = selectionBoundsInView()
        Log.i(TAG, "DOC: $why y=$scrollY ${strokes.size} strokes bottom=$contentBottom region=$dirty")
    }

    private fun renderAll(why: String) {
        val t0 = SystemClock.uptimeMillis()
        dry.clear()
        renderExposed(Rect(0, 0, viewportWidth, viewportHeight))
        // The box is an overlay on the view, not paint in this layer — see
        // NativeInkView.selectionBox for why. Keep it in step after any repaint.
        ink.selectionBox = selectionBoundsInView()
        ink.invalidate()
        Log.i(TAG, "DOC: $why y=$scrollY ${strokes.size} strokes bottom=$contentBottom " +
            "(${SystemClock.uptimeMillis() - t0}ms)")
    }

    /**
     * Persist now. Cheap enough to call often; [markDirty] debounces the common case.
     */
    private var readOnly = false

    fun save() {
        pendingSave?.let { saveHandler.removeCallbacks(it) }
        pendingSave = null
        if (!dirty || readOnly) return
        // Keep the dirty flag when the write fails, or the next save no-ops on a clean
        // flag and the work is gone with only a log line to show for it.
        if (DocFile.write(docFile, strokes)) {
            dirty = false
            commitScrollPosition()
            Log.i(TAG, "DOC: saved ${strokes.size} strokes (${docFile.length()}B)")
        } else {
            Log.e(TAG, "DOC: save FAILED, ${strokes.size} strokes still pending")
        }
    }

    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSave: Runnable? = null
    private var dirty = false

    /**
     * Schedule a checkpoint.
     *
     * Saving only in onPause meant a native crash, an OOM kill or a flat battery lost
     * everything drawn since the app was last backgrounded. Every edit now arms a short
     * timer, so at most a few seconds of work is ever at risk.
     */
    private fun markDirty() {
        dirty = true
        pendingSave?.let { saveHandler.removeCallbacks(it) }
        val r = Runnable { pendingSave = null; save() }
        pendingSave = r
        saveHandler.postDelayed(r, AUTOSAVE_DELAY_MS)
    }

    /** Release timers and bitmaps. Call from the activity's teardown. */
    fun dispose() {
        // Flush before cancelling: an edit arriving after onPause would otherwise be
        // dropped when the timer is discarded.
        save()
        pendingSave?.let { saveHandler.removeCallbacks(it) }
        pendingSave = null
        dry.release()
    }

    fun clearAll() {
        if (strokes.isEmpty()) return
        val removed = strokes.toList()
        strokes.clear()
        contentBottom = 0
        clampScroll()
        ink.clearAll()
        history.record(InkEdit(emptyList(), removed, "clear ${removed.size}"))
        markDirty()
        renderAll("clear")
    }

    /** 1-indexed page the viewport is currently showing, a page being one screen height. */
    fun currentPage(): Int = scrollY / viewportHeight + 1

    /** Total pages: at least enough to cover the content, at least enough to cover scroll. */
    fun totalPages(): Int = maxOf(1, (contentBottom + viewportHeight - 1) / viewportHeight, currentPage())

    fun label(): String = "Page ${currentPage()} of ${totalPages()}"

    /**
     * Render the whole document to a PDF, one page per screen-height band — the same
     * pages [currentPage]/[totalPages] count, and write it to [out]. Synchronous; call off
     * the UI thread for a document with many pages. Does not close [out] — the caller
     * owns the stream (typically opened on a SAF `content://` URI from the user's own
     * "Save As" picker).
     */
    fun exportPdf(out: java.io.OutputStream) {
        val pdf = android.graphics.pdf.PdfDocument()
        for (i in 0 until totalPages()) {
            val pageStartY = i * viewportHeight
            val info = android.graphics.pdf.PdfDocument.PageInfo
                .Builder(viewportWidth, viewportHeight, i + 1).create()
            val page = pdf.startPage(info)
            page.canvas.drawColor(android.graphics.Color.WHITE)
            val visible = strokes
                .filter { it.maxY >= pageStartY && it.minY <= pageStartY + viewportHeight }
                .map { it.translated(-pageStartY) }
            TchRaster.drawInto(page.canvas, visible, ink.penWidth)
            pdf.finishPage(page)
        }
        pdf.writeTo(out)
        pdf.close()
    }

    // --- test harness ---------------------------------------------------------------
    // Chauvet denies INJECT_EVENTS to the shell, so a pen cannot be simulated over adb.
    // Everything downstream of stroke capture — persistence, undo, erase, scroll — is
    // testable without one, provided strokes can be seeded synthetically.

    /** Seed [count] deterministic strokes spread down the document, for testing. */
    fun seedStrokes(count: Int, spacingY: Int = 120, startY: Int = 200) {
        val added = ArrayList<TchFile.Stroke>(count)
        repeat(count) { i ->
            val y = startY.toFloat() + i * spacingY
            val pts = (0..40).map { k ->
                val t = k / 40f
                floatArrayOf(
                    200f + t * 1000f,
                    y + kotlin.math.sin(t * 6.28f) * 30f,
                    0.10f + 0.35f * t,   // sweep pressure so width variation is visible
                )
            }
            TchFile.buildStroke(pts, viewportWidth, viewportHeight)?.let { added.add(it) }
        }
        strokes.addAll(added)
        contentBottom = strokes.maxOfOrNull { it.maxY } ?: 0
        history.record(InkEdit(added, emptyList(), "seed $count"))
        markDirty()
        renderAll("seeded $count")
    }

    /** Erase at a document point, as the eraser would. Returns strokes removed. */
    fun eraseAtWorld(x: Float, worldY: Float): Int {
        val before = strokes.size
        eraseWhole(listOf(floatArrayOf(x, worldY - scrollY, 0.5f)))
        return before - strokes.size
    }

    /** Test hook: lasso a document-space rectangle. Returns strokes selected. */
    fun lassoRectWorld(x0: Float, y0: Float, x1: Float, y1: Float): Int {
        val pts = listOf(
            floatArrayOf(x0, y0 - scrollY, 0.5f),
            floatArrayOf(x1, y0 - scrollY, 0.5f),
            floatArrayOf(x1, y1 - scrollY, 0.5f),
            floatArrayOf(x0, y1 - scrollY, 0.5f),
        )
        lassoSelect(pts)
        return selection.size
    }

    /** Test hook: drag the spot eraser along a short horizontal path at a document y. */
    fun spotEraseAtWorld(x: Float, worldY: Float, span: Float = 60f): Int {
        val before = strokes.size
        val pts = (0..6).map { k ->
            floatArrayOf(x - span / 2 + span * k / 6f, worldY - scrollY, 0.5f)
        }
        erasePartial(pts)
        return strokes.size - before
    }

    fun selectionCount(): Int = selection.size

    fun strokeCount(): Int = strokes.size
    fun bottom(): Int = contentBottom

    private companion object {
        const val TAG = "SuperDuper"
        const val KEY_SCROLL = "scrollY"
        const val KEY_BG_STYLE = "bgStyle"
        const val KEY_BG_SPACING = "bgSpacing"

        /** A third of a screen: content visibly moves, most of the view persists. */
        const val STEP_FRACTION = 0.33f


        /** Eraser reach in px. Generous enough to feel forgiving on a 300 PPI panel. */
        const val ERASER_RADIUS = 18f


        /** How far ink can paint beyond its point bounds (half max width + cap). */
        const val INK_OVERHANG = 6

        const val AUTOSAVE_DELAY_MS = 3000L

        /**
         * Settle delay for a gesture that is definitively finished, like a lasso. Long
         * enough for the dry-layer repaint to land first, short enough to read as immediate.
         */
        const val PROMPT_SETTLE_MS = 60L

        /**
         * Shortest run of enclosed ink, in pixels of path, that counts as selected.
         * Below this it is the lasso's edge clipping a passing stroke, not a selection.
         */
        const val MIN_SELECT_SPAN_PX = 40.0

        /**
         * And a minimum number of samples in the run, as well as its length.
         *
         * Length alone was not enough: a fast pen covers 24 px in half a dozen samples, so
         * lassos were still picking up 6- and 15-point slivers — and at 12 points / 24 px a
         * 17-point graze still got through. Measured legitimate partial selections start
         * around 90 points, so 24 points over 40 px leaves plenty of room below that. Those drew a tiny selection
         * box somewhere unexpected, and because a pen-down inside a selection drags it, the
         * next lasso drawn near that box silently became a drag instead — which reads as the
         * tool having stopped working.
         */
        const val MIN_SELECT_POINTS = 24

        /**
         * Shortest surviving erase fragment kept, in points and path pixels — below this,
         * a fragment's wrong-width start (see the comment in erasePartial's flush()) is
         * most of what's visible, so it reads as a rendering glitch rather than kept ink.
         * Deliberately smaller than lasso's MIN_SELECT_POINTS/MIN_SELECT_SPAN_PX: those
         * filter an edge grazed in passing from a selection the user is actively drawing;
         * this filters a stub the eraser barely missed, which should still be able to
         * leave a genuinely short remaining piece of a stroke intact.
         */
        const val MIN_ERASE_FRAGMENT_POINTS = 6
        const val MIN_ERASE_FRAGMENT_SPAN_PX = 24.0
    }
}
