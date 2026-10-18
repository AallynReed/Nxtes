package net.mildhope.nxtes.page

import android.graphics.Canvas
import android.graphics.Paint
import net.mildhope.nxtes.data.Background
import net.mildhope.nxtes.data.Paper
import net.mildhope.nxtes.data.Pattern
import kotlin.math.max

/** Draws generated page backgrounds. The canvas is in page points; sizes below are points too. */
object TemplatePainter {
    private const val LINE = 22f
    private const val LEGAL_LINE = 20f
    private const val GRID = 14.17f
    private const val TOP = 72f
    private const val BOTTOM = 36f
    private const val SIDE = 36f
    private const val GUTTER = 12f
    private const val RULE_WIDTH = 0.6f
    private const val STRUCTURE_WIDTH = 1f
    private const val DOT_RADIUS = 0.9f

    private class Colors(val paper: Int, val rule: Int, val structure: Int, val margin: Int)

    private val colors = mapOf(
        Paper.WHITE to Colors(0xFFFFFFFF.toInt(), 0xFFC5D3E3.toInt(), 0xFF9FB4CC.toInt(), 0xFFE8A3A3.toInt()),
        Paper.YELLOW to Colors(0xFFFDF5D3.toInt(), 0xFFD6CC9E.toInt(), 0xFFB3A877.toInt(), 0xFFE39A8E.toInt()),
        Paper.DARK to Colors(0xFF2A2A2A.toInt(), 0xFF4C4C4C.toInt(), 0xFF6A6A6A.toInt(), 0xFF7A4A4A.toInt()),
    )

    fun paperColor(paper: Paper): Int = colors.getValue(paper).paper

    /** [pxPerPt] keeps hairlines at least a pixel wide when drawn small, as in thumbnails. */
    fun draw(canvas: Canvas, background: Background, width: Float, height: Float, pxPerPt: Float) {
        val c = colors.getValue(background.paper)
        val hairline = 1f / pxPerPt
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.rule; strokeWidth = max(RULE_WIDTH, hairline) }
        val structure = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.structure; strokeWidth = max(STRUCTURE_WIDTH, hairline) }
        val margin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.margin; strokeWidth = max(RULE_WIDTH, hairline) }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c.structure
            strokeCap = Paint.Cap.ROUND
            strokeWidth = max(DOT_RADIUS * 2, hairline * 1.5f)
        }
        canvas.drawRect(0f, 0f, width, height, Paint().apply { color = c.paper })

        fun lines(left: Float, right: Float, top: Float, bottom: Float, step: Float = LINE) {
            var y = top
            while (y <= bottom) {
                canvas.drawLine(left, y, right, y, rule)
                y += step
            }
        }

        fun grid(left: Float, right: Float, top: Float, bottom: Float) {
            var x = left
            while (x <= right) {
                canvas.drawLine(x, top, x, bottom, rule)
                x += GRID
            }
            lines(left, right, top, bottom, GRID)
        }

        fun dots(left: Float, right: Float, top: Float, bottom: Float) {
            val columns = ((right - left) / GRID).toInt() + 1
            val rows = ((bottom - top) / GRID).toInt() + 1
            val points = FloatArray(columns * rows * 2)
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val i = (row * columns + column) * 2
                    points[i] = left + column * GRID
                    points[i + 1] = top + row * GRID
                }
            }
            canvas.drawPoints(points, dot)
        }

        fun column(pattern: Pattern, left: Float, right: Float) {
            when (pattern) {
                Pattern.COLUMNS_LINED -> lines(left, right, TOP, height - BOTTOM)
                Pattern.COLUMNS_DOTTED -> dots(left, right, TOP, height - BOTTOM)
                Pattern.COLUMNS_GRAPH -> grid(left, right, TOP, height - BOTTOM)
                else -> Unit
            }
        }

        when (val pattern = background.pattern) {
            Pattern.PLAIN -> Unit
            Pattern.LINED -> lines(0f, width, TOP, height - BOTTOM)
            Pattern.DOTTED -> dots(SIDE, width - SIDE, SIDE, height - SIDE)
            Pattern.GRAPH -> grid(SIDE, width - SIDE, SIDE, height - SIDE)
            Pattern.LEGAL -> {
                lines(0f, width, TOP, height - BOTTOM, LEGAL_LINE)
                canvas.drawLine(0f, TOP - LEGAL_LINE, width, TOP - LEGAL_LINE, margin)
                canvas.drawLine(SIDE * 2, 0f, SIDE * 2, height, margin)
                canvas.drawLine(SIDE * 2 + 3, 0f, SIDE * 2 + 3, height, margin)
            }
            Pattern.CORNELL -> {
                val summary = height * 0.8f
                val cue = width * 0.3f
                lines(cue, width, TOP + LINE, summary - LINE)
                canvas.drawLine(0f, TOP, width, TOP, structure)
                canvas.drawLine(cue, TOP, cue, summary, structure)
                canvas.drawLine(0f, summary, width, summary, structure)
            }
            Pattern.VERTICAL -> {
                var x = width - SIDE
                while (x >= SIDE) {
                    canvas.drawLine(x, TOP, x, height - BOTTOM, rule)
                    x -= LINE
                }
            }
            Pattern.COLUMNS, Pattern.COLUMNS_LINED, Pattern.COLUMNS_DOTTED, Pattern.COLUMNS_GRAPH -> {
                val middle = width / 2
                column(pattern, SIDE, middle - GUTTER)
                column(pattern, middle + GUTTER, width - SIDE)
                canvas.drawLine(middle, SIDE, middle, height - SIDE, structure)
            }
        }
    }
}
