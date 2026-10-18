package net.mildhope.nxtes.ink

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.withClip
import androidx.core.graphics.withMatrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.Pen

/** Draws a page's strokes. Highlighters go first and multiply, so text and ink stay on top. */
class InkPainter {
    private val renderer = CanvasStrokeRenderer.create()
    private val multiply = Paint().apply { blendMode = BlendMode.MULTIPLY }

    /**
     * [pageToCanvas] maps page points to canvas pixels. The renderer only uses it to judge
     * rendering quality, so it's also applied to the canvas here.
     */
    fun draw(canvas: Canvas, strokes: List<InkStroke>, pageToCanvas: Matrix, clip: RectF) {
        if (strokes.isEmpty()) return
        canvas.withClip(clip) {
            if (strokes.any { it.pen == Pen.HIGHLIGHTER }) {
                saveLayer(clip, multiply)
                drawStrokes(this, strokes, pageToCanvas) { it.pen == Pen.HIGHLIGHTER }
                restore()
            }
            drawStrokes(this, strokes, pageToCanvas) { it.pen != Pen.HIGHLIGHTER }
        }
    }

    private fun drawStrokes(canvas: Canvas, strokes: List<InkStroke>, pageToCanvas: Matrix, include: (InkStroke) -> Boolean) {
        canvas.withMatrix(pageToCanvas) {
            strokes.forEach { if (include(it)) renderer.draw(this, StrokeGeometry.of(it), pageToCanvas) }
        }
    }
}
