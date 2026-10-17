package net.mildhope.nxtes.ink

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.withClip
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.Pen

/** Draws a page's strokes. Highlighters go first and multiply, so text and ink stay on top. */
class InkPainter {
    private val renderer = CanvasStrokeRenderer.create()
    private val multiply = Paint().apply { blendMode = BlendMode.MULTIPLY }

    fun draw(canvas: Canvas, strokes: List<InkStroke>, pageToCanvas: Matrix, clip: RectF) {
        if (strokes.isEmpty()) return
        canvas.withClip(clip) {
            if (strokes.any { it.pen == Pen.HIGHLIGHTER }) {
                saveLayer(clip, multiply)
                strokes.forEach { if (it.pen == Pen.HIGHLIGHTER) renderer.draw(this, StrokeGeometry.of(it), pageToCanvas) }
                restore()
            }
            strokes.forEach { if (it.pen != Pen.HIGHLIGHTER) renderer.draw(this, StrokeGeometry.of(it), pageToCanvas) }
        }
    }
}
