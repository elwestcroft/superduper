package com.superduper.notes.eink

import android.util.Log
import android.view.View

/**
 * Per-view waveform control (SPEC.md §4.5, Phase 2).
 *
 * The reason scrolling can feel responsive on e-ink at all: the panel has a fast binary
 * waveform (~20 ms) and a quality greyscale one (~160–190 ms). Both working e-ink
 * infinite-canvas apps drive a gesture entirely in the fast mode and settle to quality once
 * the finger stops — Notable formalises exactly this in its refresh arbiter.
 *
 * All values were read off this firmware rather than assumed: `A2=4`, `DU=1`, `GC=2`,
 * `AUTO=7`, gates `DEFAULT=216 / BLACKER=204 / WHITER=68`. Every method is guarded; on a
 * firmware without them the app simply renders at whatever waveform the display service
 * picks, which is what it did before.
 */
object EinkRefresh {

    private const val TAG = "SuperDuper"

    // android.view.View EINK_SHOW_MODE_* — probed on Chauvet.E103.2606141001.2389_release.
    const val MODE_DU = 1
    /**
     * GC16: the full-refresh waveform, which cycles the pixels through black and white.
     * This IS the flash. It is the right tool for a deliberate deep clean and the wrong one
     * for anything routine — using it for a regional "quality" repaint after every lasso
     * produced a black flash on every lasso, and the fact that it was regional only made the
     * flash smaller, not absent.
     */
    const val MODE_GC = 2
    /**
     * GL16: the quality waveform WITHOUT the black/white cycle. Repaints grey levels in
     * place, clearing DU residue with no flash. This is what a bounded region should be
     * repainted with after a fast-waveform gesture. (`EINK_SHOW_MODE_GL` in
     * `android.view.View`, alongside GLR=18 and GLD=19 variants.)
     */
    const val MODE_GL = 3
    const val MODE_A2 = 4
    const val MODE_AUTO = 7

    // EINK_DATA_MODE_*: these constants are hidden-API blocked, but the parameter is a
    // plain int. 1 = A2, 3 = default.
    const val DATA_A2 = 1
    const val DATA_DEFAULT = 3

    const val GATE_DEFAULT = 216

    /** Quiet period after the last gesture before returning to quality mode. */
    const val SETTLE_DELAY_MS = 500L

    /**
     * A forced full update is a visible flash, so it is rare: the quality repaint on every
     * settle handles ordinary residue, and this is for deep cleaning only.
     */
    const val FULL_REFRESH_EVERY = 25


    /** True between beginFast and the settle actually running — the panel is in DU. */
    var isFast: Boolean = false
        private set

    private var setMode2: java.lang.reflect.Method? = null
    private var resetMode: java.lang.reflect.Method? = null
    private var setGate: java.lang.reflect.Method? = null
    private var forceFull: java.lang.reflect.Method? = null
    private var resolved = false

    fun resolve() {
        if (resolved) return
        resolved = true
        val v = View::class.java
        fun m(name: String, vararg p: Class<*>) = runCatching { v.getMethod(name, *p) }.getOrNull()
        setMode2 = m("setEinkUpdateMode", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        resetMode = m("resetEinkUpdateMode")
        setGate = m("setEinkA2Gate", Int::class.javaPrimitiveType!!)
        forceFull = m("forceEinkFullUpdate", Boolean::class.javaPrimitiveType!!)
        Log.i(TAG, "REFRESH: setMode=${setMode2 != null} reset=${resetMode != null} " +
            "gate=${setGate != null} forceFull=${forceFull != null}")
    }

    fun forceFullUpdate(view: View) {
        Log.i(TAG, "REFRESHLOG: forceEinkFullUpdate (undo/redo sync)")
        // Every other reflective call in this file is guarded (see fullRefreshNow and the
        // settle Runnable below) — this one wasn't, so a firmware update renaming or
        // removing forceEinkFullUpdate would crash on every undo/redo instead of degrading
        // the way the rest of this reflective surface does (security audit, 2026-09-03).
        runCatching { forceFull?.invoke(view, false) }
            .onFailure { Log.w(TAG, "REFRESH: forceFullUpdate failed: ${it.cause ?: it}") }
    }

    val available: Boolean get() { resolve(); return setMode2 != null }

    /**
     * Fast binary waveform for the duration of a gesture. Ghosting accumulates, which is
     * why [settle] must follow — but while the finger is moving, speed is the only thing
     * that matters.
     */
    fun beginFast(view: View) {
        resolve()
        // DU, not A2. A2 is marginally faster but leaves faint copies of moving content
        // behind it — a visible trail of ghost lines while scrolling. DU is still a binary
        // black/white waveform, so it is fast, without the residue.
        // A new gesture cancels any pending quality refresh, so a run of quick swipes
        // stays in fast mode throughout instead of flashing between them.
        cancelSettle()
        isFast = true
        Log.i(TAG, "REFRESHLOG: beginFast (DU)")
        runCatching {
            // ORDER MATTERS. setEinkUpdateMode resets the view's A2 gate to 0 unless
            // dataMode == A2, so setting the gate first meant it was immediately
            // clobbered on every pen-down. Mode first, then gate.
            setMode2?.invoke(view, DATA_DEFAULT, MODE_DU)
            setGate?.invoke(view, GATE_DEFAULT)
        }.onFailure { Log.w(TAG, "REFRESH: beginFast failed: ${it.cause ?: it}") }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSettle: Runnable? = null

    /** Consecutive gestures without a settle, so ghosting still gets cleared eventually. */
    private var gesturesSinceFullRefresh = 0

    /**
     * Schedule the return to quality mode, debounced.
     *
     * Settling immediately on every gesture end meant a full-panel refresh — a visible
     * black flash — fired while the user was already starting the next swipe. Waiting for
     * the gesture to actually stop is what makes the flash land at rest instead of
     * mid-scroll. Notable uses the same 500 ms window for the same reason.
     */
    fun scheduleSettle(
        view: View,
        delayMs: Long = SETTLE_DELAY_MS,
        forceFullUpdate: Boolean = false,
        countTowardFullRefresh: Boolean = true,
        repaint: Boolean = true,
    ) {
        val hadPending = pendingSettle != null
        cancelSettle()
        Log.i(TAG, "REFRESHLOG: settle scheduled in ${delayMs}ms repaint=$repaint count=$countTowardFullRefresh forceFull=$forceFullUpdate${if (hadPending) " (replaced a pending settle)" else ""}")
        val r = Runnable {
            pendingSettle = null
            isFast = false
            Log.i(TAG, "REFRESHLOG: settle FIRED repaint=$repaint")
            resolve()
            runCatching {
                // Explicit branch, not an elvis: Method.invoke returns null for a void
                // method, so `reset ?: setMode` ran both — the reset was immediately
                // overridden by the "fallback".
                if (resetMode != null) resetMode?.invoke(view)
                else setMode2?.invoke(view, DATA_DEFAULT, MODE_AUTO)
                // Repaint at quality WITHOUT a full-panel flash.
                //
                // Two wrong answers preceded this. Rationing forceEinkFullUpdate to one
                // settle in four left visible ghost trails; running it every settle
                // brought back the black flash. Both were treating the flash as the only
                // way to clean up.
                //
                // Resetting the waveform and invalidating repaints the whole surface
                // through the quality path, which overwrites the DU residue without the
                // black/white flash cycle a forced full update performs. The flash is now
                // reserved for the manual refresh control and for occasional deep
                // cleaning, where the user has asked for it or it is genuinely due.
                // [repaint]=false is for a gesture that has already repainted the only
                // region it dirtied, at quality (the lasso and selection moves do, via
                // refreshRegionAtQuality). For those the settle's job is just to put the
                // waveform mode back; the whole-view invalidate that normally follows the
                // reset repaints 1404x1760 through the quality path for no reason, and on
                // this panel that is the black flash the user sees on every lasso.
                if (repaint) view.invalidate()
                // [forceFullUpdate] is for a gesture whose own graphics were drawn by the
                // engine straight to the panel — a lasso outline, above all. Those pixels
                // arrive through the fast waveform and leave residue that a mode reset and
                // repaint do not clear, so the caller has to be able to demand the full
                // update rather than wait for its turn in the ration.
                // The ration exists to clean up accumulated DU residue from pen strokes.
                // A gesture that has already cleaned its own region at quality (the lasso
                // and selection moves do, via refreshRegionAtQuality) has nothing to add to
                // that debt, and letting it trip the 1-in-25 full update meant a black flash
                // landing mid-selection — the worst possible moment for one.
                if (countTowardFullRefresh) gesturesSinceFullRefresh++
                if (forceFullUpdate || gesturesSinceFullRefresh >= FULL_REFRESH_EVERY) {
                    gesturesSinceFullRefresh = 0
                    forceFull?.invoke(view, false)
                }
                if (repaint) view.invalidate()
            }.onFailure { Log.w(TAG, "REFRESH: settle failed: ${it.cause ?: it}") }
        }
        pendingSettle = r
        handler.postDelayed(r, delayMs)
    }

    fun cancelSettle() {
        pendingSettle?.let { handler.removeCallbacks(it); Log.i(TAG, "REFRESHLOG: settle CANCELLED before firing") }
        pendingSettle = null
    }

    /** Explicit user-facing clean-up, e.g. a "refresh screen" button. */
    fun fullRefreshNow(view: View) {
        Log.i(TAG, "REFRESHLOG: forceEinkFullUpdate (manual)")
        resolve()
        gesturesSinceFullRefresh = 0
        runCatching { forceFull?.invoke(view, false) }
    }
}
