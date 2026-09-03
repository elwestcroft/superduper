package com.superduper.notes.eink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.superduper.notes.doc.PenStyle
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Milestone-0 experiment: let the FIRMWARE's own pen-write engine draw the ink
 * (SPEC.md §3.5.3).
 *
 * How this is reachable at all — established by decompiling this device's framework:
 *
 *  - `android.view.View.getPWInterFace()` (note the capital F) is hiddenapi-flagged
 *    `sdk`, i.e. callable by an unprivileged app. It lazily does
 *    `VriFactory.createViewPWInterface(this)`, which reflectively constructs
 *    `htfyun.penwrite.ctrl.PWCoreCtrl(View)` — *inside* the framework — and hands back an
 *    `android.view.EinkPWInterface`. That indirection is the whole trick: `PWCoreCtrl`'s
 *    constructor is BLOCKED to us, but the framework may call it.
 *  - Once obtained, the engine self-bootstraps. `View.dispatchAttachedToWindow()` calls
 *    `onHostViewAttachedToWindow()`, which registers this View with the window's
 *    `GlobalVirtualView` and spins up `PWInputThread` at priority -15.
 *  - When it activates, `ViewRootImpl.setPWWritingPolicy(1)` **dups the window's
 *    InputChannel and disposes the app's normal input receiver**, so pen samples are read
 *    on the PW thread first, rasterized by the vendor's own pen algorithms into a
 *    process-local bitmap, and pushed to the panel via `nativeAddPWRect` in
 *    libeinkviewjni. Unconsumed events are forwarded back to the UI thread.
 *
 * Consequences for us: we do NOT draw the wet stroke, and we should not expect our
 * onTouchEvent to see pen input while PW is active. We receive finished strokes through
 * the draw-event listener and persist them from there.
 *
 * MUST be a plain View, not a SurfaceView: `PWCoreCtrl`'s constructor calls
 * `hostView.getOverlay().add(drawable)`.
 *
 * The one genuine unknown is whether an `untrusted_app` process can initialise the native
 * layer (`libeinkviewjni`) — SELinux is enforcing and the EBC path is vendor-labelled. So
 * this class logs aggressively and reports [pwActive]; MainActivity falls back to the
 * software renderer when it comes back false.
 */
/** Breathing room between selected ink and its dashed selection box, in px. */
internal const val SELECTION_PAD = 14

class NativeInkView(context: Context) : View(context) {

    private companion object {
        const val TAG = "SuperDuper"

        // android.view.EinkPWInterface pen/tool constants (values decompiled from the
        // framework; PENTYPE 2 is the firmware's own default "pen").
        const val PEN_TYPE_DEFAULT = 2
        const val DRAWOBJ_FREEHAND = 0

        /** enableTouchDispatch mode: route touch through the host with low latency. */
        const val DISPATCH_TOUCH_HOST = 2

        /** Engine drops the gesture entirely; normal dispatch still reaches onTouchEvent. */
        const val DISPATCH_TOUCH_ALL_DROP = 4

        /** `DRAW_OBJ_CHOICEERASE` — the engine's dashed selection loop, drawn to its float layer. */
        const val LASSO_OBJ_TYPE = 1



        /** A .tch containing only its 8-byte header holds no strokes. */
        const val TCH_HEADER_BYTES = 8L

        /** Minimum vertical travel before a finger drag counts as a scroll. */
        const val SWIPE_DEADZONE_PX = 40f

        /**
         * Minimum movement between scroll updates. Small enough that content tracks the
         * finger, large enough that the panel is not asked to repaint 60 times a second.
         */
        const val SCROLL_QUANTUM_PX = 24f

        /**
         * How far the pen must travel before the eraser runs again. Small enough that ink
         * disappears under the pen, large enough that each pass has time to finish.
         */
        const val ERASE_STEP_PX = 12f

        /** Verbose gesture diagnostics. Left on until erase behaviour is confirmed. */
        const val DIAG = false
    }

    /** The live android.view.EinkPWInterface, or null if the engine did not open. */
    private var pw: Any? = null

    /** True once setPWEnabled(true) has been accepted. */
    var pwActive: Boolean = false
        private set

    private var strokeCount = 0

    init {
        setBackgroundColor(Color.WHITE)
        isFocusable = true
    }

    /**
     * Desired height in pixels. The engine allocates its writing bitmap from the host
     * view's measured size (`PWCoreCtrl.onWindowChanged` → `mPWBitmapW/H`), so a tall view
     * yields a tall ink buffer — that is what makes a multi-screen scrolling segment work.
     * The vendor flags views taller than 4800 px, so callers must stay under that.
     */
    var desiredHeightPx: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (desiredHeightPx > 0) {
            setMeasuredDimension(
                getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), desiredHeightPx
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Open the engine only once we have a real size. Doing this in onAttachedToWindow
        // (as this class originally did) races layout: the engine would size its writing
        // bitmap from a 0x0 view.
        if (w > 0 && h > 0) {
            Log.i(TAG, "PW: host view sized ${w}x$h (${"%.2f".format(h / 1872f)} screens)")
            tryOpenEngine()
        }
    }

    override fun onDetachedFromWindow() {
        try {
            if (pwActive) invoke(pw, "setPWEnabled", arrayOf(Boolean::class.javaPrimitiveType!!), false)
        } catch (_: Throwable) {
        }
        pwActive = false
        super.onDetachedFromWindow()
    }

    private fun tryOpenEngine() {
        if (pw != null) return

        // 1. The one sanctioned door.
        pw = try {
            val m = View::class.java.getMethod("getPWInterFace")
            m.invoke(this).also {
                if (it == null) Log.w(TAG, "PW: getPWInterFace() returned null")
                else Log.i(TAG, "PW: engine obtained -> ${it.javaClass.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "PW: getPWInterFace() unavailable: $t")
            null
        }
        val engine = pw ?: return

        // 2. Configure the pen. Anti-alias off: on a 300 PPI 16-grey panel the firmware's
        //    own default is 0, and 1-bit edges refresh with a fast waveform (SPEC.md §4.4).
        cfg(engine, "setPenType", arrayOf(Int::class.javaPrimitiveType!!), PEN_TYPE_DEFAULT)
        cfg(engine, "setDrawObjectType", arrayOf(Int::class.javaPrimitiveType!!), DRAWOBJ_FREEHAND)
        // penWidth, not a literal: re-attaching the engine must not silently reset the pen
        // to the default and leave the dry layer re-rendering at the width the user chose.
        cfg(engine, "setPenStdWidth", arrayOf(Float::class.javaPrimitiveType!!), penWidth)
        cfg(engine, "setPenColor", arrayOf(Int::class.javaPrimitiveType!!), Color.BLACK)
        cfg(engine, "setDrawObjectPaintAntiAlias", arrayOf(Int::class.javaPrimitiveType!!), 0)
        cfg(engine, "setFingerWritable", arrayOf(Boolean::class.javaPrimitiveType!!), false)
        // Kill the "blossom" erase cursor. The engine draws a dwell indicator into its
        // float layer after ~200 ms (PWDrawObjErase, gated by mFlyingEraseEnable); on
        // e-ink that indicator ghosts badly, leaving remnants of itself behind that only
        // clear when you scribble over the area. We do not want a cursor on a panel that
        // cannot repaint it cheaply.
        cfg(engine, "disablePenEraseFloatFlower", arrayOf(Boolean::class.javaPrimitiveType!!), true)
        cfg(engine, "enableTouchDispatch", arrayOf(Int::class.javaPrimitiveType!!), DISPATCH_TOUCH_HOST)

        // 3. Listen for finished strokes — this is how we will persist ink later.
        installDrawListener(engine)

        // 4. Go.
        pwActive = cfg(engine, "setPWEnabled", arrayOf(Boolean::class.javaPrimitiveType!!), true)
        Log.i(TAG, "PW: setPWEnabled(true) accepted=$pwActive")

        // 5. Report what the engine thinks its state is — non-defaults prove it is live.
        for (g in arrayOf("getPenType", "getPenStdWidth", "getAvailableUndo", "isCurrentWriting")) {
            try {
                Log.i(TAG, "PW: $g() = ${engine.javaClass.getMethod(g).invoke(engine)}")
            } catch (t: Throwable) {
                Log.w(TAG, "PW: $g() failed: $t")
            }
        }
    }

    /**
     * Registers a dynamic proxy for android.view.EinkPWInterface$PWDrawEventWithPoint so we
     * are told when the engine finishes a stroke. The interface itself is `sdk`-flagged and
     * loadable, so Proxy can implement it.
     */
    private fun installDrawListener(engine: Any) {
        try {
            val iface = Class.forName("android.view.EinkPWInterface\$PWDrawEventWithPoint")
            val handler = InvocationHandler { _, method: Method, args: Array<Any?>? ->
                when (method.name) {
                    "onTouchDrawStart" -> Log.i(TAG, "PW: strokeStart")
                    "onTouchDrawEnd" -> {
                        strokeCount++
                        val bmp = args?.getOrNull(0) as? Bitmap
                        val r = args?.getOrNull(1) as? Rect
                        // This runs on PWInputThread0 at priority -15. Hop to the UI
                        // thread before touching anything the app owns, and never do work
                        // here that could delay the ink path.
                        if (bmp != null && r != null && !bmp.isRecycled) {
                            val copy = Rect(r)
                            post { onEngineStrokeRendered(bmp, copy) }
                        }
                    }
                    "onOneWordDone" -> Log.i(TAG, "PW: oneWordDone")
                    "toString" -> return@InvocationHandler "PWDrawListener"
                    "hashCode" -> return@InvocationHandler System.identityHashCode(this)
                    "equals" -> return@InvocationHandler false
                }
                null
            }
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler)
            engine.javaClass.getMethod("setDrawEventListener", iface).invoke(engine, proxy)
            Log.i(TAG, "PW: draw listener installed")
        } catch (t: Throwable) {
            Log.w(TAG, "PW: draw listener failed: $t")
        }
    }

    // --- Test harness (Milestone 0) -----------------------------------------------
    // Driven over adb so eraser semantics can be tested reproducibly:
    //   adb shell am broadcast -a com.superduper.notes.TOOL --es tool eraser
    // Draw-object types decompiled from PWDrawObjectAlgo: 0=freehand pen, 1=lasso-erase,
    // 2=tap-erase, 4=lasso-move, 5=erase.

    /** Switch the engine's active tool. Returns true if the engine accepted it. */
    fun setTool(drawObjType: Int): Boolean {
        val ok = cfg(
            pw ?: return false, "setDrawObjectType",
            arrayOf(Int::class.javaPrimitiveType!!), drawObjType
        )
        Log.i(TAG, "PW: tool -> $drawObjType accepted=$ok undoDepth=${availableUndo()}")
        return ok
    }

    /** Allow finger input to draw, so adb-synthesised swipes can drive the engine. */
    fun setFingerWritable(enabled: Boolean): Boolean {
        val engine = pw ?: return false
        return cfg(engine, "setFingerWritable", arrayOf(Boolean::class.javaPrimitiveType!!), enabled)
    }

    /** Wipe the engine's buffer between test runs. */
    fun clearAll() {
        invoke(pw, "clearContentX", arrayOf(Boolean::class.javaPrimitiveType!!), true)
        Log.i(TAG, "PW: clearContentX -> undoDepth=${availableUndo()}")
    }

    /**
     * Engine undo depth. Watching how this moves across an erase is itself evidence:
     * if an erase pushes an undo entry, the engine records erases as operations rather
     * than merely painting over pixels.
     */
    fun availableUndo(): Int = invoke(pw, "getAvailableUndo", emptyArray()) as? Int ?: -1

    /**
     * Fence a screen region so the pen does not ink there — used for the toolbar strip,
     * which is a sibling view the engine would otherwise happily draw over.
     * Coordinates are in this host view's space.
     */
    fun fenceRect(rect: android.graphics.Rect): Boolean {
        val engine = pw ?: return false
        return cfg(engine, "addUnWriteRect", arrayOf(android.graphics.Rect::class.java), rect)
    }

    fun clearFences(): Boolean {
        val engine = pw ?: return false
        return cfg(engine, "clearUnWriteRectList", emptyArray())
    }

    /** Redo via the firmware's history stack. */
    fun redo(): Boolean {
        val engine = pw ?: return false
        val before = invoke(engine, "getAvailableRedo", emptyArray())
        val r = invoke(engine, "reDo", emptyArray())
        Log.i(TAG, "PW: reDo -> $r  redoAvail $before -> ${invoke(engine, "getAvailableRedo", emptyArray())}")
        return r as? Boolean ?: false
    }

    /**
     * Undo via the firmware's own history stack.
     *
     * The real signature is `unDo(boolean rmUndoList)`; an earlier no-arg lookup silently
     * threw NoSuchMethodException and made undo look like a no-op. Try the documented
     * arity first, then fall back, and log the outcome so a failure is never silent again.
     */
    fun undo(): Boolean {
        val engine = pw ?: return false
        val before = availableUndo()
        val result = try {
            engine.javaClass
                .getMethod("unDo", Boolean::class.javaPrimitiveType!!)
                .invoke(engine, true)
        } catch (_: NoSuchMethodException) {
            try {
                engine.javaClass.getMethod("unDo").invoke(engine)
            } catch (t: Throwable) {
                Log.w(TAG, "PW: unDo unavailable: ${t.cause ?: t}"); null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "PW: unDo failed: ${t.cause ?: t}"); null
        }
        Log.i(TAG, "PW: unDo -> $result  undoDepth $before -> ${availableUndo()}")
        return result as? Boolean ?: false
    }

    /**
     * Bind the engine to an explicit page file. Must be a `.tch` path: a bare directory
     * makes the engine invent timestamped names, and a `.png` path saves raster only —
     * losing the vector model we depend on.
     */
    fun setPagePath(path: String, onReady: (() -> Unit)? = null): Boolean {
        val engine = pw ?: return false
        java.io.File(path).parentFile?.mkdirs()
        val bytes = java.io.File(path).let { if (it.exists()) it.length() else 0L }
        // Page-swap latency IS scroll latency in the paged architecture, so measure both
        // the synchronous call and the time until ink is actually back on screen.
        val t0 = android.os.SystemClock.uptimeMillis()
        val ok = cfg(engine, "setLoadFilePath", arrayOf(String::class.java), path)
        val tCall = android.os.SystemClock.uptimeMillis() - t0
        Log.i(TAG, "PAGE: setLoadFilePath($path) ok=$ok bytes=$bytes callMs=$tCall")
        // Poll for the model to repopulate — the load runs on a background thread.
        val start = android.os.SystemClock.uptimeMillis()
        postDelayed(object : Runnable {
            override fun run() {
                val n = availableUndo()
                val el = android.os.SystemClock.uptimeMillis() - start
                // An empty page legitimately has 0 steps, so stop waiting once the file
                // itself is empty rather than polling until the timeout.
                val done = n > 0 || bytes <= TCH_HEADER_BYTES
                if (done) {
                    Log.i(TAG, "PAGE: model ready after ${el}ms (steps=$n, ${bytes}B)")
                    onReady?.invoke()
                } else if (el < 4000) {
                    postDelayed(this, 20)
                } else {
                    Log.w(TAG, "PAGE: model still empty after ${el}ms")
                    onReady?.invoke()
                }
            }
        }, 20)
        return ok
    }

    fun pagePath(): String? = invoke(pw, "getPWBitmapFilePath", emptyArray()) as? String

    /**
     * Paint [bmp] straight into the engine's writing layer.
     *
     * `setPWBitmap` composites on the calling thread and invalidates the host itself, with
     * no dependence on `mPWHandler` — which is what makes it usable on a live engine where
     * `repaintStep` cannot run. `recycle=false` because the caller reuses one scratch
     * bitmap across scrolls.
     *
     * Note these pixels carry no entries in the engine's stroke model; the model comes
     * separately from the `.tch` we load alongside. Keeping the two in step is the whole
     * contract of the scrolling design.
     */
    fun pushBitmap(bmp: android.graphics.Bitmap): Boolean {
        val engine = pw ?: return false
        return try {
            engine.javaClass.getMethod(
                "setPWBitmap", android.graphics.Bitmap::class.java,
                android.graphics.Rect::class.java, android.graphics.Rect::class.java,
                Boolean::class.javaPrimitiveType!!
            ).invoke(engine, bmp, null, null, false)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "PW: setPWBitmap failed: ${t.cause ?: t}"); false
        }
    }

    /** Warm a neighbouring page so switching to it costs no disk I/O. */
    fun preloadPage(path: String): Boolean {
        val engine = pw ?: return false
        return cfg(engine, "setPreloadFilePath", arrayOf(String::class.java), path)
    }

    /** Force a full synchronous save (incremental auto-save holds back the newest 50 strokes). */
    fun savePage(timeoutMs: Long = 2000): Any? {
        val engine = pw ?: return null
        val r = invoke(engine, "saveBitmapAndWaitDone", arrayOf(Long::class.javaPrimitiveType!!), timeoutMs)
        Log.i(TAG, "PAGE: saveBitmapAndWaitDone($timeoutMs) -> $r  path=${pagePath()}  undo=${availableUndo()}")
        return r
    }

    /**
     * Dump the engine's OWN stroke model.
     *
     * `EinkPWInterface.getStepPointArray(step)` is reported to return
     * `ArrayList<PWInputPoint>` — real per-point geometry (x, y, action, radius,
     * pressure) — with `getAvailableUndo()` / `getAvailableRepaintStep()` giving the
     * stroke count. If that holds, the firmware keeps a vector model we can read, and we
     * do not have to infer geometry from pixels.
     *
     * Its hidden-API status could not be established from the decompilation (no flags
     * file), so this probe exists to settle it at runtime.
     */
    fun dumpStrokes() {
        val engine = pw ?: return
        val undo = availableUndo()
        val repaint = invoke(engine, "getAvailableRepaintStep", emptyArray())
        val valid = invoke(engine, "getValidStepCount", emptyArray())
        Log.i(TAG, "DUMP: availableUndo=$undo repaintStep=$repaint validStep=$valid")

        for (step in 0 until minOf(if (undo > 0) undo else 0, 4)) {
            val arr = invoke(
                engine, "getStepPointArray", arrayOf(Int::class.javaPrimitiveType!!), step
            )
            if (arr == null) {
                Log.w(TAG, "DUMP: step $step -> null (blocked or empty)")
                continue
            }
            val list = arr as? java.util.ArrayList<*>
            if (list == null) {
                Log.i(TAG, "DUMP: step $step -> ${arr.javaClass.name} (unexpected type)")
                continue
            }
            val n = list.size
            val sample = StringBuilder()
            for (i in listOf(0, n / 2, n - 1).distinct().filter { it in 0 until n }) {
                val pt = list[i] ?: continue
                sample.append(" [$i]").append(describePoint(pt))
            }
            Log.i(TAG, "DUMP: step $step -> ${n} points, class=${list.firstOrNull()?.javaClass?.name}$sample")
        }
    }

    /**
     * Log the engine's full public API. Cheaper and more reliable than guessing method
     * names from a decompilation, and it settles which repaint entry points exist.
     */
    fun dumpMethods(filter: String?) {
        val engine = pw ?: return
        val names = engine.javaClass.methods
            .map { m -> m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")" }
            .filter { filter == null || it.contains(filter, ignoreCase = true) }
            .distinct()
            .sorted()
        Log.i(TAG, "METHODS(${filter ?: "all"}): ${names.size}")
        names.chunked(6).forEach { Log.i(TAG, "  " + it.joinToString("  ")) }
    }

    /**
     * Repaint the engine's ink from its retained stroke model.
     *
     * Real signature (decompiled): `repaintStep(int speedType, int startStep, int endStep,
     * int maxDelay, RepaintListener)`. `endStep = -1` means "through the last step"; the
     * firmware's own save-PNG path calls `repaintStep(3, 0, -1, 0, listener)`.
     *
     * This is the call that makes erase/undo visible — and it is also the mechanism the
     * infinite canvas will need to redraw ink after scrolling (SPEC.md §5).
     */
    fun forceRepaint() {
        val engine = pw ?: return
        // Deliberately not gated on isRepainting(): after a page swap it reports true and
        // stays true, so honouring it means the repaint never happens at all. stopRepaint
        // first, then drive a fresh one.
        if (invoke(engine, "isRepainting", emptyArray()) == true) {
            Log.i(TAG, "REPAINT: engine claims busy — stopping it first")
            invoke(engine, "stopRepaint", emptyArray())
        }
        val listenerClass = try {
            Class.forName("android.view.EinkPWInterface\$RepaintListener")
        } catch (t: Throwable) {
            Log.w(TAG, "REPAINT: RepaintListener class missing: $t"); null
        }
        val listener = listenerClass?.let { cls ->
            Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { _, method, _ ->
                when (method.name) {
                    "toString" -> "RepaintListener"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> false
                    else -> { Log.i(TAG, "REPAINT: callback ${method.name}"); null }
                }
            }
        }
        try {
            val m = engine.javaClass.getMethod(
                "repaintStep",
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                listenerClass
            )
            val r = m.invoke(engine, 3, 0, -1, 0, listener)
            Log.i(TAG, "REPAINT: repaintStep(3,0,-1,0,listener) -> $r  steps=${availableUndo()}")
        } catch (t: Throwable) {
            Log.w(TAG, "REPAINT: repaintStep failed: ${t.cause ?: t}")
        }
    }

    /** Reads x/y/pressure off a PWInputPoint by reflection, tolerating name differences. */
    private fun describePoint(pt: Any): String {
        fun num(vararg names: String): String {
            for (nm in names) {
                try {
                    return "%.2f".format((pt.javaClass.getMethod(nm).invoke(pt) as Number).toFloat())
                } catch (_: Throwable) {
                }
                try {
                    return "%.2f".format((pt.javaClass.getField(nm).get(pt) as Number).toFloat())
                } catch (_: Throwable) {
                }
            }
            return "?"
        }
        return "(x=${num("getX", "x")},y=${num("getY", "y")},p=${num("getPress", "getPressure", "press")})"
    }

    /** The engine's accumulated ink, for persistence. */
    fun inkBitmap(): Bitmap? = invoke(pw, "getPureWriteBitmap", emptyArray()) as? Bitmap

    private fun cfg(target: Any, name: String, params: Array<Class<*>>, vararg args: Any?): Boolean =
        try {
            target.javaClass.getMethod(name, *params).invoke(target, *args)
            Log.i(TAG, "PW: $name(${args.joinToString()}) ok")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "PW: $name failed: ${t.cause ?: t}")
            false
        }

    private fun invoke(target: Any?, name: String, params: Array<Class<*>>, vararg args: Any?): Any? {
        val t = target ?: return null
        return try {
            t.javaClass.getMethod(name, *params).invoke(t, *args)
        } catch (e: Throwable) {
            Log.w(TAG, "PW: $name failed: ${e.cause ?: e}"); null
        }
    }

    // --- Geometry-capture experiment (SPEC.md §6 open question) -------------------
    // The engine owns the wet stroke and hands back a BITMAP, but the infinite canvas
    // needs vector geometry for tiling, erase, lasso and persistence. Question: does the
    // UI thread still receive the pen MotionEvents (the engine forwards what it does not
    // consume), so we can capture the polyline for free alongside the engine's drawing?
    private var uiMoveEvents = 0
    private var uiSamples = 0
    private var uiStrokeActive = false

    // Pressure telemetry. The vector model (SPEC.md §6.1) stores pressure per point, so
    // before building on this stream we must know whether the forwarded events carry real
    // pressure or a constant. A digitizer reporting a fixed 1.0 for every sample means the
    // axis is absent, not that the user pressed evenly.
    /**
     * Points of the stroke in progress, in view coordinates, as (x, y, pressure).
     *
     * The engine draws the wet stroke, but the app owns the document — so the app needs
     * the geometry too. It arrives free: the framework forwards pen events to the UI
     * thread whether we read them or not (verified 15/15 strokes at ~480 Hz with real
     * pressure), on a thread outside the ink path.
     */
    private val strokePoints = ArrayList<FloatArray>(1024)

    /** Called on pen-up with the finished stroke's points in view coordinates. */
    var onStrokeFinished: (List<FloatArray>) -> Unit = {}

    /**
     * The engine's own rendering of the finished stroke: its writing bitmap plus the rect
     * the stroke occupies, delivered on the UI thread.
     *
     * Re-rasterising the stroke ourselves made it visibly change weight the instant the pen
     * lifted — thin while drawing, thicker once committed — because our pressure curve is
     * an approximation of Ratta's. Compositing their pixels instead makes the handoff
     * exact. Both Boox infinite-canvas apps do the same thing for the same reason.
     */
    var onEngineStrokeRendered: (android.graphics.Bitmap, Rect) -> Unit = { _, _ -> }

    /**
     * Draw the in-progress erase or lasso path.
     *
     * The engine draws nothing useful in these modes — its own buffer is empty — so without
     * this, circling something is invisible until the pen lifts and the gesture feels like
     * it stalled. A dashed trail shows the region being outlined as it is drawn.
     */
    /**
     * What the pen currently does.
     *
     * One mode rather than a set of booleans. Previously `erasing`, `showGesturePath` and
     * `streamErase` were set independently and the engine's enabled state was tied to just
     * one of them — so the engine stayed on during erase, painting its own cursor over our
     * trail, while the trail only became visible after the gesture finished.
     *
     * The engine is enabled **only** for PEN. In every other mode it contributes nothing
     * (its buffer is cleared after each stroke) and its overlay composites above our canvas,
     * so leaving it on can only interfere.
     */
    enum class PenMode { PEN, ERASE_SPOT, ERASE_AREA, LASSO }

    var penMode: PenMode = PenMode.PEN
        set(value) {
            if (field == value) return
            field = value
            val outline = value == PenMode.ERASE_AREA || value == PenMode.LASSO
            // Engine on for PEN and for the outline modes; off only for spot erase, where a
            // visible line under the eraser would be wrong.
            setEngineDispatch(value == PenMode.PEN || outline, value != PenMode.PEN)
            // In the outline modes the engine must use a PEN nib, not the tool's own
            // draw-object type — an eraser nib would draw nothing to see. Returning to the
            // pen restores the chosen nib, not a hardcoded 0, or picking brush then
            // lassoing then drawing again would silently give you the plain pen back.
            // LASSO uses objType 1 (DRAW_OBJ_CHOICEERASE), whose handler draws a dashed
            // 3 px loop into the engine's FLOAT bitmap and touches the writing bitmap only
            // with a single CLEAR fill at pen-up — verified by reading
            // PWDrawObjChoiceErase:60,68,87-92,114-116. That is the engine's own marching
            // ants at pen speed, instead of the freehand nib's solid ink.
            //
            // This value was already declared on InkToolbar.Tool.LASSO and already applied
            // by MainActivity; the line below used to overwrite it with the pen nib, which
            // is the entire reason the outline looked like ink.
            // Both outline tools use objType 1 now: proven on the lasso first, then extended
            // to area erase, whose solid pen-ink loop read as ink and threw the user off.
            if (outline) setTool(LASSO_OBJ_TYPE)
            else if (value == PenMode.PEN) setTool(penStyle.objType)
            if (value != PenMode.PEN) resolveOverlay()
            gesturePath.rewind()
            invalidate()
            // Arm the fast waveform on tool selection, not on pen-down. Switching at
            // pen-down meant a quality refresh was already in flight and blocked the DU
            // updates until it finished — which is why the trail only caught up midway
            // through the gesture.
            onGestureInkPace(value != PenMode.PEN)
            Log.i(TAG, "UI: mode -> $value (engine ${if (value == PenMode.PEN || outline) "draws" else "off"})")
        }

    private val showGesturePath: Boolean
        get() = penMode == PenMode.ERASE_AREA || penMode == PenMode.LASSO

    private val streamErase: Boolean get() = penMode == PenMode.ERASE_SPOT

    private var drawCount = 0
    private var lastLoggedDrawCount = -1
    private var diagMoves = 0

    private var lastErasedX = 0f
    private var lastErasedY = 0f

    private fun movedSince(ax: Float, ay: Float, bx: Float, by: Float, min: Float): Boolean {
        val dx = bx - ax; val dy = by - ay
        return dx * dx + dy * dy >= min * min
    }


    /** Emitted during an erase drag so erasing can keep up with the pen. */
    var onEraseProgress: (List<FloatArray>) -> Unit = {}

    /**
     * A spot-erase gesture was cancelled by the framework after live batches had already
     * changed the document. The document must close its undo entry for what was erased —
     * otherwise those edits persist with nothing to undo them.
     */
    var onEraseCancelled: () -> Unit = {}

    /**
     * Index of the last sample already handed to the eraser, so the next batch starts
     * there. Batches used to start at this MotionEvent's first sample, so an event that
     * did not cross ERASE_STEP_PX contributed nothing to any live batch and ink under that
     * sub-path was only caught at pen-up (plan 3.2).
     */
    private var lastErasedIndex = 0

    private val gesturePath = Path()
    private val gesturePaint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    /**
     * Whether the current tool erases rather than inks.
     *
     * The engine still receives the gesture and runs its own eraser, but its buffer is
     * empty (we clear it after every stroke), so that is a harmless no-op. The erase that
     * matters happens against the app's model, driven by the same captured geometry.
     */
    val erasing: Boolean
        get() = penMode == PenMode.ERASE_SPOT || penMode == PenMode.ERASE_AREA

    /**
     * Ink weight — the engine's `stdWidth`, and the same value [TchRaster] re-renders with.
     *
     * It has to set both. This is the single input to the firmware's width formula, so if
     * the engine is told one number and the dry-layer renderer another, wet ink and
     * re-drawn ink disagree by construction — which is the exact defect the ported width
     * math exists to prevent. This is also the hook the user-facing thickness control will
     * use, so the two must not be allowed to drift apart there either.
     *
     * 6.2 is the shipped default, chosen by drawing at each of 2.5 / 4.4 / 6.2 on the
     * device. Lower values are not wrong, just thinner: the firmware quantises width to
     * whole pixels, so 2.5 collapses to a uniform 1 px hairline with no pressure response
     * at all, 4.4 spans 1-2 px, and 6.2 gives ~2 px with visible variation (SPEC.md §6.3a).
     */
    /**
     * Which of the engine's nibs the PEN tool draws with.
     *
     * Separate axis from [penMode]: the mode says pen-vs-eraser-vs-lasso, this says which
     * nib the pen is. Setting it pushes `setDrawObjectType` so the engine's own wet stroke
     * changes, and it is recorded on every stroke so [com.superduper.notes.doc.TchRaster]
     * re-renders that stroke with the same equation the engine used for it.
     *
     * Only applied while the PEN tool is active — the outline modes deliberately force a
     * pen nib (see [penMode]) because an eraser nib draws nothing visible.
     */
    var penStyle: PenStyle = PenStyle.PEN
        set(value) {
            if (field == value) return
            field = value
            if (penMode == PenMode.PEN) setTool(value.objType)
            Log.i(TAG, "UI: pen style -> $value (objType=${value.objType})")
        }

    var penWidth: Float = 6.2f
        set(value) {
            field = value
            pw?.let { cfg(it, "setPenStdWidth", arrayOf(Float::class.javaPrimitiveType!!), value) }
        }

    /** Painted beneath the engine's wet ink; the document's committed strokes. */
    var dryLayer: com.superduper.notes.canvas.DryLayer? = null

    // Sample clock, measured per stroke from MotionEvent timestamps — the ground truth for
    // TchRaster.SAMPLE_PERIOD_MS, which otherwise has to assume a rate.
    private var strokeDownTime = 0L
    private var strokeUpTime = 0L
    private var strokeSamples = 0

    private var pMin = Float.MAX_VALUE
    private var pMax = -Float.MAX_VALUE
    private var pSum = 0.0
    private var pCount = 0
    private val pDistinct = HashSet<Int>()

    private fun notePressure(p: Float) {
        if (p < pMin) pMin = p
        if (p > pMax) pMax = p
        pSum += p.toDouble()
        pCount++
        // Bucket to 3 decimals: enough to tell a real analogue axis from a constant.
        pDistinct.add((p * 1000f).toInt())
    }

    /**
     * Finger swipe → scroll by the distance actually swiped, in pixels.
     *
     * Positive means the content should move up (scroll down the document). Reporting the
     * real distance rather than a direction lets a short flick nudge the page and a long
     * drag travel — a fixed step ignores how far you moved, which reads as a page turn.
     */
    var onSwipe: (dyPixels: Int) -> Unit = {}

    /**
     * Bounds of the current lasso selection, or null. Supplied by the document.
     *
     * A pen-down inside these bounds drags the selection instead of starting a new stroke,
     * which is what makes a selection feel like an object you can pick up.
     */
    /**
     * The lasso selection box, drawn as an overlay in [onDraw] — never into the dry layer.
     *
     * This is what lets selecting and deselecting leave the document's pixels alone. When
     * the box was painted into the dry bitmap, showing or clearing it meant repainting the
     * whole layer through TchRaster, which swapped the engine's own pixels for ours on every
     * visible stroke — so drawing a lasso visibly changed the weight of ink that had nothing
     * to do with it, and the full-view invalidate flashed the panel. As an overlay it costs
     * one small regional invalidate and touches no ink.
     *
     * View coordinates. Null when nothing is selected.
     */
    private var selectionBoxField: Rect? = null
    var selectionBox: Rect?
        get() = selectionBoxField
        set(value) = setSelectionBox(value, EinkRefresh.MODE_GL)

    /**
     * Move the box, repainting the affected region with [mode]'s waveform. Always GL today:
     * a live box redrawn in DU during a drag was tried and removed, because any repaint we
     * trigger mid-gesture makes the engine re-composite the host with no host bitmap
     * prepared, blanking the ink (see the drag-start comment in onTouchEvent).
     */
    private fun setSelectionBox(value: Rect?, mode: Int) {
        val old = selectionBox
        // Assign through the backing field, not the setter, or this recurses.
        selectionBoxField = value
        // Invalidate the union of old and new, padded for the dash stroke, so the previous
        // box is erased and the new one drawn in one regional repaint.
        val dirty = Rect()
        old?.let { dirty.union(padded(it)) }
        value?.let { dirty.union(padded(it)) }
        // Whole-view repaint through SurfaceFlinger, on purpose. This used to be a regional
        // eink invalidate (invalidate(Rect, GL)); see refreshRegionAtQuality for why not.
        Log.i(TAG, "REFRESHLOG: selectionBox $old -> $value (plain invalidate)")
        invalidate()
    }

    private fun padded(r: Rect) = Rect(
        r.left - SELECTION_PAD - 4, r.top - SELECTION_PAD - 4,
        r.right + SELECTION_PAD + 4, r.bottom + SELECTION_PAD + 4,
    )

    private val selectionPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }

    /** True when a view-space point lands on the current selection's ink. */
    var selectionHit: (Float, Float) -> Boolean = { _, _ -> false }

    /**
     * Switches the panel to a fast waveform for the duration of a non-pen gesture.
     *
     * The engine tags its own dirty rects FASTPW, so while it is enabled ink appears
     * immediately. Turning it off for erase and lasso removed that, and repaints fell back
     * to the quality waveform — hundreds of milliseconds each, which is why a trail that
     * was demonstrably being painted never showed up until the gesture ended.
     */
    var onGestureInkPace: (active: Boolean) -> Unit = {}

    /** Called with a view-space delta when a selection drag completes. */
    var onSelectionMoved: (dx: Int, dy: Int) -> Unit = { _, _ -> }

    private var movingSelection = false
    /** Where the box was when the drag began, so a cancelled drag can put it back. */
    private var dragBoxOrigin: Rect? = null
    private var moveFromX = 0f
    private var moveFromY = 0f

    private var fingerDownY = 0f
    private var fingerDownX = 0f
    private var fingerTracking = false

    /** Last y already reported to the document, so drags emit deltas not absolutes. */
    private var fingerLastY = 0f
    private var fingerScrolling = false

    /** Called when a drag begins/ends, so the panel can switch waveform for the gesture. */
    var onScrollGesture: (active: Boolean) -> Unit = {}

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tool = event.getToolType(0)
        val isPen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

        // Finger never inks (palm rejection rule 1, SPEC.md §4.3) — the engine is told
        // setFingerWritable(false) — so the finger is free to mean "turn the page".
        // Swiping up moves down the document, matching the direction content would move.
        if (!isPen) {
            // Never scroll while a pen stroke is in flight. Points are captured in view
            // coordinates and converted to world space once, at pen-up, so a scroll
            // partway through would displace everything drawn before it.
            if (uiStrokeActive) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fingerDownX = event.x; fingerDownY = event.y
                    fingerLastY = event.y
                    fingerTracking = true; fingerScrolling = false
                }

                // Scroll DURING the drag, not on release. Waiting for pen-up is what made
                // this feel like a page turn: nothing moves while your finger does. The
                // content now tracks the finger, quantised to SCROLL_QUANTUM_PX so the
                // panel is not asked to repaint on every one of ~60 events per second.
                MotionEvent.ACTION_MOVE -> {
                    if (!fingerTracking) return true
                    val totalDy = event.y - fingerDownY
                    val totalDx = event.x - fingerDownX
                    if (!fingerScrolling) {
                        if (kotlin.math.abs(totalDy) > SWIPE_DEADZONE_PX &&
                            kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)
                        ) {
                            fingerScrolling = true
                            onScrollGesture(true)
                            fingerLastY = event.y
                        } else {
                            return true
                        }
                    }
                    val dy = event.y - fingerLastY
                    if (kotlin.math.abs(dy) >= SCROLL_QUANTUM_PX) {
                        fingerLastY = event.y
                        onSwipe(-dy.toInt())
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (fingerTracking) {
                        fingerTracking = false
                        // Flush the remainder so the content ends where the finger did.
                        val dy = event.y - fingerLastY
                        if (fingerScrolling && kotlin.math.abs(dy) >= 1f) onSwipe(-dy.toInt())
                        if (fingerScrolling) onScrollGesture(false)
                        fingerScrolling = false
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (fingerScrolling) onScrollGesture(false)
                    fingerTracking = false; fingerScrolling = false
                }
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A pen-down ON an existing lasso selection drags it rather than drawing —
                // what makes a selection feel like an object you can pick up. Two guards,
                // both learned the hard way: only while the lasso tool is actually active,
                // and only near the selected ink itself. Testing the selection's bounding
                // box instead meant a scattered selection covered the screen and captured
                // every later pen-down, so the next lasso the user drew silently dragged
                // the old selection into view.
                if (penMode == PenMode.LASSO && selectionHit(event.x, event.y)) {
                    movingSelection = true
                    moveFromX = event.x; moveFromY = event.y
                    // Kept only so a cancelled drag can restore the box.
                    dragBoxOrigin = selectionBox?.let { Rect(it) }
                    // NO live feedback during the drag, deliberately — two attempts failed
                    // for reasons that are now understood (SPEC §0.0a):
                    //  1. Dropping the engine mid-gesture (enableTouchDispatch(4)) makes it
                    //     abort its in-flight stroke with clearContent "at writing" and a
                    //     whole-host invalidate; its overlay then blanks the region.
                    //  2. Sliding an overlay box with per-step invalidates triggers the
                    //     engine's objType-1 handler to re-composite the whole host on each
                    //     one — "postDirtyRectX: abort Float/Obj cause mHostBmp not ready" —
                    //     and with no host bitmap prepared that composite is blank, so every
                    //     step painted white over the ink being dragged.
                    // The engine draws its dashed loop over the moving ink during the drag;
                    // the ink moves once at pen-up. Live feedback needs the float-layer route
                    // (setHostBmpForErasing at pen-down) and is deferred.
                    Log.i(TAG, "UI: dragging selection from (${event.x.toInt()}, ${event.y.toInt()})")
                    return true
                }
                // Cheap: beginFast early-returns when the mode is already set, but this
                // re-arms if the settle debounce fired between strokes.
                if (penMode != PenMode.PEN) onGestureInkPace(true)
                uiMoveEvents = 0; uiSamples = 1; uiStrokeActive = true
                // Sample clock: the real ms-per-sample, which TchRaster.SAMPLE_PERIOD_MS
                // can only assume. Measured from the same MotionEvent timestamps the
                // engine uses, so the two are directly comparable.
                strokeDownTime = event.eventTime; strokeSamples = 0
                pMin = Float.MAX_VALUE; pMax = -Float.MAX_VALUE; pSum = 0.0; pCount = 0
                pDistinct.clear()
                strokePoints.clear()
                strokePoints.add(floatArrayOf(event.x, event.y, event.pressure))
                gesturePath.rewind()
                lastErasedX = event.x; lastErasedY = event.y
                lastErasedIndex = 0
                notePressure(event.pressure)
                Log.i(
                    TAG,
                    "UI: DOWN tool=$tool pen=$isPen p=${"%.3f".format(event.pressure)} " +
                        "thread=${Thread.currentThread().name}"
                )
            }
            MotionEvent.ACTION_MOVE -> {
                // A selection drag records nothing and repaints nothing until pen-up — see
                // the DOWN handler for why any mid-drag invalidate blanks the ink.
                if (movingSelection) return true
                uiMoveEvents++
                uiSamples += event.historySize + 1
                strokeSamples += event.historySize + 1
                strokeUpTime = event.eventTime
                // Drain the historical batch too — those samples carry their own pressure
                // and are the majority of the stream at ~480 Hz.
                val firstNew = strokePoints.size
                for (h in 0 until event.historySize) {
                    notePressure(event.getHistoricalPressure(0, h))
                    strokePoints.add(
                        floatArrayOf(
                            event.getHistoricalX(0, h), event.getHistoricalY(0, h),
                            event.getHistoricalPressure(0, h)
                        )
                    )
                }
                notePressure(event.pressure)
                strokePoints.add(floatArrayOf(event.x, event.y, event.pressure))

                if (DIAG) {
                    diagMoves++
                    if (diagMoves % 20 == 0) {
                        Log.i(TAG, "DIAG move#$diagMoves mode=$penMode showTrail=$showGesturePath " +
                            "stream=$streamErase pts=${strokePoints.size}")
                    }
                }
                if (showGesturePath) {
                    // Two paths on purpose. The engine post is the one that might be fast;
                    // our own canvas is the floor, because a late outline beats no outline
                    // and going engine-only left the user with nothing at all.
                    for (k in maxOf(1, firstNew) until strokePoints.size) {
                        val a = strokePoints[k - 1]; val b = strokePoints[k]
                        if (gesturePath.isEmpty) gesturePath.moveTo(a[0], a[1])
                        gesturePath.lineTo(b[0], b[1])
                    }
                    // Not in LASSO: there the engine draws the loop itself, and the
                    // writing bitmap must be genuinely empty at pen-up or objType 1's
                    // CLEAR fill would have something of ours to cut out.
                    if (!showGesturePath) postTrailSpan(maxOf(1, firstNew))
                    // Kept for every mode: our own dashed trail is the floor, so if the
                    // engine's float loop turns out invisible on this panel the outline is
                    // merely slow rather than absent.
                    invalidateGestureSpan(maxOf(0, firstNew - 1))
                }
                // Throttle by distance, not by event. The digitizer reports ~480 samples
                // a second and each pass scans the document, so running per sample meant
                // the pen outran the eraser and the result arrived in a lump.
                if (DIAG && streamErase) Log.i(TAG, "DIAG stream check at pts=${strokePoints.size}")
                if (streamErase && movedSince(lastErasedX, lastErasedY, event.x, event.y, ERASE_STEP_PX)) {
                    lastErasedX = event.x; lastErasedY = event.y
                    // Erase as the pen moves rather than waiting for pen-up. Erasing only
                    // on release made a scribble sit there and then jump, which reads as a
                    // stall rather than as erasing.
                    // From the last sample the eraser already saw, not from this event's
                    // first sample — so no sub-path between batches is ever skipped.
                    val batch = ArrayList<FloatArray>(strokePoints.size - lastErasedIndex)
                    for (k in lastErasedIndex until strokePoints.size) batch.add(strokePoints[k])
                    lastErasedIndex = strokePoints.size - 1
                    if (batch.size >= 2) onEraseProgress(batch)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                // Discard, do not commit. The engine drops its wet ink on cancel, so
                // committing here made ink appear that was never seen being drawn — or ran
                // an erase the user did not finish.
                if (penMode != PenMode.PEN) onGestureInkPace(false)
                if (movingSelection) {
                    // A cancelled drag puts everything back: the flag (a stuck flag would
                    // turn every later pen-down into a drag) and the box.
                    movingSelection = false
                    selectionBox = dragBoxOrigin
                    dragBoxOrigin = null
                    Log.i(TAG, "UI: selection drag cancelled")
                }
                if (uiStrokeActive) {
                    uiStrokeActive = false
                    // Live spot-erase batches have already changed the document; the
                    // document must close the gesture's undo entry.
                    if (streamErase) onEraseCancelled()
                    strokePoints.clear()
                    gesturePath.rewind(); invalidate()
                    Log.i(TAG, "UI: stroke cancelled, discarded")
                }
            }

            MotionEvent.ACTION_UP -> {
                // Settle is debounced, so a run of erase strokes stays in fast mode.
                if (penMode != PenMode.PEN) onGestureInkPace(false)
                // Clear the gesture trail. It was only rewound on mode change, pen-down and
                // cancel — never on a normal pen-up — so the dashed loop stayed on screen
                // after the erase completed.
                if (showGesturePath) {
                    clearTrailOverlay()
                    gesturePath.rewind()
                    invalidate()
                }
                if (movingSelection) {
                    movingSelection = false
                    dragBoxOrigin = null
                    // The move repaints the ink and sets the box to its real new bounds at
                    // quality.
                    onSelectionMoved((event.x - moveFromX).toInt(), (event.y - moveFromY).toInt())
                    return true
                }
                if (uiStrokeActive) {
                    uiStrokeActive = false
                    if (strokePoints.isNotEmpty()) onStrokeFinished(ArrayList(strokePoints))
                    val mean = if (pCount > 0) pSum / pCount else 0.0
                    val varies = pDistinct.size > 3
                    // The measured sample period. TchRaster assumes 2 ms (~480 Hz); if this
                    // reads materially lower the ported speed term under-reads speed and
                    // over-predicts width, worst on slow strokes where decimation is heaviest.
                    val span = strokeUpTime - strokeDownTime
                    if (strokeSamples > 1 && span > 0) {
                        Log.i(
                            TAG,
                            "SAMPLECLOCK: %.3f ms/sample (%.0f Hz) over %d samples in %d ms"
                                .format(
                                    span.toDouble() / strokeSamples,
                                    1000.0 * strokeSamples / span, strokeSamples, span
                                )
                        )
                    }
                    Log.i(
                        TAG,
                        "UI: UP  moveEvents=$uiMoveEvents samples=$uiSamples pen=$isPen | " +
                            "PRESSURE n=$pCount min=${"%.3f".format(pMin)} " +
                            "max=${"%.3f".format(pMax)} mean=${"%.3f".format(mean)} " +
                            "distinct=${pDistinct.size} -> " +
                            if (varies) "REAL (usable for §6.1)"
                            else "CONSTANT/ABSENT (vector model must not depend on it)"
                    )
                }
            }
        }
        // MUST return true. Android stops delivering a gesture to a view that declines
        // ACTION_DOWN, so returning false makes the UI thread look starved of MOVE/UP
        // even if the framework is forwarding them. Consuming here is safe: the engine
        // reads its own dup'd InputChannel on PWInputThread BEFORE normal dispatch, so
        // our return value cannot affect what it draws.
        return true
    }

    // --- Phase 0 spike -------------------------------------------------------------
    // The architecture the working e-ink apps use (Notable, Notate) is the inverse of what
    // we built: the vendor engine renders ONLY the live stroke, while the app owns the
    // persisted content and the scroll, pausing the ink layer across a gesture. Two facts
    // gate that design, and both are checked here.

    /**
     * S1 — pause/resume the wet-ink layer.
     *
     * Onyx exposes this as PEN_PAUSE / setRawDrawingEnabled(false); it is what lets those
     * apps move their own content underneath the ink layer without smearing. Our
     * equivalent should be setPWEnabled. If this does not toggle cleanly — artifacts, or
     * lost engine state — the whole inverted architecture is off the table.
     */
    /**
     * Hand the pen to the engine, or take it away.
     *
     * **`setPWEnabled` does nothing.** It stores a field that the firmware never reads —
     * verified in the decompiled source, where the only other references are log strings.
     * Every earlier attempt to silence the engine for erase and lasso called it and had no
     * effect, which is why its cursor kept following the pen.
     *
     * The switch the firmware actually consumes is touch dispatch:
     *   `TOUCH_ALL_DROP (4)` — the engine drops the gesture before drawing anything, while
     *   normal dispatch still delivers it to our `onTouchEvent`.
     *   `TOUCH_HOST_LATER (2)` — the engine draws and also forwards to us. This is what we
     *   want for the pen, and what we were wrongly leaving on during erase.
     *
     * `disablePenErase` additionally stops the engine synthesising an erase gesture from
     * eraser-tool hover events, which it does independently of anything else.
     */
    fun setEngineHandlesPen(enabled: Boolean): Boolean = setEngineDispatch(enabled, !enabled)

    /**
     * Let the engine draw, and separately decide whether it may synthesise erases.
     *
     * These were one flag, which forced a false choice: in area-erase we wanted the engine's
     * renderer (for the outline) but not its eraser. Splitting them is what makes an
     * engine-drawn outline possible.
     */
    private fun setEngineDispatch(engineDraws: Boolean, blockPenErase: Boolean): Boolean {
        val engine = pw ?: return false
        val dispatch = if (engineDraws) DISPATCH_TOUCH_HOST else DISPATCH_TOUCH_ALL_DROP
        val a = cfg(engine, "enableTouchDispatch", arrayOf(Int::class.javaPrimitiveType!!), dispatch)
        val b = cfg(engine, "disablePenErase", arrayOf(Boolean::class.javaPrimitiveType!!), blockPenErase)
        Log.i(TAG, "PW: engine draws=$engineDraws dispatch=$dispatch blockPenErase=$blockPenErase ok=$a/$b")
        return a
    }

    /**
     * Modes where the ENGINE draws the outline instead of us.
     *
     * Measured, not guessed: our own pixels reach the engine's writing bitmap fine
     * (verified 2500/2500 via getPureWriteBitmap) and addOneDirtyRectInternal accepts the
     * post and returns true — yet nothing lands on the panel. Compositing runs through
     * PWDrawable.draw -> drawPWBitmapInternal, which only happens on a normal View draw
     * pass, so every app-side route ends up behind SurfaceFlinger and arrives late. The
     * engine's ink is fast because a live writing session hands the bitmap to the EBC
     * directly. No session, no fast path — so to get a fast outline, the engine has to be
     * the one holding the pen.
     */
    val engineDrawsOutline: Boolean
        get() = penMode == PenMode.ERASE_AREA || penMode == PenMode.LASSO

    /** Lighter-weight alternative to a full pause: hide the writing layer only. */
    fun setInkLayerVisible(visible: Boolean): Boolean {
        val engine = pw ?: return false
        return cfg(
            engine, "setPWBitmapInVisible", arrayOf(Boolean::class.javaPrimitiveType!!), !visible
        )
    }

    /**
     * S2 — does our own drawing land UNDER the engine's ink?
     *
     * The engine's PWDrawable lives in this View's overlay, so onDraw content should
     * composite below the wet stroke. If so, the app can own all persisted ink here and
     * let the engine handle only the live stroke on top.
     */
    var showTestPattern: Boolean = false
        set(value) { field = value; invalidate() }

    private val testPaint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    /**
     * Draw the gesture trail through the ENGINE's pipeline instead of our own.
     *
     * `View.invalidate` reaches the panel via SurfaceFlinger and, during a pen gesture,
     * far too late — the trail was provably painted 69 times and appeared only after the
     * gesture ended. The engine's ink is instant because it draws into its own writing
     * bitmap and posts the dirty rect straight to the EBC via `postDirtyRect`.
     *
     * `getPwCanvas()` hands us a Canvas over that same bitmap, and `fastUpdatePwRect` is
     * the public door to that post. Since touch dispatch is dropped in erase/lasso modes
     * the engine is not using the bitmap itself, so it is free to act as our overlay — and
     * it composites above our dry layer, which is exactly where a trail belongs.
     *
     * `flag = 0`: with a non-zero flag the engine drops the post when the last action was
     * UP (its own comment there reads "APP needs fix").
     */
    private val POST_DUAUTO = 16
    private val POST_FASTPW = 9

    private var loggedNoCanvas = false
    private var loggedFirstPost = false
    private var pwCanvasMethod: java.lang.reflect.Method? = null
    private var fastUpdateMethod: java.lang.reflect.Method? = null
    private var overlayResolved = false

    private fun resolveOverlay() {
        if (overlayResolved) return
        overlayResolved = true
        val engine = pw ?: return
        pwCanvasMethod = runCatching { engine.javaClass.getMethod("getPwCanvas") }.getOrNull()
        // addOneDirtyRectInternal, not fastUpdatePwRect. Both end at the same EBC call, but
        // fastUpdatePwRect drops any post with a non-zero flag while `mCurrentAction == 1`,
        // and that field is only written on the engine's send-back path — which dispatch=4
        // bypasses. So after the first pen stroke it would sit at UP forever and silently
        // swallow every post. (flag=0 is worse: it matches no branch and posts nothing.)
        // This entry point takes the waveform directly and has no such guard.
        fastUpdateMethod = runCatching {
            engine.javaClass.getMethod(
                "addOneDirtyRectInternal", Rect::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
            )
        }.getOrNull()
        Log.i(TAG, "PW: overlay canvas=${pwCanvasMethod != null} fastPost=${fastUpdateMethod != null}")
    }

    /** Draw one new span of the trail into the engine's bitmap and post it immediately. */
    private fun postTrailSpan(fromIndex: Int) {
        resolveOverlay()
        val engine = pw ?: return
        val canvas = runCatching { pwCanvasMethod?.invoke(engine) as? Canvas }.getOrNull()
        if (canvas == null) {
            if (!loggedNoCanvas) {
                loggedNoCanvas = true
                Log.w(TAG, "PW: getPwCanvas() returned null while engine pen is off — " +
                    "engine overlay unavailable, falling back to our own canvas")
            }
            return
        }
        if (strokePoints.size < 2 || fromIndex >= strokePoints.size) return

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        val span = Path()
        var started = false
        for (k in maxOf(1, fromIndex) until strokePoints.size) {
            val a = strokePoints[k - 1]
            val b = strokePoints[k]
            if (!started) { span.moveTo(a[0], a[1]); started = true }
            span.lineTo(b[0], b[1])
            minX = minOf(minX, a[0], b[0]); maxX = maxOf(maxX, a[0], b[0])
            minY = minOf(minY, a[1], b[1]); maxY = maxOf(maxY, a[1], b[1])
        }
        if (!started) return
        canvas.drawPath(span, gesturePaint)

        val pad = 6
        val r = Rect(
            (minX - pad).toInt(), (minY - pad).toInt(),
            (maxX + pad).toInt(), (maxY + pad).toInt()
        )
        // dispMode 16 (DUAUTO) + dataMode 9 (FASTPW) — the combination the engine posts its
        // own wet ink with. dispMode 16 makes it pick the matching A2 gate itself.
        val posted = runCatching { fastUpdateMethod?.invoke(engine, r, POST_DUAUTO, POST_FASTPW) }
            .onFailure { Log.w(TAG, "PW: dirty-rect post failed: ${it.cause ?: it}") }
            .getOrNull()
        if (!loggedFirstPost) {
            loggedFirstPost = true
            Log.i(TAG, "PW: first trail post rect=$r returned=$posted canvas=${canvas.width}x${canvas.height}")
        }
    }

    /** Wipe the trail from the engine's bitmap once the gesture ends. */
    /**
     * Diagnostic: draw a known block into the engine's bitmap and post it four different ways,
     * so a framebuffer readback can say which post path — if any — carries our own pixels.
     *
     * Not wired to any UI. Driven over adb, because the alternative is spending the user's
     * patience one pen gesture at a time.
     */
    fun pwOverlayTest(variant: Int): String {
        resolveOverlay()
        val engine = pw ?: return "no engine"
        val canvas = runCatching { pwCanvasMethod?.invoke(engine) as? Canvas }.getOrNull()
            ?: return "getPwCanvas null"
        val r = Rect(200, 300, 600, 700)
        val fill = Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.FILL }
        canvas.drawRect(Rect(r), fill)

        // Does our draw actually land in the bitmap the engine composites? This separates
        // "getPwCanvas is not backed by the writing bitmap" from "the bitmap never reaches
        // the panel" — two problems with completely different fixes.
        val inBitmap = runCatching {
            val bmp = engine.javaClass.getMethod("getPureWriteBitmap").invoke(engine) as? Bitmap
                ?: return@runCatching "getPureWriteBitmap null"
            var dark = 0; var tot = 0
            var y = r.top
            while (y < r.bottom && y < bmp.height) {
                var x = r.left
                while (x < r.right && x < bmp.width) {
                    val c = bmp.getPixel(x, y)
                    val a = (c ushr 24) and 0xFF
                    val lum = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
                    tot++
                    if (a > 0 && lum < 100) dark++
                    x += 8
                }
                y += 8
            }
            "$dark/$tot dark cfg=${bmp.config} ${bmp.width}x${bmp.height}"
        }.getOrElse { "EX:${it.cause ?: it}" }

        val policy = runCatching { engine.javaClass.getMethod("getErasePolicy").invoke(engine) }.getOrNull()
        val writing = runCatching { engine.javaClass.getMethod("isCurrentWriting").invoke(engine) }.getOrNull()

        val how: String
        val ret: Any?
        when (variant) {
            0 -> {
                how = "addOneDirtyRectInternal(r,16,9) DUAUTO/FASTPW"
                ret = runCatching {
                    fastUpdateMethod?.invoke(engine, Rect(r), POST_DUAUTO, POST_FASTPW)
                }.getOrElse { "EX:${it.cause ?: it}" }
            }
            1 -> {
                how = "addOneDirtyRectInternal(r,7,3) AUTO/DEFAULT"
                ret = runCatching {
                    fastUpdateMethod?.invoke(engine, Rect(r), 7, 3)
                }.getOrElse { "EX:${it.cause ?: it}" }
            }
            else -> {
                // flag 2 and 128 are the branches that copy mWritingBitmap into the host canvas.
                how = "fastUpdatePwRect(r,$variant)"
                ret = runCatching {
                    engine.javaClass.getMethod(
                        "fastUpdatePwRect", Rect::class.java, Int::class.javaPrimitiveType!!
                    ).invoke(engine, Rect(r), variant)
                    "void"
                }.getOrElse { "EX:${it.cause ?: it}" }
            }
        }
        val msg = "PWTEST v=$variant $how -> $ret canvas=${canvas.width}x${canvas.height} " +
            "inWriteBitmap=[$inBitmap] erasePolicy=$policy isCurrentWriting=$writing rect=$r"
        Log.i(TAG, msg)
        return msg
    }

    private fun clearTrailOverlay() {
        val engine = pw ?: return
        runCatching {
            engine.javaClass.getMethod(
                "clearContent", Rect::class.java,
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            ).invoke(engine, null, true, false, true)
        }.onFailure { Log.w(TAG, "PW: clearContent failed: ${it.cause ?: it}") }
    }

    /**
     * Repaint one rectangle at quality, to clear fast-waveform residue from it.
     *
     * The alternative was `forceEinkFullUpdate`, which cleans the whole panel with a
     * black flash — fine as a manual "refresh screen" action, wrong as the routine
     * cleanup after every lasso. This asks the EPD to redraw just the region the engine's
     * overlay dirtied, through the GC (quality) waveform, so the residue goes without the
     * flash. [rect] is in view coordinates; it is padded because a stroke paints slightly
     * beyond its own point bounds.
     *
     * GL, not GC. GC16 is the flashing full-refresh waveform; using it here — even on a
     * small rect — was the black flash reported on every lasso. GL16 repaints at quality
     * without the black/white cycle.
     */
    /**
     * Repaint after a document edit (undo/redo, selection move, erase gesture end). Deliberately
     * a plain whole-view invalidate — the normal SurfaceFlinger path — NOT the regional
     * `invalidate(Rect, mode)` channel this used to call with the GL waveform.
     *
     * Measured on device (REFRESHLOG, 2026-09-02): after a regional GL repaint of an edited
     * region, followed by the full settle (reset + whole-view repaint via SurfaceFlinger), the
     * panel LOOKED right, but drawing over that region with the engine's pen fast path revealed
     * the pre-edit ink under the pen until pen-up, and a selection box drawn through the same
     * channel while the panel was in DU left a permanent line of marching ants. Both were
     * cleared only by forceEinkFullUpdate. So the regional channel leaves panel state the
     * ordinary composition path cannot repair; the engine's fast path composites against that
     * state. Whole-view repaints through SurfaceFlinger keep the two in step. The rect is kept
     * for the log so a region can still be correlated with what the user saw.
     */
    fun refreshRegionAtQuality(rect: Rect, pad: Int = 8) {
        // Plain invalidate only — lasso, selection move and spot erase are all confirmed clean
        // on device with nothing more than this. See forceSyncAfterEdit for the two paths that aren't.
        Log.i(TAG, "REFRESHLOG: refreshRegionAtQuality rect=$rect (plain invalidate)")
        invalidate()
    }

    /**
     * Ask the ink engine to re-synchronise the pen's background with the current frame.
     *
     * WHY (measured on device, 2026-09-02, with the engine's own debug flags on). The engine's
     * pen fast path posts only a rect per sample; the native side blends the writing bitmap over
     * a copy of the frame that it keeps itself. That copy is refreshed by the engine's whole-view
     * einkMode-3 invalidate of the host view (`PWCoreCtrl.invalidateHost(null)`), which its
     * ViewRootImpl watcher (`GlobalVirtualView.checkPWViewUpdateAndSetCopyFlag`) issues when the
     * host repaints under the pen area — but only while `isWriteNoneDraw()` is true, i.e. ink
     * was written since the host last drew the writing bitmap. We never draw the writing bitmap
     * (the document is ours), yet the flag is still cleared by our post-stroke draw, so for our
     * undo/scroll/move repaints the watcher logged `writeNotDraw=false` and stayed silent, and
     * the next stroke revealed the previous frame under the pen. Spot erase never ghosted
     * because its DU-tagged live repaints happened to trip the watcher.
     *
     * So we make the watcher's call ourselves, through the engine's public API. Not to be
     * confused with the two things that made it WORSE: our own `invalidate(Rect, 3)` on a
     * sub-rectangle left stale panel state (a stuck line of marching ants), and a whole-view
     * `invalidate(Rect, 30)` — the pen-up sync the engine issues itself — ghosted everything.
     */
    /**
     * The one fix that has ever cleared a stale-panel artifact from the pen engine's fast path,
     * on device, all session (2026-09-02): forceEinkFullUpdate — which flashes. Six non-flashing
     * alternatives were tried and ruled out in an on-device A/B against the undo case: the
     * engine's own whole-view resync (invalidateHost/mode 3), EinkManager.sendOneFullFrame,
     * screenRefresh at both full and partial quality (modes 3 and 8), a whole-view mode-7
     * invalidate, and the engine's sync-window protocol (which this firmware's kernel reports as
     * unsupported — status stayed `INVAL` before and after). Every one of them still ghosted.
     * Only this flashes, and only this clears it.
     *
     * Two call sites, both a rare, discrete user action rather than a continuous gesture, so the
     * flash is a one-time cost the user pays for something they just deliberately finished:
     *  - ScrollingDocument.afterEdit (undo/redo) — drawing over the changed area ghosted the
     *    pre-edit ink under the pen.
     *  - ScrollingDocument.moveSelection, after a move completes — the lasso outline is drawn
     *    live by the engine's own fast path (objType 1), and left the same kind of residue
     *    (a persistent line of marching ants) at the box's original location.
     * Spot erase and scroll are clean with a plain repaint and get none of this.
     */
    fun forceSyncAfterEdit() {
        EinkRefresh.resolve()
        EinkRefresh.forceFullUpdate(this)
    }


    /**
     * Repaint one rectangle on the FAST (DU) waveform — for feedback that has to keep up
     * with the pen, such as ink vanishing under a spot eraser. Residue is cleaned by the
     * gesture-end settle. [rect] is in view coordinates.
     *
     * Exists because the live erase path used the deprecated `invalidate(l,t,r,b)`, which a
     * hardware-accelerated view discards (SPEC §5.6): the dry layer updated on every erase
     * step but the panel did not, so ink only visibly disappeared at pen-up — the "big
     * latency" on spot erase. The eink-aware overload is the one the EPD honours.
     */
    fun refreshRegionFast(rect: Rect, pad: Int = 8) {
        invalidateEink(
            (rect.left - pad).coerceAtLeast(0),
            (rect.top - pad).coerceAtLeast(0),
            (rect.right + pad).coerceAtMost(width),
            (rect.bottom + pad).coerceAtMost(height),
            EinkRefresh.MODE_DU,
        )
    }

    private var invalidateEinkMethod: java.lang.reflect.Method? = null
    private var invalidateEinkResolved = false

    /** `View.invalidate(Rect, einkMode)` — carries the waveform with the update. */
    private fun invalidateEink(l: Int, t: Int, r: Int, b: Int, mode: Int) {
        if (!invalidateEinkResolved) {
            invalidateEinkResolved = true
            invalidateEinkMethod = runCatching {
                View::class.java.getMethod(
                    "invalidate", Rect::class.java, Int::class.javaPrimitiveType!!
                )
            }.getOrNull()
            Log.i(TAG, "PW: eink invalidate ${if (invalidateEinkMethod != null) "available" else "MISSING"}")
        }
        val m = invalidateEinkMethod
        // REFRESHLOG: every regional eink invalidate, with rect and waveform, so the exact
        // sequence behind a ghost or a stuck overlay can be read rather than inferred.
        Log.i(TAG, "REFRESHLOG: invalidateEink rect=($l,$t-$r,$b) mode=$mode fast=${EinkRefresh.isFast}")
        if (m == null) { invalidate(); return }
        runCatching { m.invoke(this, Rect(l, t, r, b), mode) }.onFailure { invalidate() }
    }

    /** Repaint just the newest span of the gesture trail. */
    private fun invalidateGestureSpan(fromIndex: Int) {
        if (strokePoints.size < 2) return
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in fromIndex until strokePoints.size) {
            val p = strokePoints[k]
            if (p[0] < minX) minX = p[0]
            if (p[0] > maxX) maxX = p[0]
            if (p[1] < minY) minY = p[1]
            if (p[1] > maxY) maxY = p[1]
        }
        // Use the eink-aware overload. A hardware-accelerated view discards this rect for
        // its own damage calculation, but the EPD dirty-rect channel DOES honour it — so
        // this is a band-sized panel update instead of a full-screen one, and it carries
        // the waveform explicitly rather than relying on the view's sticky mode.
        val pad = 8
        invalidateEink(
            (minX - pad).toInt(), (minY - pad).toInt(),
            (maxX + pad).toInt(), (maxY + pad).toInt(),
            EinkRefresh.MODE_DU
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The document, beneath the engine's wet stroke. PWDrawable lives in this View's
        // overlay, so anything painted here composites underneath the live ink.
        dryLayer?.draw(canvas)

        if (showGesturePath && !gesturePath.isEmpty) {
            canvas.drawPath(gesturePath, gesturePaint)
            drawCount++
        }

        // The selection box, over the document and under the wet ink. A static dashed
        // rectangle rather than a fill or animation: a fill is indistinguishable from ink
        // at 16 grey levels, and animation costs a panel refresh per frame.
        selectionBox?.let { b ->
            canvas.drawRect(
                (b.left - SELECTION_PAD).toFloat(), (b.top - SELECTION_PAD).toFloat(),
                (b.right + SELECTION_PAD).toFloat(), (b.bottom + SELECTION_PAD).toFloat(),
                selectionPaint,
            )
        }

        if (DIAG && drawCount != lastLoggedDrawCount) {
            lastLoggedDrawCount = drawCount
            Log.i(TAG, "DIAG onDraw: mode=$penMode trailPainted=$drawCount")
        }
        if (!showTestPattern) return
        // Horizontal rules plus a diagonal — write across them and it is immediately
        // obvious whether engine ink lands above or below, and whether the two fight.
        val step = height / 12f
        var y = step
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, testPaint)
            y += step
        }
        canvas.drawLine(0f, 0f, width.toFloat(), height.toFloat(), testPaint)
        Log.i(TAG, "SPIKE S2: drew test pattern in onDraw (${width}x$height)")
    }
}
