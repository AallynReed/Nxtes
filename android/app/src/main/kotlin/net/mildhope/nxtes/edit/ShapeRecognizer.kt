package net.mildhope.nxtes.edit

import net.mildhope.nxtes.data.ShapeKind
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A recognised shape's kind and corners, in the same coordinates as the drawing. */
class Recognized(val kind: ShapeKind, val xs: FloatArray, val ys: FloatArray)

/** Turns a hand-drawn line into the shape it looks like, if it's one of the [enabled] kinds. */
object ShapeRecognizer {
    private const val CLOSED_GAP = 0.22f
    private const val LINE_TOLERANCE = 0.05f
    private const val CORNER_TOLERANCE = 0.09f
    private const val ROUNDNESS = 0.08f
    private const val RIGHT_ANGLE_SLACK = 22.0

    fun recognize(xs: FloatArray, ys: FloatArray, enabled: Set<ShapeKind>): Recognized? {
        if (xs.size < 3) return null
        val box = Box.of(xs, ys)
        val diagonal = hypot(box.width, box.height)
        if (diagonal < 4f) return null
        val gap = hypot(xs.last() - xs.first(), ys.last() - ys.first())
        if (gap > CLOSED_GAP * diagonal) {
            if (ShapeKind.LINE !in enabled) return null
            val chord = gap
            val worst = xs.indices.maxOf { distanceToSegment(xs[it], ys[it], xs.first(), ys.first(), xs.last(), ys.last()) }
            return if (worst < max(LINE_TOLERANCE * chord, 2f)) Recognized(ShapeKind.LINE, floatArrayOf(xs.first(), xs.last()), floatArrayOf(ys.first(), ys.last())) else null
        }

        val (cx, cy) = simplifyClosed(xs, ys, CORNER_TOLERANCE * diagonal)
        if (roundness(xs, ys, box) < ROUNDNESS && (cx.size > 4 || ShapeKind.RECTANGLE !in enabled && ShapeKind.TRIANGLE !in enabled)) {
            if (ShapeKind.ELLIPSE in enabled) return ellipse(box)
        }
        return when (cx.size) {
            3 -> if (ShapeKind.TRIANGLE in enabled) Recognized(ShapeKind.TRIANGLE, cx, cy) else null
            4 -> rectangle(cx, cy)?.takeIf { ShapeKind.RECTANGLE in enabled }
                ?: if (ShapeKind.POLYGON in enabled) Recognized(ShapeKind.POLYGON, cx, cy) else null
            in 5..8 -> if (ShapeKind.POLYGON in enabled) Recognized(ShapeKind.POLYGON, cx, cy) else null
            else -> if (ShapeKind.ELLIPSE in enabled && roundness(xs, ys, box) < ROUNDNESS * 1.5f) ellipse(box) else null
        }
    }

    /** The corners of a regular polygon with [sides] inscribed in the box, starting at the top. */
    fun regularPolygon(box: Box, sides: Int): Pair<FloatArray, FloatArray> {
        val n = sides.coerceIn(3, 24)
        val angles = (0 until n).map { -Math.PI / 2 + 2 * Math.PI * it / n }
        return FloatArray(n) { (box.centerX + cos(angles[it]) * box.width / 2).toFloat() } to
            FloatArray(n) { (box.centerY + sin(angles[it]) * box.height / 2).toFloat() }
    }

    private fun ellipse(box: Box) = Recognized(
        ShapeKind.ELLIPSE,
        floatArrayOf(box.left, box.right, box.right, box.left),
        floatArrayOf(box.top, box.top, box.bottom, box.bottom),
    )

    /** How far the points stray from the ellipse filling their box: the RMS of normalised radius minus one. */
    private fun roundness(xs: FloatArray, ys: FloatArray, box: Box): Float {
        val a = box.width / 2
        val b = box.height / 2
        if (a < 1f || b < 1f) return Float.MAX_VALUE
        val radii = xs.indices.map { sqrt(((xs[it] - box.centerX) / a).let { x -> x * x } + ((ys[it] - box.centerY) / b).let { y -> y * y }) }
        return sqrt(radii.sumOf { ((it - 1) * (it - 1)).toDouble() } / radii.size).toFloat()
    }

    /** A rectangle squared up from four corners when their angles are close to right angles. */
    private fun rectangle(xs: FloatArray, ys: FloatArray): Recognized? {
        for (i in 0 until 4) {
            val p = (i + 3) % 4
            val n = (i + 1) % 4
            val angle = angleAt(xs[p], ys[p], xs[i], ys[i], xs[n], ys[n])
            if (abs(angle - 90.0) > RIGHT_ANGLE_SLACK) return null
        }
        val theta = atan2((ys[1] - ys[0]).toDouble(), (xs[1] - xs[0]).toDouble())
        val c = cos(-theta)
        val s = sin(-theta)
        val rx = xs.indices.map { xs[it] * c - ys[it] * s }
        val ry = xs.indices.map { xs[it] * s + ys[it] * c }
        val corners = listOf(rx.min() to ry.min(), rx.max() to ry.min(), rx.max() to ry.max(), rx.min() to ry.max())
        val back = corners.map { (x, y) -> (x * cos(theta) - y * sin(theta)) to (x * sin(theta) + y * cos(theta)) }
        return Recognized(ShapeKind.RECTANGLE, back.map { it.first.toFloat() }.toFloatArray(), back.map { it.second.toFloat() }.toFloatArray())
    }

    private fun angleAt(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Double {
        val ux = (ax - bx).toDouble()
        val uy = (ay - by).toDouble()
        val vx = (cx - bx).toDouble()
        val vy = (cy - by).toDouble()
        val cosine = (ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy)).coerceAtLeast(1e-6)
        return Math.toDegrees(acos(cosine.coerceIn(-1.0, 1.0)))
    }

    /** Corners of a closed line, by Douglas–Peucker from its farthest-apart points. */
    private fun simplifyClosed(xs: FloatArray, ys: FloatArray, epsilon: Float): Pair<FloatArray, FloatArray> {
        val start = 0
        val far = xs.indices.maxBy { hypot(xs[it] - xs[start], ys[it] - ys[start]) }
        val first = douglasPeucker(xs, ys, start, far, epsilon)
        val second = douglasPeucker(xs, ys, far, xs.lastIndex, epsilon)
        val indices = (first + second.drop(1)).dropLast(1).toMutableList()
        // Merge corners closer together than the tolerance.
        val merged = mutableListOf<Int>()
        for (i in indices) {
            if (merged.isEmpty() || hypot(xs[i] - xs[merged.last()], ys[i] - ys[merged.last()]) > epsilon) merged += i
        }
        if (merged.size > 2 && hypot(xs[merged.first()] - xs[merged.last()], ys[merged.first()] - ys[merged.last()]) <= epsilon) merged.removeAt(merged.lastIndex)
        // Drop corners where the line hardly bends.
        val corners = merged.filterIndexed { k, i ->
            val p = merged[(k + merged.size - 1) % merged.size]
            val n = merged[(k + 1) % merged.size]
            merged.size <= 3 || angleAt(xs[p], ys[p], xs[i], ys[i], xs[n], ys[n]) < 160.0
        }
        return corners.map { xs[it] }.toFloatArray() to corners.map { ys[it] }.toFloatArray()
    }

    private fun douglasPeucker(xs: FloatArray, ys: FloatArray, from: Int, to: Int, epsilon: Float): List<Int> {
        if (to <= from + 1) return listOf(from, to)
        var worst = -1f
        var index = from
        for (i in from + 1 until to) {
            val d = distanceToSegment(xs[i], ys[i], xs[from], ys[from], xs[to], ys[to])
            if (d > worst) {
                worst = d
                index = i
            }
        }
        return if (worst > epsilon) douglasPeucker(xs, ys, from, index, epsilon).dropLast(1) + douglasPeucker(xs, ys, index, to, epsilon) else listOf(from, to)
    }

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val f = if (lengthSquared == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(px - (ax + f * dx), py - (ay + f * dy))
    }
}
