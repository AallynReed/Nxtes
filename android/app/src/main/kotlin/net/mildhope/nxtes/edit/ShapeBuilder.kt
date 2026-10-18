package net.mildhope.nxtes.edit

import net.mildhope.nxtes.data.LineStyle
import net.mildhope.nxtes.data.Shape
import net.mildhope.nxtes.data.ShapeKind
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Builds the shape a shape pen draws while dragged, in page points. */
object ShapeBuilder {
    /**
     * The shape for a drag from [x0],[y0] to [x1],[y1]. [path] holds every point dragged through,
     * used by curves. [free] recognises what was drawn, falling back to a curve.
     */
    fun build(
        id: String,
        kind: ShapeKind?,
        sides: Int,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        path: Pair<FloatArray, FloatArray>,
        color: Int,
        width: Float,
        fill: Int,
        line: LineStyle,
    ): Shape? {
        if (hypot(x1 - x0, y1 - y0) < 1f && kind != ShapeKind.CURVE && kind != null) return null
        val box = Box(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
        fun shape(k: ShapeKind, xs: FloatArray, ys: FloatArray) = Shape(id, k, xs, ys, color, width, if (k.closed) fill else 0, line)
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> shape(kind, floatArrayOf(x0, x1), floatArrayOf(y0, y1))
            ShapeKind.RECTANGLE, ShapeKind.ELLIPSE -> shape(
                kind,
                floatArrayOf(box.left, box.right, box.right, box.left),
                floatArrayOf(box.top, box.top, box.bottom, box.bottom),
            )
            ShapeKind.TRIANGLE -> shape(kind, floatArrayOf(box.centerX, box.right, box.left), floatArrayOf(box.top, box.bottom, box.bottom))
            ShapeKind.POLYGON -> ShapeRecognizer.regularPolygon(box, sides).let { (xs, ys) -> shape(kind, xs, ys) }
            ShapeKind.CURVE -> if (path.first.size < 2) null else simplify(path).let { (xs, ys) -> shape(kind, xs, ys) }
            null -> {
                if (path.first.size < 2) return null
                val found = ShapeRecognizer.recognize(path.first, path.second, ShapeKind.entries.toSet())
                if (found != null) shape(found.kind, found.xs, found.ys) else simplify(path).let { (xs, ys) -> shape(ShapeKind.CURVE, xs, ys) }
            }
        }
    }

    /** Keeps every point at least two points from the last one kept, and the end. */
    private fun simplify(path: Pair<FloatArray, FloatArray>): Pair<FloatArray, FloatArray> {
        val (xs, ys) = path
        val keep = mutableListOf(0)
        for (i in 1 until xs.size) {
            val last = keep.last()
            if (abs(xs[i] - xs[last]) + abs(ys[i] - ys[last]) > 2f) keep += i
        }
        if (keep.last() != xs.lastIndex) keep += xs.lastIndex
        return keep.map { xs[it] }.toFloatArray() to keep.map { ys[it] }.toFloatArray()
    }
}
