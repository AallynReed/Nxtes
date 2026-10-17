package net.mildhope.nxtes.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import net.mildhope.nxtes.data.Pen

/**
 * Each pen gets its own family instance, so a finished stroke's pen can be recovered from its
 * brush. Stock families are pinned to a version so saved strokes keep rendering the same way.
 */
object Brushes {
    /** Smallest distinguishable distance, in page points. */
    private const val EPSILON = 0.05f

    private val families: Map<Pen, BrushFamily> by lazy {
        mapOf(
            Pen.BALLPOINT to StockBrushes.marker(StockBrushes.MarkerVersion.V1),
            Pen.FOUNTAIN to StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1),
            Pen.PENCIL to StockBrushes.pencilUnstable,
            Pen.MARKER to StockBrushes.highlighter(SelfOverlap.ACCUMULATE, StockBrushes.HighlighterVersion.V1),
            Pen.HIGHLIGHTER to StockBrushes.highlighter(SelfOverlap.DISCARD, StockBrushes.HighlighterVersion.V1),
        )
    }

    fun brush(pen: Pen, color: Int, size: Float): Brush =
        Brush.createWithColorIntArgb(families.getValue(pen), color, size, EPSILON)

    fun penOf(brush: Brush): Pen = families.entries.firstOrNull { it.value === brush.family }?.key ?: Pen.BALLPOINT
}
