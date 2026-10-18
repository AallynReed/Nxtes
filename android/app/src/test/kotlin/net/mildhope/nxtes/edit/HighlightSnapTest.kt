package net.mildhope.nxtes.edit

import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.InputTool
import net.mildhope.nxtes.data.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightSnapTest {
    /** "ab cd" on one line, a made-up line break, then "ef" on the next line; chars are 10 wide and 12 tall. */
    private val text = PageText(
        "ab cd\nef",
        floatArrayOf(
            0f, 0f, 10f, 12f,
            10f, 0f, 20f, 12f,
            20f, 0f, 25f, 12f,
            25f, 0f, 35f, 12f,
            35f, 0f, 45f, 12f,
            0f, 0f, 0f, 0f,
            0f, 20f, 10f, 32f,
            10f, 20f, 20f, 32f,
        ),
    )

    @Test
    fun strokeAlongALineCoversTheCharactersItCrosses() {
        assertEquals(1..3, snapHighlight(text, floatArrayOf(12f, 30f), floatArrayOf(6f, 7f), 8f))
    }

    @Test
    fun drawnRightToLeftItCoversTheSameText() {
        assertEquals(1..3, snapHighlight(text, floatArrayOf(30f, 12f), floatArrayOf(7f, 6f), 8f))
    }

    @Test
    fun aThickStrokeJustAboveTheLineStillCounts() {
        assertEquals(0..4, snapHighlight(text, floatArrayOf(2f, 44f), floatArrayOf(-3f, -3f), 10f))
    }

    @Test
    fun strokeDownAcrossLinesCoversEverythingBetween() {
        assertEquals(3..7, snapHighlight(text, floatArrayOf(30f, 15f), floatArrayOf(6f, 26f), 6f))
    }

    @Test
    fun strokeMostlyOffTheTextStaysInk() {
        assertNull(snapHighlight(text, floatArrayOf(40f, 200f), floatArrayOf(6f, 6f), 6f))
        assertNull(snapHighlight(text, floatArrayOf(0f, 100f), floatArrayOf(60f, 60f), 6f))
    }

    /** A stroke corner to corner across the box, so its bounds are the box. */
    private fun stroke(left: Float, top: Float, right: Float, bottom: Float, pen: Pen = Pen.BALLPOINT) = InkStroke(
        "$left,$top", pen, 0xFF000000.toInt(), 0f, InputTool.STYLUS, 0, 0L,
        floatArrayOf(left, right), floatArrayOf(top, bottom), intArrayOf(0, 10),
    )

    /** Two lines of handwriting, letters 8 wide and 10 high with a tall one in each word, 30 apart. */
    private val writing = listOf(0f, 30f).flatMap { y ->
        listOf(0f, 50f).flatMap { x ->
            listOf(stroke(x, y, x + 8, y + 10), stroke(x + 10, y - 8, x + 18, y + 10), stroke(x + 20, y, x + 28, y + 10), stroke(x + 30, y, x + 38, y + 10))
        }
    }

    @Test
    fun strokeAlongHandwritingCoversTheLettersItCrosses() {
        assertEquals(listOf(Box(10f, -8f, 88f, 10f)), snapToWriting(writing, floatArrayOf(12f, 85f), floatArrayOf(5f, 6f), 8f))
    }

    @Test
    fun aThickStrokeOverOneLineLeavesTheNextAlone() {
        assertEquals(listOf(Box(0f, 22f, 38f, 40f)), snapToWriting(writing, floatArrayOf(1f, 37f), floatArrayOf(33f, 33f), 30f))
    }

    @Test
    fun aStrokeZigzaggingOverTwoLinesCoversBoth() {
        assertEquals(
            listOf(Box(0f, -8f, 28f, 10f), Box(0f, 22f, 28f, 40f)),
            snapToWriting(writing, floatArrayOf(1f, 27f, 27f, 1f), floatArrayOf(5f, 5f, 35f, 35f), 4f),
        )
    }

    @Test
    fun strokeMostlyOffTheWritingStaysAsDrawn() {
        assertNull(snapToWriting(writing, floatArrayOf(30f, 300f), floatArrayOf(5f, 5f), 8f))
        assertNull(snapToWriting(writing, floatArrayOf(0f, 90f), floatArrayOf(200f, 200f), 8f))
    }

    @Test
    fun otherHighlightsArentWriting() {
        val highlights = listOf(stroke(0f, 0f, 90f, 12f, Pen.HIGHLIGHTER))
        assertNull(snapToWriting(highlights, floatArrayOf(5f, 85f), floatArrayOf(6f, 6f), 8f))
    }

    @Test
    fun straightHighlightsFillTheirBands() {
        val drawn = stroke(0f, 0f, 90f, 6f, Pen.HIGHLIGHTER)
        val straight = straightHighlights(drawn, listOf(Box(10f, -8f, 88f, 12f)), { "new" }).single()
        assertEquals(Pen.HIGHLIGHTER, straight.pen)
        assertEquals(22f, straight.size, 0.001f)
        assertEquals(2f, straight.y[0], 0.001f)
        assertEquals(2f, straight.y[1], 0.001f)
        assertEquals(13.3f, straight.x[0], 0.001f)
        assertEquals(84.7f, straight.x[1], 0.001f)
    }
}
