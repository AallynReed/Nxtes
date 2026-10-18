package net.mildhope.nxtes.ink

import androidx.compose.ui.geometry.Offset
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import net.mildhope.nxtes.data.InkStroke
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.hypot

enum class EraserMode { STROKE, PARTIAL }

/** Erases along the segment [from]-[to] (page points) with the given radius. */
fun erase(ink: PageSession, pageId: String, from: Offset, to: Offset, radius: Float, mode: EraserMode, group: Any) {
    val strokes = ink.strokes(pageId)
    if (strokes.isEmpty()) return
    val area = ImmutableParallelogram.fromSegmentAndPadding(
        ImmutableSegment(ImmutableVec(from.x, from.y), ImmutableVec(to.x, to.y)),
        radius,
    )
    val hits = strokes.filter { area.intersects(StrokeGeometry.of(it).shape, AffineTransform.IDENTITY) }.toSet()
    if (hits.isEmpty()) return
    val result = when (mode) {
        EraserMode.STROKE -> strokes.filter { it !in hits }
        EraserMode.PARTIAL -> strokes.flatMap { if (it in hits) cutOut(it, from, to, radius) else listOf(it) }
    }
    ink.replace(pageId, result, group)
}

/**
 * Removes the parts of [stroke] that come within [radius] of the segment, counting the stroke's own
 * half-width. Returns the pieces that remain, or the stroke itself if nothing was touched.
 */
fun cutOut(stroke: InkStroke, from: Offset, to: Offset, radius: Float, newId: () -> String = { UUID.randomUUID().toString() }): List<InkStroke> {
    val reach = radius + stroke.size / 2
    val points = densify(stroke, step = (radius / 2).coerceAtLeast(0.1f))
    val keep = BooleanArray(points.size) { distanceToSegment(points.x[it], points.y[it], from, to) > reach }
    if (keep.all { it }) return listOf(stroke)

    val pieces = mutableListOf<InkStroke>()
    var start = -1
    for (i in 0..points.size) {
        val kept = i < points.size && keep[i]
        if (kept && start < 0) start = i
        if (!kept && start >= 0) {
            if (i - start >= 2) pieces += points.slice(stroke, start, i, newId())
            start = -1
        }
    }
    return pieces
}

/** Point channels of a stroke, with extra points interpolated so no gap is longer than [step]. */
private class Points(
    val x: FloatArray,
    val y: FloatArray,
    val t: IntArray,
    val pressure: FloatArray?,
    val tilt: FloatArray?,
    val orientation: FloatArray?,
) {
    val size get() = x.size

    fun slice(source: InkStroke, from: Int, until: Int, id: String) = InkStroke(
        id = id,
        pen = source.pen,
        color = source.color,
        size = source.size,
        tool = source.tool,
        noiseSeed = source.noiseSeed,
        created = source.created,
        x = x.copyOfRange(from, until),
        y = y.copyOfRange(from, until),
        t = t.copyOfRange(from, until),
        pressure = pressure?.copyOfRange(from, until),
        tilt = tilt?.copyOfRange(from, until),
        orientation = orientation?.copyOfRange(from, until),
    )
}

private fun densify(s: InkStroke, step: Float): Points {
    val x = ArrayList<Float>(); val y = ArrayList<Float>(); val t = ArrayList<Int>()
    val p = s.pressure?.let { ArrayList<Float>() }
    val tilt = s.tilt?.let { ArrayList<Float>() }
    val orientation = s.orientation?.let { ArrayList<Float>() }
    fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    for (i in 0 until s.pointCount) {
        if (i > 0) {
            val extra = ceil(hypot(s.x[i] - s.x[i - 1], s.y[i] - s.y[i - 1]) / step).toInt() - 1
            for (k in 1..extra) {
                val f = k / (extra + 1f)
                x += lerp(s.x[i - 1], s.x[i], f)
                y += lerp(s.y[i - 1], s.y[i], f)
                t += lerp(s.t[i - 1].toFloat(), s.t[i].toFloat(), f).toInt()
                p?.add(lerp(s.pressure!![i - 1], s.pressure[i], f))
                tilt?.add(lerp(s.tilt!![i - 1], s.tilt[i], f))
                orientation?.add(lerp(s.orientation!![i - 1], s.orientation[i], f))
            }
        }
        x += s.x[i]; y += s.y[i]; t += s.t[i]
        p?.add(s.pressure!![i]); tilt?.add(s.tilt!![i]); orientation?.add(s.orientation!![i])
    }
    return Points(
        x.toFloatArray(), y.toFloatArray(), t.toIntArray(),
        p?.toFloatArray(), tilt?.toFloatArray(), orientation?.toFloatArray(),
    )
}

private fun distanceToSegment(px: Float, py: Float, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lengthSquared = dx * dx + dy * dy
    val f = if (lengthSquared == 0f) 0f else (((px - a.x) * dx + (py - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    return hypot(px - (a.x + f * dx), py - (a.y + f * dy))
}
