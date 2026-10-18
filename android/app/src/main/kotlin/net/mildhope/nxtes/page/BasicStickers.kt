package net.mildhope.nxtes.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import kotlin.math.cos
import kotlin.math.sin

/** Stickers Nxtes draws itself, so there's always something to stamp. Drawn in a [size]-square. */
object BasicStickers {
    val ids = listOf("star", "heart", "check", "cross", "exclaim", "question", "smile", "flag", "bulb", "pin")

    fun render(id: String, px: Int): Bitmap = createBitmap(px, px).also { draw(id, Canvas(it), px.toFloat()) }

    fun draw(id: String, canvas: Canvas, size: Float) {
        val s = size
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = s * 0.06f
        }
        fun circle(color: Int) {
            fill.color = color
            canvas.drawCircle(s / 2, s / 2, s * 0.44f, fill)
        }
        fun glyph(text: String, color: Int) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = s * 0.55f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(text, s / 2, s / 2 - (paint.descent() + paint.ascent()) / 2, paint)
        }
        when (id) {
            "star" -> {
                val path = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) s * 0.46f else s * 0.2f
                    val a = -Math.PI / 2 + Math.PI * i / 5
                    val x = (s / 2 + cos(a) * r).toFloat()
                    val y = (s * 0.53f + sin(a) * r).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                fill.color = 0xFFFFC83D.toInt()
                canvas.drawPath(path, fill)
                line.color = 0xFFE09A00.toInt()
                line.strokeWidth = s * 0.03f
                canvas.drawPath(path, line)
            }
            "heart" -> {
                val path = Path().apply {
                    moveTo(s / 2, s * 0.86f)
                    cubicTo(s * 0.05f, s * 0.55f, s * 0.12f, s * 0.12f, s / 2, s * 0.3f)
                    cubicTo(s * 0.88f, s * 0.12f, s * 0.95f, s * 0.55f, s / 2, s * 0.86f)
                    close()
                }
                fill.color = 0xFFE5484D.toInt()
                canvas.drawPath(path, fill)
            }
            "check" -> {
                circle(0xFF2EAD5B.toInt())
                line.color = 0xFFFFFFFF.toInt()
                line.strokeWidth = s * 0.09f
                canvas.drawPath(Path().apply { moveTo(s * 0.28f, s * 0.52f); lineTo(s * 0.44f, s * 0.68f); lineTo(s * 0.73f, s * 0.36f) }, line)
            }
            "cross" -> {
                circle(0xFFE5484D.toInt())
                line.color = 0xFFFFFFFF.toInt()
                line.strokeWidth = s * 0.09f
                canvas.drawLine(s * 0.33f, s * 0.33f, s * 0.67f, s * 0.67f, line)
                canvas.drawLine(s * 0.67f, s * 0.33f, s * 0.33f, s * 0.67f, line)
            }
            "exclaim" -> {
                fill.color = 0xFFF5A524.toInt()
                canvas.drawPath(Path().apply { moveTo(s / 2, s * 0.08f); lineTo(s * 0.95f, s * 0.88f); lineTo(s * 0.05f, s * 0.88f); close() }, fill)
                fill.color = 0xFFFFFFFF.toInt()
                canvas.drawRoundRect(RectF(s * 0.455f, s * 0.36f, s * 0.545f, s * 0.64f), s * 0.04f, s * 0.04f, fill)
                canvas.drawCircle(s / 2, s * 0.75f, s * 0.05f, fill)
            }
            "question" -> {
                circle(0xFF3E7BFA.toInt())
                glyph("?", 0xFFFFFFFF.toInt())
            }
            "smile" -> {
                circle(0xFFFFD43B.toInt())
                fill.color = 0xFF3B2F0B.toInt()
                canvas.drawCircle(s * 0.36f, s * 0.42f, s * 0.05f, fill)
                canvas.drawCircle(s * 0.64f, s * 0.42f, s * 0.05f, fill)
                line.color = 0xFF3B2F0B.toInt()
                line.strokeWidth = s * 0.05f
                canvas.drawArc(RectF(s * 0.3f, s * 0.38f, s * 0.7f, s * 0.72f), 20f, 140f, false, line)
            }
            "flag" -> {
                line.color = 0xFF5F6368.toInt()
                line.strokeWidth = s * 0.05f
                canvas.drawLine(s * 0.26f, s * 0.12f, s * 0.26f, s * 0.9f, line)
                fill.color = 0xFFE5484D.toInt()
                canvas.drawPath(Path().apply {
                    moveTo(s * 0.28f, s * 0.14f)
                    lineTo(s * 0.8f, s * 0.3f)
                    lineTo(s * 0.28f, s * 0.48f)
                    close()
                }, fill)
            }
            "bulb" -> {
                fill.color = 0xFFFFD43B.toInt()
                canvas.drawCircle(s / 2, s * 0.4f, s * 0.28f, fill)
                fill.color = 0xFF9AA0A6.toInt()
                canvas.drawRoundRect(RectF(s * 0.36f, s * 0.64f, s * 0.64f, s * 0.86f), s * 0.05f, s * 0.05f, fill)
            }
            "pin" -> {
                line.color = 0xFF5F6368.toInt()
                line.strokeWidth = s * 0.04f
                canvas.drawLine(s / 2, s * 0.5f, s / 2, s * 0.92f, line)
                fill.color = 0xFFE5484D.toInt()
                canvas.drawCircle(s / 2, s * 0.32f, s * 0.24f, fill)
                fill.color = 0x66FFFFFF
                canvas.drawCircle(s * 0.42f, s * 0.25f, s * 0.07f, fill)
            }
        }
    }
}
