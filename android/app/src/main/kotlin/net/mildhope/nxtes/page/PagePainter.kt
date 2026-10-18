package net.mildhope.nxtes.page

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withClip
import androidx.core.graphics.withMatrix
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.LineStyle
import net.mildhope.nxtes.data.Mask
import net.mildhope.nxtes.data.MaskKind
import net.mildhope.nxtes.data.MarkKind
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.data.Picture
import net.mildhope.nxtes.data.Shape
import net.mildhope.nxtes.data.ShapeKind
import net.mildhope.nxtes.data.StickyNote
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.data.TextMark
import net.mildhope.nxtes.ink.StrokeGeometry
import java.util.WeakHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Images and fonts a page draws with. */
interface PageResources {
    /** The picture's bitmap, or null while it loads. */
    fun bitmap(blob: String): Bitmap?

    fun typeface(font: String): Typeface
}

/** Draws a page's content in layers; see [PageContent]. Highlights multiply, so the page shows through. */
class PagePainter {
    private val renderer = CanvasStrokeRenderer.create()
    private val multiply = Paint().apply { blendMode = BlendMode.MULTIPLY }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * [pageToCanvas] maps page points to canvas pixels. Masks whose ids are in [revealed] are drawn
     * see-through.
     */
    fun draw(
        canvas: Canvas,
        content: PageContent,
        pageToCanvas: Matrix,
        clip: RectF,
        resources: PageResources,
        revealed: Set<String> = emptySet(),
    ) {
        if (content.isEmpty) return
        canvas.withClip(clip) {
            if (content.marks.any { it.kind == MarkKind.HIGHLIGHT }) {
                saveLayer(clip, multiply)
                withMatrix(pageToCanvas) { content.marks.filter { it.kind == MarkKind.HIGHLIGHT }.forEach { drawMark(this, it) } }
                restore()
            }
            withMatrix(pageToCanvas) {
                content.marks.filter { it.kind != MarkKind.HIGHLIGHT }.forEach { drawMark(this, it) }
                content.objects.forEach {
                    when (it) {
                        is Shape -> drawShape(this, it)
                        is TextBox -> drawText(this, it, resources)
                        is Picture -> drawPicture(this, it, resources)
                    }
                }
            }
            if (content.strokes.any { it.pen == Pen.HIGHLIGHTER }) {
                saveLayer(clip, multiply)
                drawStrokes(this, content.strokes, pageToCanvas) { it.pen == Pen.HIGHLIGHTER }
                restore()
            }
            drawStrokes(this, content.strokes, pageToCanvas) { it.pen != Pen.HIGHLIGHTER }
            withMatrix(pageToCanvas) {
                content.notes.forEach { drawNote(this, it) }
                content.masks.forEach { drawMask(this, it, it.id in revealed) }
            }
        }
    }

    private fun drawStrokes(canvas: Canvas, strokes: List<InkStroke>, pageToCanvas: Matrix, include: (InkStroke) -> Boolean) {
        canvas.withMatrix(pageToCanvas) {
            strokes.forEach { if (include(it)) renderer.draw(this, StrokeGeometry.of(it), pageToCanvas) }
        }
    }

    fun drawShape(canvas: Canvas, shape: Shape) {
        val path = shapePath(shape)
        if (shape.fill != 0 && shape.kind.closed) {
            fill.color = shape.fill
            canvas.drawPath(path, fill)
        }
        line.color = shape.color
        line.strokeWidth = shape.width
        line.pathEffect = when (shape.line) {
            LineStyle.SOLID -> null
            LineStyle.DASHED -> DashPathEffect(floatArrayOf(shape.width * 3, shape.width * 2.5f), 0f)
            LineStyle.DOTTED -> DashPathEffect(floatArrayOf(0.01f, shape.width * 2.2f), 0f)
        }
        canvas.drawPath(path, line)
        line.pathEffect = null
        if (shape.kind == ShapeKind.ARROW && shape.xs.size >= 2) {
            canvas.drawPath(arrowHead(shape.xs[0], shape.ys[0], shape.xs[1], shape.ys[1], shape.width), line)
        }
    }

    private fun drawText(canvas: Canvas, box: TextBox, resources: PageResources) {
        val layout = textLayout(box, resources)
        canvas.withRotation(box.rotation, box.centerX, box.centerY) {
            if (box.background != 0) {
                fill.color = box.background
                drawRect(box.x, box.y, box.x + box.width, box.y + box.height, fill)
            }
            withTranslation(box.x, box.y) { layout.draw(this) }
        }
    }

    private fun drawPicture(canvas: Canvas, picture: Picture, resources: PageResources) {
        val dst = RectF(picture.cx - picture.width / 2, picture.cy - picture.height / 2, picture.cx + picture.width / 2, picture.cy + picture.height / 2)
        canvas.withRotation(picture.rotation, picture.cx, picture.cy) {
            if (picture.outline.size >= 6) clipPath(outlinePath(picture, dst))
            val bitmap = resources.bitmap(picture.blob)
            if (bitmap == null) {
                fill.color = PLACEHOLDER
                drawRect(dst, fill)
            } else {
                val src = Rect(
                    (picture.cropLeft * bitmap.width).toInt(),
                    (picture.cropTop * bitmap.height).toInt(),
                    (picture.cropRight * bitmap.width).toInt(),
                    (picture.cropBottom * bitmap.height).toInt(),
                )
                drawBitmap(bitmap, src, dst, bitmapPaint)
            }
        }
    }

    private fun drawNote(canvas: Canvas, note: StickyNote) {
        val s = NOTE_SIZE
        val fold = s * 0.3f
        val body = Path().apply {
            moveTo(note.x, note.y)
            lineTo(note.x + s, note.y)
            lineTo(note.x + s, note.y + s - fold)
            lineTo(note.x + s - fold, note.y + s)
            lineTo(note.x, note.y + s)
            close()
        }
        fill.color = note.color
        canvas.drawPath(body, fill)
        fill.color = 0x33000000
        canvas.drawPath(Path().apply {
            moveTo(note.x + s, note.y + s - fold)
            lineTo(note.x + s - fold, note.y + s - fold)
            lineTo(note.x + s - fold, note.y + s)
            close()
        }, fill)
        line.color = 0x66000000
        line.strokeWidth = 1f
        for (i in 1..3) canvas.drawLine(note.x + s * 0.2f, note.y + s * 0.22f * i, note.x + s * 0.68f, note.y + s * 0.22f * i, line)
    }

    private fun drawMask(canvas: Canvas, mask: Mask, revealed: Boolean) {
        val alpha = if (revealed) 0.18f else 1f
        val colour = Color.argb((Color.alpha(mask.color) * alpha).toInt(), Color.red(mask.color), Color.green(mask.color), Color.blue(mask.color))
        when (mask.kind) {
            MaskKind.TAPE -> {
                val path = polygon(mask.xs, mask.ys)
                fill.color = colour
                canvas.drawPath(path, fill)
                if (revealed) {
                    line.color = mask.color
                    line.strokeWidth = 1f
                    line.pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
                    canvas.drawPath(path, line)
                    line.pathEffect = null
                }
            }
            MaskKind.PEN -> {
                line.color = colour
                line.strokeWidth = mask.width
                canvas.drawPath(polyline(mask.xs, mask.ys), line)
            }
        }
    }

    private fun drawMark(canvas: Canvas, mark: TextMark) {
        for (i in mark.rects.indices step 4) {
            val x = mark.rects[i]
            val y = mark.rects[i + 1]
            val w = mark.rects[i + 2]
            val h = mark.rects[i + 3]
            when (mark.kind) {
                MarkKind.HIGHLIGHT -> {
                    fill.color = mark.color
                    canvas.drawRect(x, y, x + w, y + h, fill)
                }
                MarkKind.UNDERLINE, MarkKind.STRIKEOUT -> {
                    line.color = mark.color
                    line.strokeWidth = max(0.8f, h * 0.08f)
                    val at = if (mark.kind == MarkKind.UNDERLINE) y + h - line.strokeWidth / 2 else y + h * 0.55f
                    canvas.drawLine(x, at, x + w, at, line)
                }
            }
        }
    }

    companion object {
        private val layouts = WeakHashMap<TextBox, StaticLayout>()

        /** The laid-out text of [box]; its height is what the box needs. */
        fun textLayout(box: TextBox, resources: PageResources): StaticLayout = synchronized(layouts) {
            layouts.getOrPut(box) {
                val style = (if (box.bold) Typeface.BOLD else 0) or (if (box.italic) Typeface.ITALIC else 0)
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
                    textSize = box.size
                    color = box.color
                    typeface = Typeface.create(resources.typeface(box.font), style)
                    isUnderlineText = box.underline
                }
                StaticLayout.Builder.obtain(box.text, 0, box.text.length, paint, box.width.toInt().coerceAtLeast(1))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build()
            }
        }

        /** Sticky note icons are this many points across. */
        const val NOTE_SIZE = 22f
        private const val PLACEHOLDER = 0xFFE6E6E6.toInt()

        fun shapePath(shape: Shape): Path {
            val xs = shape.xs
            val ys = shape.ys
            return when (shape.kind) {
                ShapeKind.LINE, ShapeKind.ARROW -> polyline(xs, ys)
                ShapeKind.RECTANGLE, ShapeKind.TRIANGLE, ShapeKind.POLYGON -> polygon(xs, ys)
                ShapeKind.CURVE -> smooth(xs, ys)
                ShapeKind.ELLIPSE -> if (xs.size < 4) polygon(xs, ys) else Path().apply {
                    addOval(RectF(-1f, -1f, 1f, 1f), Path.Direction.CW)
                    val cx = (xs[0] + xs[2]) / 2
                    val cy = (ys[0] + ys[2]) / 2
                    val ux = (xs[1] - xs[0]) / 2
                    val uy = (ys[1] - ys[0]) / 2
                    val vx = (xs[3] - xs[0]) / 2
                    val vy = (ys[3] - ys[0]) / 2
                    transform(Matrix().apply { setValues(floatArrayOf(ux, vx, cx, uy, vy, cy, 0f, 0f, 1f)) })
                }
            }
        }

        fun polygon(xs: FloatArray, ys: FloatArray) = polyline(xs, ys).apply { if (xs.size > 2) close() }

        fun polyline(xs: FloatArray, ys: FloatArray) = Path().apply {
            if (xs.isEmpty()) return@apply
            moveTo(xs[0], ys[0])
            for (i in 1 until xs.size) lineTo(xs[i], ys[i])
            if (xs.size == 1) lineTo(xs[0] + 0.01f, ys[0])
        }

        /** A smooth curve through the points' midpoints. */
        fun smooth(xs: FloatArray, ys: FloatArray) = Path().apply {
            if (xs.size < 3) return polyline(xs, ys)
            moveTo(xs[0], ys[0])
            for (i in 1 until xs.size - 1) quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2, (ys[i] + ys[i + 1]) / 2)
            lineTo(xs.last(), ys.last())
        }

        fun arrowHead(x0: Float, y0: Float, x1: Float, y1: Float, width: Float): Path {
            val angle = atan2(y1 - y0, x1 - x0)
            val length = max(8f, width * 4f)
            val spread = Math.toRadians(28.0).toFloat()
            return Path().apply {
                moveTo((x1 - length * cos(angle - spread)), (y1 - length * sin(angle - spread)))
                lineTo(x1, y1)
                lineTo((x1 - length * cos(angle + spread)), (y1 - length * sin(angle + spread)))
            }
        }

        private fun outlinePath(picture: Picture, dst: RectF) = Path().apply {
            val o = picture.outline
            moveTo(dst.left + o[0] * dst.width(), dst.top + o[1] * dst.height())
            for (i in 2 until o.size step 2) lineTo(dst.left + o[i] * dst.width(), dst.top + o[i + 1] * dst.height())
            close()
        }
    }
}
