package com.superduper.notes.doc

/**
 * The firmware's pen styles, and the width tables behind each one.
 *
 * These are the engine's own nibs, selected with `setDrawObjectType`. They are worth using
 * rather than inventing our own because the engine renders the live stroke — whatever it
 * does, we have to match when re-drawing, so the cheapest correct answer is to use its
 * styles and port its tables.
 *
 * Every style routes through the same `PWPenConfig.getPointWidthByMap` that [TchRaster]
 * already ports; only the tables differ. `PWPenConfig.getPenAdjustRadius:280-300` is the
 * dispatch:
 *
 * ```
 * objType 18            -> stdWidth verbatim              (constant)
 * objType 0,19,16,22    -> PenPenConfig    (pressure + speed)
 * objType 15,21         -> BrushPenConfig  (pressure + speed, wider swing)
 * objType 14,20         -> PencilPenConfig (pressure only — speedMap is null)
 * objType 17,23         -> (int)(stdWidth * 1.2 + 0.5)    (constant)
 * ```
 *
 * Note `objType 16/22` (`RANDOM_BALLPEN`) dispatches to *PenPenConfig*, not to the
 * `BallPenConfig` class that exists beside it — so `BallPenConfig` is dead code in this
 * firmware and a "ball pen" would render exactly like the pen. It is not offered here.
 *
 * Constant names are `android.view.PWDrawObjectHandler`'s own (decompiled from
 * `framework.jar`); the map literals were confirmed against a
 * `jadx --no-replace-consts` pass, so the numbers are the real bytecode contents rather
 * than JADX's symbol substitutions.
 */
enum class PenStyle(
    /** `setDrawObjectType` value — the engine's `DRAW_OBJ_RANDOM_*` constant. */
    val objType: Int,
    /** Pressure → width-rate table, or null for a constant-width nib. */
    val ppMap: IntArray?,
    /** Speed → width-rate table. Null means no speed term at all: sRate stays 128. */
    val speedMap: IntArray?,
    /** For constant-width nibs: width = stdWidth × this. Ignored when [ppMap] is set. */
    val constantScale: Float,
) {
    /**
     * `DRAW_OBJ_RANDOM_PEN`. The default, and the one verified against engine pixels
     * (SPEC.md §6.3a): pressure moves width ~10%, speed ~35%.
     */
    PEN(0, PenTables.PEN_PP, PenTables.PEN_SPEED, 0f),

    /**
     * `DRAW_OBJ_RANDOM_PENCIL`. Pressure only — `PencilPenConfig:14` passes a **null**
     * speed map, so a pencil line does not thin out when you write fast, which is what
     * separates it from the pen. Its pressure table is also heavier throughout
     * (80→300 against the pen's 58→250).
     */
    PENCIL(14, PenTables.PENCIL_PP, null, 0f),

    /**
     * `DRAW_OBJ_RANDOM_BRUSH`. The most expressive nib: its speed table swings 132→~71,
     * roughly half again the pen's range, so strokes taper markedly with speed. The
     * closest thing here to a calligraphic pen.
     */
    BRUSH(15, PenTables.BRUSH_PP, PenTables.BRUSH_SPEED, 0f),

    /**
     * `DRAW_OBJ_RANDOM_MARK`. Constant width, `(int)(stdWidth × 1.2 + 0.5)`
     * (`PWPenConfig.getPointRadusForMarkPen:89-91`) — it ignores the point entirely, so
     * neither pressure nor speed affects it.
     *
     * **Not currently offered in the UI**, because we cannot yet render it faithfully. The
     * engine does not stamp a solid disc: `PWRandomDrawMark.creatMarkBitmap:189-240` walks
     * the disc stepping *both* axes by 2 and calls `drawPoints`, so only 1 pixel in 4 is
     * set — a stipple, in three densities (`lightColor`, `darkColor`, `dark2Color`). On a
     * 16-grey panel that reads as a grey highlighter, not black ink.
     *
     * Re-rendering it as a solid stroke made fresh marker ink look faded and then darken
     * the moment anything triggered a redraw. It can be offered again once [TchRaster]
     * paints the same dither — a 2×2 shader would do it — at which point it becomes a
     * genuine highlighter rather than a heavy pen.
     */
    MARKER(17, null, null, 1.2f),

    /**
     * `DRAW_OBJ_RANDOM_FIXPEN`. Dead constant width — `getPenAdjustRadius:284-286` returns
     * `stdWidth` before it even looks at the point. A technical pen: no pressure response,
     * no speed response, every line identical.
     */
    FIXED(18, null, null, 1f),
    ;

    /** True when width comes from [constantScale] rather than the tables. */
    val isConstantWidth: Boolean get() = ppMap == null

    companion object {
        /** The style stored in a stroke's config record, defaulting to [PEN]. */
        fun fromObjType(objType: Int): PenStyle =
            entries.firstOrNull { it.objType == objType } ?: PEN
    }
}

/**
 * The raw table literals, kept out of [PenStyle] so the enum entries can reference them:
 * an enum constructor argument cannot read the enum's own companion object.
 *
 * Each is a flat key,value,key,value… ladder read by `TchRaster.mapValue`.
 */
internal object PenTables {

    /** `PenPenConfig.PEN_PP_WIDTH_MAP:25`. */
    val PEN_PP = intArrayOf(10, 58, 120, 68, 270, 138, 320, 170, 350, 235, 420, 250)

    /** `PenPenConfig.PEN_SPEED_WIDTH_MAP:27`. */
    val PEN_SPEED = intArrayOf(20, 130, 80, 128, 120, 110, 500, 98, 1200, 90, 4000, 86)

    /** `PencilPenConfig.PENCIL_PP_WIDTH_MAP:8`. Heavier than the pen at every pressure. */
    val PENCIL_PP = intArrayOf(10, 80, 120, 80, 270, 160, 320, 240, 350, 288, 420, 300)

    /** `BrushPenConfig.BRUSH_PP_WIDTH_MAP:8`. */
    val BRUSH_PP = intArrayOf(10, 68, 120, 80, 270, 160, 320, 226, 350, 272, 420, 280)

    /**
     * `BrushPenConfig.BURSH_SPEED_WIDTH_MAP:9` — their spelling, and transcribed verbatim
     * including a defect.
     *
     * The keys are **not monotonic**: 120 appears after 400. `getMapArrayValue` scans the
     * ladder in order and takes the first bucket whose key the value does not exceed, so
     * the `120 → 84` entry is unreachable (any key ≤ 120 is already caught by the ≤ 400
     * bucket), and keys above 400 interpolate from a base of 120 — leaving a small step at
     * the boundary. Given the pen table has 1200 in the same slot, this is almost certainly
     * a missing zero in the firmware.
     *
     * It is reproduced exactly rather than corrected. The engine draws the wet stroke with
     * this table; "fixing" it here would put our re-render permanently out of step with the
     * ink the user watched appear, which is the whole defect §6.3a exists to prevent.
     * Confirmed as the literal bytecode contents via `jadx --no-replace-consts`.
     */
    val BRUSH_SPEED = intArrayOf(20, 132, 50, 130, 100, 108, 400, 90, 120, 84, 4000, 70)
}
