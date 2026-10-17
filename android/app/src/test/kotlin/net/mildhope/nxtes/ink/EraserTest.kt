package net.mildhope.nxtes.ink

import androidx.compose.ui.geometry.Offset
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.InputTool
import net.mildhope.nxtes.data.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EraserTest {
    /** A horizontal line from x=0 to x=100 at y=0, with points every 50 pt. */
    private val line = InkStroke(
        id = "line", pen = Pen.BALLPOINT, color = 0, size = 2f, tool = InputTool.STYLUS, noiseSeed = 0, created = 5,
        x = floatArrayOf(0f, 50f, 100f), y = floatArrayOf(0f, 0f, 0f), t = intArrayOf(0, 10, 20),
        pressure = floatArrayOf(0.5f, 0.5f, 0.5f),
    )
    private var ids = 0
    private fun cut(from: Offset, to: Offset, radius: Float = 5f) = cutOut(line, from, to, radius) { "piece${ids++}" }

    @Test
    fun cuttingTheMiddleLeavesTwoPieces() {
        val pieces = cut(Offset(50f, -20f), Offset(50f, 20f))

        assertEquals(2, pieces.size)
        assertTrue(pieces[0].x.max() < 50f - 5f)
        assertTrue(pieces[1].x.min() > 50f + 5f)
        assertEquals(100f, pieces[1].x.last())
        assertEquals(pieces[1].pointCount, pieces[1].pressure!!.size)
        assertTrue(pieces.all { it.created == 5L && it.pen == Pen.BALLPOINT })
    }

    @Test
    fun cuttingTheEndLeavesOnePiece() {
        val pieces = cut(Offset(100f, -20f), Offset(100f, 20f))

        assertEquals(1, pieces.size)
        assertEquals(0f, pieces[0].x.first())
    }

    @Test
    fun missingTheStrokeKeepsItUnchanged() {
        assertSame(line, cut(Offset(50f, 30f), Offset(60f, 30f)).single())
    }

    @Test
    fun coveringTheWholeStrokeRemovesIt() {
        assertTrue(cut(Offset(-10f, 0f), Offset(110f, 0f)).isEmpty())
    }

    @Test
    fun interpolatedTimesStayInOrder() {
        val piece = cut(Offset(50f, -20f), Offset(50f, 20f)).first()

        assertTrue(piece.t.toList().zipWithNext().all { (a, b) -> a <= b })
    }
}
