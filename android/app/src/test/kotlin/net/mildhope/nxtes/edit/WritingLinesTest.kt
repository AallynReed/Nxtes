package net.mildhope.nxtes.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class WritingLinesTest {
    /** Letters 8 wide with a 10 high body whose top is at [y]: a, l (tall), g (descends), o, i with its dot. */
    private fun word(x: Float, y: Float) = listOf(
        Box(x, y, x + 8, y + 10),
        Box(x + 10, y - 8, x + 18, y + 10),
        Box(x + 20, y, x + 28, y + 18),
        Box(x + 30, y, x + 38, y + 10),
        Box(x + 40, y, x + 42, y + 10),
        Box(x + 40, y - 6, x + 42, y - 4),
    )

    @Test
    fun lettersSortIntoTheirLines() {
        val boxes = word(0f, 0f) + word(0f, 30f) + word(50f, 30f)
        assertEquals(listOf((0..5).toList(), (6..17).toList()), writingLines(boxes).map { it.sorted() })
    }

    @Test
    fun aDotJoinsTheLineItBelongsTo() {
        val boxes = listOf(Box(0f, 0f, 8f, 10f), Box(10f, 0f, 18f, 10f), Box(20f, 0f, 28f, 10f), Box(22f, -7f, 24f, -5f)) +
            listOf(Box(0f, -30f, 8f, -20f), Box(10f, -30f, 18f, -12f), Box(20f, -30f, 28f, -20f))
        assertEquals(listOf(listOf(4, 5, 6), listOf(0, 1, 2, 3)), writingLines(boxes).map { it.sorted() })
    }

    @Test
    fun aLineWrittenOnASlopeStaysOneLine() {
        val boxes = (0 until 12).map { i -> Box(i * 20f, i * 2f, i * 20f + 8, i * 2f + 10) }
        assertEquals(1, writingLines(boxes).size)
    }

    @Test
    fun aBracketBesideTwoLinesDoesntJoinThem() {
        val boxes = word(20f, 0f) + word(20f, 30f) + Box(0f, -5f, 6f, 45f)
        val lines = writingLines(boxes)
        assertEquals(2, lines.size)
        assertEquals(13, lines.sumOf { it.size })
    }
}
