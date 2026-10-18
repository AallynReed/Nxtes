package net.mildhope.nxtes.edit

import net.mildhope.nxtes.data.Picture
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The whole image's frame behind a cropped picture, in page points. */
data class ImageFrame(val cx: Float, val cy: Float, val width: Float, val height: Float, val rotation: Float)

/** Crop arithmetic for pictures; fractions are of the whole image, 0 to 1. */
object Crop {
    private const val MIN_SIZE = 0.02f

    fun frame(p: Picture): ImageFrame {
        val width = p.width / (p.cropRight - p.cropLeft)
        val height = p.height / (p.cropBottom - p.cropTop)
        val (dx, dy) = turn(((p.cropLeft + p.cropRight) / 2 - 0.5f) * width, ((p.cropTop + p.cropBottom) / 2 - 0.5f) * height, p.rotation)
        return ImageFrame(p.cx - dx, p.cy - dy, width, height, p.rotation)
    }

    /** The picture showing the image's [left]..[bottom] part, clipped to [outline] (fractions of that part). */
    fun apply(p: Picture, left: Float, top: Float, right: Float, bottom: Float, outline: List<Float> = emptyList()): Picture {
        val l = left.coerceIn(0f, 1f - MIN_SIZE)
        val t = top.coerceIn(0f, 1f - MIN_SIZE)
        val r = right.coerceIn(l + MIN_SIZE, 1f)
        val b = bottom.coerceIn(t + MIN_SIZE, 1f)
        val f = frame(p)
        val (dx, dy) = turn(((l + r) / 2 - 0.5f) * f.width, ((t + b) / 2 - 0.5f) * f.height, p.rotation)
        return p.copy(
            cx = f.cx + dx, cy = f.cy + dy, width = (r - l) * f.width, height = (b - t) * f.height,
            cropLeft = l, cropTop = t, cropRight = r, cropBottom = b, outline = outline,
        )
    }

    /** Crops to a freeform path given as x, y image fractions: the box around it, clipped to the path. */
    fun freeform(p: Picture, path: List<Float>): Picture {
        if (path.size < 6) return p
        val xs = path.filterIndexed { i, _ -> i % 2 == 0 }.map { it.coerceIn(0f, 1f) }
        val ys = path.filterIndexed { i, _ -> i % 2 == 1 }.map { it.coerceIn(0f, 1f) }
        val l = xs.min()
        val t = ys.min()
        val r = max(xs.max(), l + MIN_SIZE)
        val b = max(ys.max(), t + MIN_SIZE)
        val outline = xs.indices.flatMap { listOf((xs[it] - l) / (r - l), (ys[it] - t) / (b - t)) }
        return apply(p, l, t, min(r, 1f), min(b, 1f), outline)
    }

    private fun turn(x: Float, y: Float, degrees: Float): Pair<Float, Float> {
        val r = Math.toRadians(degrees.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return (x * c - y * s) to (x * s + y * c)
    }
}
