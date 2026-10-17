package net.mildhope.nxtes.ink

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.brush.Brush
import net.mildhope.nxtes.data.Pen

data class PenPreset(val pen: Pen, val color: Int, val size: Float) {
    val brush: Brush by lazy { Brushes.brush(pen, color, size) }
}

enum class Tool { PEN, ERASER }

/** The active drawing tool and input options. */
@Stable
class ToolState {
    var tool by mutableStateOf(Tool.PEN)
    var pen by mutableStateOf(PenPreset(Pen.BALLPOINT, 0xFF000000.toInt(), 1.2f))
    var eraserMode by mutableStateOf(EraserMode.STROKE)

    /** Eraser radius on screen, so it feels the same at any zoom. */
    var eraserRadiusDp by mutableFloatStateOf(10f)
    var drawWithFinger by mutableStateOf(false)
}
