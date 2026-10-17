package net.mildhope.nxtes.ink

import android.util.Log
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.InputTool
import java.util.UUID
import java.util.WeakHashMap

/** Converts between saved strokes and androidx.ink strokes, caching the built geometry. */
object StrokeGeometry {
    private val cache = WeakHashMap<InkStroke, Stroke>()

    fun of(stroke: InkStroke): Stroke = synchronized(cache) { cache.getOrPut(stroke) { build(stroke) } }

    /** Turns a finished stroke in world coordinates into a saved stroke on the page whose corner is at the origin. */
    @OptIn(ExperimentalInkCustomBrushApi::class)
    fun fromFinished(stroke: Stroke, originX: Float, originY: Float, created: Long): InkStroke {
        val inputs = stroke.inputs
        val n = inputs.size
        val scratch = StrokeInput()
        val x = FloatArray(n)
        val y = FloatArray(n)
        val t = IntArray(n)
        val pressure = if (inputs.hasPressure()) FloatArray(n) else null
        val tilt = if (inputs.hasTilt()) FloatArray(n) else null
        val orientation = if (inputs.hasOrientation()) FloatArray(n) else null
        for (i in 0 until n) {
            inputs.populate(i, scratch)
            x[i] = scratch.x - originX
            y[i] = scratch.y - originY
            t[i] = scratch.elapsedTimeMillis.toInt()
            pressure?.set(i, scratch.pressure)
            tilt?.set(i, scratch.tiltRadians)
            orientation?.set(i, scratch.orientationRadians)
        }
        return InkStroke(
            id = UUID.randomUUID().toString(),
            pen = Brushes.penOf(stroke.brush),
            color = stroke.brush.colorIntArgb,
            size = stroke.brush.size,
            tool = toolOf(inputs.getToolType()),
            noiseSeed = inputs.getNoiseSeed(),
            created = created,
            x = x, y = y, t = t,
            pressure = pressure, tilt = tilt, orientation = orientation,
        )
    }

    @OptIn(ExperimentalInkCustomBrushApi::class)
    private fun build(s: InkStroke): Stroke {
        val type = when (s.tool) {
            InputTool.STYLUS -> InputToolType.STYLUS
            InputTool.TOUCH -> InputToolType.TOUCH
            InputTool.MOUSE -> InputToolType.MOUSE
            InputTool.UNKNOWN -> InputToolType.UNKNOWN
        }
        val batch = MutableStrokeInputBatch()
        var skipped = 0
        for (i in 0 until s.pointCount) {
            try {
                batch.add(
                    type, s.x[i], s.y[i], s.t[i].toLong(),
                    pressure = s.pressure?.get(i) ?: StrokeInput.NO_PRESSURE,
                    tiltRadians = s.tilt?.get(i) ?: StrokeInput.NO_TILT,
                    orientationRadians = s.orientation?.get(i) ?: StrokeInput.NO_ORIENTATION,
                )
            } catch (e: IllegalArgumentException) {
                skipped++
            }
        }
        if (skipped > 0) Log.w("Ink", "Skipped $skipped invalid points in stroke ${s.id}")
        batch.setNoiseSeed(s.noiseSeed)
        return Stroke(Brushes.brush(s.pen, s.color, s.size), batch)
    }

    private fun toolOf(type: InputToolType) = when (type) {
        InputToolType.STYLUS -> InputTool.STYLUS
        InputToolType.TOUCH -> InputTool.TOUCH
        InputToolType.MOUSE -> InputTool.MOUSE
        else -> InputTool.UNKNOWN
    }
}
