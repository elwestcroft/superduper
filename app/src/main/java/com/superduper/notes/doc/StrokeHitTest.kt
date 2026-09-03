package com.superduper.notes.doc

/**
 * Geometry for erasing and selecting over the app's own stroke model (SPEC.md §6.4).
 *
 * These exist because the app now owns the document. The firmware's erasers are genuinely
 * good — three vector modes, all undoable — but they only see strokes the engine itself
 * recorded, and the engine's buffer is cleared after every pen-up. Owning the document
 * means owning the geometry that operates on it.
 */
object StrokeHitTest {

    /**
     * Squared distance from a point to a line segment.
     *
     * Squared throughout: comparisons against a squared radius give the same answer as
     * real distances without a square root per segment, and an eraser drag tests a lot of
     * segments.
     */
    private fun distSqToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val abx = bx - ax
        val aby = by - ay
        val apx = px - ax
        val apy = py - ay
        val lenSq = abx * abx + aby * aby
        // Degenerate segment (duplicate samples are common at ~480 Hz): treat as a point.
        val t = if (lenSq <= 1e-6f) 0f else ((apx * abx + apy * aby) / lenSq).coerceIn(0f, 1f)
        val dx = apx - abx * t
        val dy = apy - aby * t
        return dx * dx + dy * dy
    }

    /**
     * Which points of [stroke] fall under an eraser dragged along [path]?
     *
     * Returns a mask, one entry per point, true where the eraser covered it. This is what a
     * spot eraser needs: not "did the eraser touch this stroke" but "exactly which parts of
     * it did the eraser cover", so the survivors can be re-formed into shorter strokes.
     *
     * [path] is a flat array of x,y pairs in the same space as the stroke.
     */
    fun coveredPoints(stroke: TchFile.Stroke, path: FloatArray, radius: Float): BooleanArray {
        val pts = stroke.points
        val mask = BooleanArray(pts.size)
        if (path.size < 2) return mask
        val rSq = radius * radius

        // Bounding-box reject: an eraser stroke usually touches very few of the document's
        // strokes, and this keeps the per-point work off the rest.
        var pMinX = Float.MAX_VALUE; var pMaxX = -Float.MAX_VALUE
        var pMinY = Float.MAX_VALUE; var pMaxY = -Float.MAX_VALUE
        var i = 0
        while (i < path.size) {
            if (path[i] < pMinX) pMinX = path[i]
            if (path[i] > pMaxX) pMaxX = path[i]
            if (path[i + 1] < pMinY) pMinY = path[i + 1]
            if (path[i + 1] > pMaxY) pMaxY = path[i + 1]
            i += 2
        }
        if (stroke.maxY < pMinY - radius || stroke.minY > pMaxY + radius ||
            stroke.maxX < pMinX - radius || stroke.minX > pMaxX + radius
        ) return mask

        val n = path.size / 2
        for (k in pts.indices) {
            val px = pts[k].x.toFloat()
            val py = stroke.yOf(pts[k]).toFloat()
            var hit = false
            if (n == 1) {
                val dx = path[0] - px; val dy = path[1] - py
                hit = dx * dx + dy * dy <= rSq
            } else {
                for (j in 1 until n) {
                    if (distSqToSegment(
                            px, py,
                            path[(j - 1) * 2], path[(j - 1) * 2 + 1],
                            path[j * 2], path[j * 2 + 1]
                        ) <= rSq
                    ) { hit = true; break }
                }
            }
            mask[k] = hit
        }
        return mask
    }

    /** True if [stroke] passes within [radius] of the point, in the stroke's own space. */
    fun hits(stroke: TchFile.Stroke, x: Float, y: Float, radius: Float): Boolean {
        val pts = stroke.points
        if (pts.isEmpty()) return false
        val rSq = radius * radius

        // Bounding-box reject first — most strokes are nowhere near the eraser.
        if (y + radius < stroke.minY || y - radius > stroke.maxY) return false

        if (pts.size == 1) {
            val dx = pts[0].x - x
            val dy = stroke.yOf(pts[0]) - y
            return dx * dx + dy * dy <= rSq
        }
        for (i in 1 until pts.size) {
            if (distSqToSegment(
                    x, y,
                    pts[i - 1].x.toFloat(), stroke.yOf(pts[i - 1]).toFloat(),
                    pts[i].x.toFloat(), stroke.yOf(pts[i]).toFloat()
                ) <= rSq
            ) return true
        }
        return false
    }

    /**
     * Is [stroke] enclosed by the lasso polygon [poly] (flat array of x,y pairs)?
     *
     * Uses a winding-number point-in-polygon test, which — unlike even-odd ray casting —
     * behaves sensibly when a hand-drawn lasso crosses itself, which they routinely do.
     *
     * A whole-stroke verdict, gated on [threshold] of the points being inside. This is the
     * right question for **area erase**, which deletes whole strokes: circling something
     * should remove it entirely, and a fraction of the points is a good enough proxy for
     * "the user circled this". It is deliberately *not* what the lasso selection uses —
     * see [insideMask] for why a threshold cannot express a partial selection.
     */
    fun enclosed(stroke: TchFile.Stroke, poly: FloatArray, threshold: Float = 0.8f): Boolean {
        val pts = stroke.points
        if (pts.isEmpty() || poly.size < 6) return false

        // Bounding-box reject before the per-point winding test.
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
        if (stroke.maxY < pMinY || stroke.minY > pMaxY ||
            stroke.maxX < pMinX || stroke.minX > pMaxX
        ) return false

        var inside = 0
        pts.forEach { p ->
            if (windingNonZero(p.x.toFloat(), stroke.yOf(p).toFloat(), poly)) inside++
        }
        return inside >= (pts.size * threshold).toInt().coerceAtLeast(1)
    }

    /**
     * Which of [stroke]'s points the lasso [poly] encloses.
     *
     * The per-point answer, not the whole-stroke verdict [enclosed] gives. A threshold
     * which either rejects a stroke the user plainly circled or grabs one that mostly lies
     * outside — measured on a real document, a stroke 68% inside the lasso was rejected by
     * an 80% threshold while looking entirely selected. This lets the selection be exactly
     * the region drawn.
     *
     * [poly] is in the same space as the stroke.
     */
    fun insideMask(stroke: TchFile.Stroke, poly: FloatArray): BooleanArray {
        val pts = stroke.points
        val mask = BooleanArray(pts.size)
        if (poly.size < 6 || pts.isEmpty()) return mask

        // Bounding-box reject before the per-point winding test — a lasso touches few of
        // the document's strokes, and this keeps the winding work off the rest.
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
        if (stroke.maxY < pMinY || stroke.minY > pMaxY ||
            stroke.maxX < pMinX || stroke.minX > pMaxX
        ) return mask

        pts.indices.forEach { k ->
            mask[k] = windingNonZero(
                pts[k].x.toFloat(), stroke.yOf(pts[k]).toFloat(), poly
            )
        }
        return mask
    }

    /** How many of [stroke]'s points fall inside [poly]. Diagnostic counterpart to [insideMask]. */
    fun insideCount(stroke: TchFile.Stroke, poly: FloatArray): Int {
        var inside = 0
        stroke.points.forEach { p ->
            if (windingNonZero(p.x.toFloat(), stroke.yOf(p).toFloat(), poly)) inside++
        }
        return inside
    }

    /** Non-zero winding rule: counts signed crossings of a ray from the point. */
    private fun windingNonZero(x: Float, y: Float, poly: FloatArray): Boolean {
        var wind = 0
        val n = poly.size / 2
        for (i in 0 until n) {
            val ax = poly[i * 2]; val ay = poly[i * 2 + 1]
            val j = (i + 1) % n
            val bx = poly[j * 2]; val by = poly[j * 2 + 1]
            if (ay <= y) {
                if (by > y && cross(ax, ay, bx, by, x, y) > 0) wind++
            } else {
                if (by <= y && cross(ax, ay, bx, by, x, y) < 0) wind--
            }
        }
        return wind != 0
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float) =
        (bx - ax) * (py - ay) - (px - ax) * (by - ay)

    /**
     * True if [stroke] intersects the capsule swept between two eraser samples.
     *
     * An eraser drag arrives as discrete points; testing only those points would let a
     * fast swipe skip over a thin stroke between samples. Sampling along the segment
     * closes that gap cheaply — the alternative, true capsule-vs-polyline intersection, is
     * more code for no perceptible benefit at this radius.
     */
    fun hitsSweep(
        stroke: TchFile.Stroke,
        x0: Float, y0: Float, x1: Float, y1: Float,
        radius: Float,
    ): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val steps = kotlin.math.max(1, (dist / (radius * 0.75f)).toInt())
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            if (hits(stroke, x0 + dx * t, y0 + dy * t, radius)) return true
        }
        return false
    }
}
