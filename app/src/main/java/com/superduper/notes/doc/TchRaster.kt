package com.superduper.notes.doc

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs

/**
 * Renders strokes into the app's own dry layer.
 *
 * Ink is drawn without anti-aliasing, matching what the engine does for its live stroke and
 * for the same reason: a 16-grey panel dithers soft edges into mud, and binary transitions
 * are what the fast waveform handles cleanly (SPEC.md §4.4). Toolbar icons are the opposite
 * case — static, drawn once, so they are anti-aliased.
 *
 * Stroke weight is not our own curve: it is a port of the firmware's own pen-width
 * algorithm, so that a stroke re-drawn here is the same weight the engine drew when it was
 * wet. See [engineWidths].
 */
object TchRaster {

    private val paint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // ---------------------------------------------------------------------------------
    // The firmware's pen-width tables, transcribed literally.
    //
    // JADX renders these with whatever unrelated constant happened to share the value
    // (`PWDrawObjClickErase.mDisappearDelayMs` for 120, and so on). Every one of those is a
    // `static final int` with a literal initialiser, so the substitution is safe and the
    // arrays below are the real bytecode contents — confirmed by re-decompiling htfypw.jar
    // with `jadx --no-replace-consts`, which prints the same numbers as raw literals. Each
    // is a flat key,value,key,value… ladder read by [mapValue].
    //
    // ASSUMPTION — which tables are live is decided by four device properties that cannot
    // be read out of the firmware, only off the device. These are the defaults, which hold
    // unless a property says otherwise:
    //
    //     adb shell getprop ro.htfy.eink.dypen   true swaps PRESS_MAP for DY_PRESS_MAP
    //                                            {70,9, 96,10, 200,20, 3500,350, 4096,410}
    //                                            — the biggest single lever on weight
    //     adb shell getprop ro.sys.project       "jyt103s" swaps *both* width tables for
    //                                            JTY103_{PP,SPEED}_WIDTH_MAP
    //                                            (`PenPenConfig:32-33,40-46`)
    //     adb shell getprop ro.eink.pentype      3 swaps SPEED_WIDTH_MAP for
    //                                            GOODIX_PEN_SPEED_WIDTH_MAP
    //                                            (`PenPenConfig:36-39`)
    //     adb shell getprop ro.sf.lcd_density    picks DEFAULT_PENWIDTH_MAP_78 over _103
    //                                            above 250 — not load-bearing here, since
    //                                            setPenStdWidth overrides what it feeds
    //
    // The first three are unverified on this device. A mismatch shows up immediately in the
    // `--es tool penwidth` measurement (ScrollingDocument.measureInk) rather than silently,
    // and correcting it is a matter of swapping the literals below.
    // ---------------------------------------------------------------------------------

    /**
     * Raw pressure → "physical pressure". `PWPenConfig.CP_PRESS_MAP:41`, the default map.
     *
     * Shared by every nib: `getPPbyPress` reads `PEN_DP2PP_MAP`, which is a property of the
     * digitizer rather than of the pen style, so it does not live in [PenStyle].
     */
    private val PRESS_MAP = intArrayOf(
        70, 9, 96, 10, 1000, 25, 1500, 37, 2000, 56, 2500, 90, 3000, 140, 3500, 213, 4096, 350
    )

    /** `PWPenConfig.EXPEND_PW_WIDTH_RATE:22` — the fixed-point divisor for pRate × sRate. */
    private const val WIDTH_RATE = 16384f

    /** `PWPenConfig.MAX_POINT_SPEED:26`. */
    private const val MAX_POINT_SPEED = 4000

    /** `PWPenConfig.MIN_DRAW_PP:29` — below this the engine gives up and returns 0.5. */
    private const val MIN_DRAW_PP = 10

    /**
     * The literal numerator in `getSpeedRateForPoint2:261`.
     *
     * Note this is *not* `DPI_SPEED`: the sibling `getSpeedRateForPoint0:233` reads that
     * field, while the variant actually used by `getPointWidthByMap` hard-codes 200.
     */
    private const val SPEED_NUMERATOR = 200

    /**
     * The engine ignores a sample until it has moved this far from the last one it kept
     * (`GlobalVirtualView.enqueueEventData:2616`). We record every historical sample, so
     * the engine sees a sparser stroke than we store — and since speed is measured between
     * *kept* samples, the widths only line up if we drop the same ones.
     */
    private const val ENGINE_MOVE_THRESHOLD_PX = 4

    /**
     * Milliseconds per digitizer sample — the one number here not read out of the firmware.
     *
     * **Measured, not assumed.** On this Nomad it is 2.067–2.082 ms across 14 strokes
     * (mean 2.074, i.e. 482 Hz), logged as `SAMPLECLOCK` from the same `MotionEvent`
     * timestamps the engine itself uses. The engine truncates the gap to whole
     * milliseconds, so 2 is the right integer and there is nothing left to tune here.
     *
     * The engine times its points. `repeat` is the gap in whole milliseconds since the
     * previous kept sample: `GlobalVirtualView.enqueueEventData` computes it from
     * `MotionEvent.getEventTime()` at `:2622` for historical samples and `:2655` for the
     * batch's own, and `PWInternalDraw:190` stores it on the point. That it is a duration
     * and not a repeat count is not an inference — the framework class exposes the same
     * field as `PWInputPoint.getDelayMs():85-87`, and `PWCoreCtrl:1953` reads it into a
     * variable named `pointDelay` and passes it to `sendMessageDelayed`.
     *
     * Our records carry no timestamps — but they do not need to. Samples arrive at a
     * near-constant rate, so the *count* of samples between two kept points is the elapsed
     * time, and the gap is `(skipped + 1) × SAMPLE_PERIOD_MS`. At the panel's ~480 Hz that
     * is 2.08 ms, and the engine truncates to an int anyway.
     *
     * If this ever needs to stop being an assumption, the slot is already there and already
     * has the right name: `TchFile`'s record reserves an `int32 repeat` at offset 10 — the
     * engine's own field — and `buildStroke` currently leaves it zero. The capture site sees
     * `MotionEvent` timestamps, so filling it in would replace this constant with the real
     * gap. It is left alone here because it changes the on-disk format and every stroke
     * already saved would need a fallback to this estimate anyway.
     */
    private const val SAMPLE_PERIOD_MS = 2

    /**
     * The gap the engine assigns to a stroke's first point, regardless of the clock:
     * `PWInternalDraw.pushMotionEvent:177` hard-codes `evTime2 = 4` on ACTION_DOWN. It
     * matters because the second point divides by `thisGap + firstGap`, so getting it
     * wrong skews the speed exactly where a stroke is most visible.
     */
    private const val PEN_DOWN_GAP_MS = 4

    /**
     * Width used for strokes saved before the width was recorded per stroke.
     *
     * A fixed constant, deliberately, rather than the pen weight selected now. Falling back
     * to the live setting meant every stroke in an older document re-rendered at whatever
     * pen the user had just picked — so switching pens visibly changed the thickness of ink
     * drawn days earlier. Their true width is not recoverable, but it is far better for it
     * to be *stable and wrong by a little* than to move every time the pen changes.
     *
     * This is the app's default weight at the time the field was introduced, so documents
     * written around then render at close to their original weight.
     */
    private const val LEGACY_STD_WIDTH = 6.2f

    /**
     * Draw [strokes] onto an existing canvas, respecting whatever clip it carries.
     *
     * The clip is what makes scrolling cheap: the dry layer rasterises only the band a
     * scroll newly exposed, rather than the whole screen.
     *
     * Each stroke renders at the weight and nib recorded on it, so [baseWidth] is no longer
     * consulted here at all — it is kept in the signature for the measurement path, and
     * strokes with no recorded width use [LEGACY_STD_WIDTH] instead. Rendering old ink at
     * the *current* pen weight is what made switching pens change the thickness of ink that
     * was already on the page.
     */
    @Suppress("UNUSED_PARAMETER")
    fun drawInto(canvas: Canvas, strokes: List<TchFile.Stroke>, baseWidth: Float) {
        val seg = Path()
        strokes.forEach { stroke ->
            val pts = stroke.points
            if (pts.isEmpty()) return@forEach
            // Per stroke, not per call: a document mixes nibs AND weights, and each
            // stroke was drawn by the engine with the pair recorded in its own config
            // record. [baseWidth] is only the fallback for strokes saved before the width
            // was recorded — using it for everything made old ink change thickness
            // whenever the pen setting changed.
            val widths = engineWidths(pts, stroke.stdWidth ?: LEGACY_STD_WIDTH, stroke.style)

            if (pts.size == 1) {
                paint.strokeWidth = widths[0]
                canvas.drawPoint(pts[0].x.toFloat(), stroke.yOf(pts[0]).toFloat(), paint)
                return@forEach
            }

            // Per-segment width, not one width for the whole stroke. The engine varies
            // thickness point by point; painting the committed stroke at a single mean
            // width made ink visibly change character the moment the pen lifted. A new
            // sub-path is emitted only when the width actually changes, so a 700-point
            // stroke does not cost 700 draw calls — and because the engine quantises width
            // to whole pixels, those changes are rare.
            var lastX = pts[0].x.toFloat()
            var lastY = stroke.yOf(pts[0]).toFloat()
            var midX = lastX
            var midY = lastY
            var runWidth = widths[0]
            seg.rewind()
            seg.moveTo(midX, midY)

            for (i in 1 until pts.size) {
                val x = pts[i].x.toFloat()
                val y = stroke.yOf(pts[i]).toFloat()
                val newMidX = (lastX + x) / 2f
                val newMidY = (lastY + y) / 2f

                if (widths[i] != runWidth) {
                    paint.strokeWidth = runWidth
                    canvas.drawPath(seg, paint)
                    seg.rewind()
                    seg.moveTo(midX, midY)
                    runWidth = widths[i]
                }
                seg.quadTo(lastX, lastY, newMidX, newMidY)
                midX = newMidX; midY = newMidY
                lastX = x; lastY = y
            }
            paint.strokeWidth = runWidth
            canvas.drawPath(seg, paint)
        }
    }

    /**
     * The per-point widths this renderer would use for [stroke], for checking the port
     * against the engine numerically rather than by eye.
     *
     * A `0` entry means an Android hairline, so anything comparing this against measured
     * pixels should read it as 1. See `ScrollingDocument.measureInk`.
     */
    fun widthsFor(stroke: TchFile.Stroke, baseWidth: Float): FloatArray =
        engineWidths(stroke.points, stroke.stdWidth ?: baseWidth, stroke.style)   // fresh strokes always record theirs

    /**
     * Indices of the points the engine would have kept, by the same ±4 px rule
     * [engineWidths] uses.
     *
     * Exposed for measurement. The engine draws straight lines between *these* points, so
     * this polyline — not the raw sample list — is the length its ink actually covers.
     * Measuring against every stored sample instead inflates the denominator with pen
     * jitter, badly on slow strokes where consecutive integer coordinates differ by a pixel
     * or less, and makes correct ink read as too thin.
     */
    fun keptIndices(stroke: TchFile.Stroke): IntArray {
        val pts = stroke.points
        val kept = ArrayList<Int>(pts.size)
        var haveRef = false
        var refX = 0
        var refY = 0
        for (i in pts.indices) {
            val x = pts[i].x
            val y = pts[i].y
            if (!haveRef ||
                abs(x - refX) >= ENGINE_MOVE_THRESHOLD_PX ||
                abs(y - refY) >= ENGINE_MOVE_THRESHOLD_PX
            ) {
                kept.add(i)
                refX = x; refY = y; haveRef = true
            }
        }
        return kept.toIntArray()
    }

    /**
     * The stroke width the engine would have used for each point, in device pixels.
     *
     * A port of `PWPenConfig.getPointWidthByMap:179-200` plus the two clamps that sit
     * between it and the canvas. The previous hand-fitted curve here was a function of
     * pressure alone; the engine's is a function of pressure *and speed*, and across the
     * pressure range a stylus actually reports (~0.03–0.55, SPEC.md §4.1c) speed is by far
     * the larger term — pressure moves the width about 10%, speed about 35%. That is why
     * re-calibrating the old curve never converged: it was missing the dominant variable.
     *
     * The chain, with the firmware's own names:
     *
     *     pp    = PRESS_MAP[4095 × pressure]           getPPbyPress:120
     *     pRate = PP_WIDTH_MAP[pp]                     getPenWidthByPP:149
     *     sRate = SPEED_WIDTH_MAP[speed]               getPenWidthBySpeed:154
     *     r     = pRate × sRate × stdWidth / 16384     getPointWidthByMap:195
     *
     * `stdWidth` is the app's own `setPenStdWidth` value, passed through untouched
     * (`PWDrawObjectAlgo.getCurPointRadus:446` hands it straight to `calcPointRadus`).
     *
     * `r` is named a radius throughout the firmware but is **not** one: it reaches the
     * canvas as `paint.setStrokeWidth(r)` (`PWRandomDrawPen.drawlineByPoints:35` — the
     * handler registered for `drawObjType 0`, `PWDrawObjectAlgo:138-139`), and the per-point
     * dirty rect halves it about the point (`PWBaseDrawObjHandler.getPointRect:122-128`).
     * Reading it as a radius and doubling it is exactly the factor-of-two that made
     * re-drawn ink look heavy. See [quantise] for the clamps that follow, which matter
     * more than the curve does.
     *
     * At the 2.5 std width we configure, `r` lands between 0.77 and 1.27 across the whole
     * usable range, so every point quantises to a hairline and ink is uniform. The full
     * curve is ported rather than hard-coding that, because it is what makes a thicker pen
     * setting behave correctly the moment one is exposed.
     *
     * Three known divergences, all confined to the ends of a stroke and all costing more
     * to reproduce than they are worth:
     *   * The engine drops a point outright when its pp is 1..9
     *     (`PWRandomDrawPen.drawPoints:169`); we paint a hairline. That band is
     *     pressure below about 0.024.
     *   * It forces the pen-up point's pressure to 0.001 (`PWInternalDraw:188`), which puts
     *     it in that same undrawn band; we use the reported pressure.
     *   * The last sample of each `MotionEvent` batch gets its gap measured from the
     *     previous batch rather than from the last kept sample
     *     (`GlobalVirtualView:2655` reads the field, not the local it just advanced), so
     *     the engine over-states that one gap. We cannot see batch boundaries in a stored
     *     stroke, so we measure every gap the same way.
     *
     * Together those make the kept-sample sequence itself slightly unrecoverable: which
     * samples the engine kept depends on where its 4 px reference point had landed, which
     * depends on the batch boundaries we cannot see, which feeds back into every subsequent
     * gap and therefore into the smoothed speed. This is why the `penwidth` check compares
     * a stroke's *mean* width rather than matching point for point — the aggregate is
     * stable even though individual points may differ by a sample.
     *
     * A fourth difference is geometric rather than in the weight: where two neighbouring
     * points differ by more than 1.4 px the engine ramps between them, subdividing the
     * segment and interpolating both radius and position (`PWRandomDrawPen.drawLine:
     * 96-149`). Below that — which, since these widths are integers, is every segment
     * whose ends differ by at most 1 — it draws one constant-width line at the later
     * point's width, exactly as we do. At the std width we ship, where everything is a
     * hairline, the ramp never runs at all.
     */
    private fun engineWidths(
        pts: List<TchFile.Record>,
        stdWidth: Float,
        style: PenStyle,
    ): FloatArray {
        val out = FloatArray(pts.size)

        // The constant-width nibs never look at the point at all — the marker because
        // getPointRadusForMarkPen:89-91 ignores its argument, the fixed pen because
        // getPenAdjustRadius:284-286 returns before the pressure lookup. No pressure, no
        // speed, no per-point state.
        if (style.isConstantWidth) {
            val w = quantise(
                if (style == PenStyle.MARKER) ((stdWidth * style.constantScale) + 0.5f).toInt().toFloat()
                else stdWidth * style.constantScale
            )
            return FloatArray(pts.size) { w }
        }

        val ppMap = style.ppMap!!
        val speedMap = style.speedMap

        var havePrev = false      // P1 exists
        var havePrev2 = false     // P0 exists
        var prevX = 0
        var prevY = 0
        var prevGapMs = 0
        var prevRawPp = 0
        var prev2RawPp = 0
        // The engine reuses pooled points, and a fresh one carries speed = -1
        // (`PWPoint.Invalid:118`). The first point of a stroke never runs the speed branch,
        // so the second point genuinely reads -1 back out of it.
        var prevSpeed = -1
        var prev2Speed = -1

        var keptX = 0
        var keptY = 0
        var skipped = 0
        var current = 0f

        for (i in pts.indices) {
            val p = pts[i]
            val x = p.x
            val y = p.y

            // Mirror the engine's sample filter. Note it advances its reference point only
            // when it accepts one, so small drifts accumulate rather than each being
            // measured against its immediate neighbour.
            val kept = !havePrev ||
                abs(x - keptX) >= ENGINE_MOVE_THRESHOLD_PX ||
                abs(y - keptY) >= ENGINE_MOVE_THRESHOLD_PX
            if (!kept) {
                skipped++
                out[i] = current
                continue
            }

            val gapMs = if (havePrev) (skipped + 1) * SAMPLE_PERIOD_MS else PEN_DOWN_GAP_MS
            val rawPp = mapValue((4095f * p.pressure).toInt(), PRESS_MAP)

            val r: Float
            // getPointWidthByMap:182-185 stores the raw pp and only then bails on a
            // too-light point — so pp carries forward but speed does not. The bail happens
            // before `setPointSpeed`, leaving a pooled point's -1 in place for the next
            // point to read back.
            var speedX = -1
            if (rawPp < MIN_DRAW_PP) {
                r = 0.5f
            } else {
                var sRate = 128
                var pp = rawPp
                // A null speed map is how the firmware turns the speed term off — the
                // pencil is defined by passing null here, leaving sRate at its 128 base
                // (getPointWidthByMap:186-193). It is not an oversight to guard.
                if (havePrev && speedMap != null) {
                    // Squared distance, deliberately: the firmware never takes the root, so
                    // its "speed" grows quadratically with the gap.
                    val dx = (x - prevX).toLong()
                    val dy = (y - prevY).toLong()
                    val dist = dx * dx + dy * dy
                    var speed2 = (dist * SPEED_NUMERATOR / (gapMs + prevGapMs)).toInt()
                    if (speed2 > MAX_POINT_SPEED) speed2 = MAX_POINT_SPEED

                    // getSpeedRateForPoint2:266-272. The smoothed value is what gets
                    // stored, so this is a recursive filter, and it is never re-clamped.
                    speedX = if (havePrev2) {
                        ((speed2 * 3) + (prevSpeed * 3) + (prev2Speed * 2)) shr 3
                    } else {
                        (speed2 + prevSpeed) shr 1
                    }
                    sRate = mapValue(speedX, speedMap)

                    // getPointPPWithPrev2:169-177. Smoothing reads the *raw* pp of the
                    // previous points — the engine stores the unsmoothed value and only
                    // uses the blend locally.
                    pp = if (havePrev2) {
                        ((rawPp * 4) + (prevRawPp * 2) + (prev2RawPp * 2)) shr 3
                    } else {
                        ((rawPp * 5) + (prevRawPp * 3)) shr 3
                    }
                }
                r = mapValue(pp, ppMap) * sRate * stdWidth / WIDTH_RATE
            }

            current = quantise(r)
            out[i] = current

            prev2Speed = prevSpeed; prevSpeed = speedX
            prev2RawPp = prevRawPp; prevRawPp = rawPp
            havePrev2 = havePrev; havePrev = true
            prevX = x; prevY = y; prevGapMs = gapMs
            keptX = x; keptY = y
            skipped = 0
        }
        return out
    }

    /**
     * The three steps between the width formula and the canvas, which between them throw
     * away most of the curve's resolution — this is why matching the engine is mostly a
     * matter of matching the quantisation, not the arithmetic.
     *
     * Returns a value to hand straight to [Paint.strokeWidth], including 0 — which is not
     * "invisible" but Android's hairline, a one-pixel line regardless of transform, and
     * precisely what the engine ends up drawing for a fine pen.
     */
    private fun quantise(r: Float): Float {
        // PWDrawObjectAlgo.getCurPointRadus:452-454 — a floor, applied before the value is ever stored.
        val floored = if (r < 1.5f) 1.0f else r
        // PWInputPoint.setRadus(float):53-55 rounds it into an int field: (int)(0.5f + r).
        val ir = (floored + 0.5f).toInt()
        // PWInputPoint.getFixRadus:61-67, and again in PWRandomDrawPen.drawlineByPoints:
        // 29-35. Below 2 the engine asks for a hairline rather than a thin line.
        return if (ir < 2) 0f else ir.toFloat()
    }

    /**
     * The firmware's table interpolator, `PWPenConfig.getMapArrayValue:93-118`.
     *
     * [map] is a flat key,value,key,value… ladder. Below the first key the first value is
     * returned flat; above the last key the last value is; in between it interpolates
     * linearly with a rounding bias of half the key span. Integer division throughout, and
     * Kotlin truncates toward zero exactly as Java does — which matters, because the speed
     * table descends, so the interpolation term is routinely negative.
     *
     * **Do not "correct" the bias.** It is added unconditionally, so on a descending table
     * it rounds the wrong way and the function does not reproduce its own tabulated values:
     * `mapValue(80, SPEED_WIDTH_MAP)` returns **129**, not the 128 in the table, and every
     * other knot on that table is likewise one high. The ascending pressure tables are
     * exact. This is the firmware's arithmetic, bug and all, and reproducing it is the
     * whole point — "fixing" it silently de-tunes every stroke.
     */
    private fun mapValue(key: Int, map: IntArray): Int {
        if (key <= map[0]) return map[1]
        var i = 2
        while (i < map.size) {
            if (key <= map[i]) {
                val p0 = map[i - 1]
                val span = map[i + 1] - p0
                val dpx = map[i] - map[i - 2]
                return p0 + (((key - map[i - 2]) * span) + (dpx shr 1)) / dpx
            }
            i += 2
        }
        return map[map.size - 1]
    }
}
