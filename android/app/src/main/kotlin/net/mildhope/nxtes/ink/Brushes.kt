package net.mildhope.nxtes.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import net.mildhope.nxtes.data.Pen
import java.util.Collections
import java.util.WeakHashMap

/**
 * Brushes for each pen, using stock families pinned to a version so saved strokes keep rendering
 * the same way. Jetpack Ink 1.0 has no public textured pencil yet, so the pencil uses the pressure
 * pen for now; pencil strokes keep their pen type and will pick up a texture once one is available.
 */
object Brushes {
    /** Smallest distinguishable distance, in page points. */
    private const val EPSILON = 0.05f

    private val families: Map<Pen, BrushFamily> by lazy {
        val pressurePen = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        mapOf(
            Pen.BALLPOINT to StockBrushes.marker(StockBrushes.MarkerVersion.V1),
            Pen.FOUNTAIN to pressurePen,
            Pen.PENCIL to pressurePen,
            Pen.MARKER to StockBrushes.highlighter(SelfOverlap.ACCUMULATE, StockBrushes.HighlighterVersion.V1),
            Pen.HIGHLIGHTER to StockBrushes.highlighter(SelfOverlap.DISCARD, StockBrushes.HighlighterVersion.V1),
        )
    }

    /** Which pen made each brush, so finished strokes can be saved with their pen type. */
    private val pens = Collections.synchronizedMap(WeakHashMap<Brush, Pen>())

    fun brush(pen: Pen, color: Int, size: Float): Brush =
        Brush.createWithColorIntArgb(families.getValue(pen), color, size, EPSILON).also { pens[it] = pen }

    fun penOf(brush: Brush): Pen =
        pens[brush] ?: families.entries.firstOrNull { it.value === brush.family }?.key ?: Pen.BALLPOINT
}
