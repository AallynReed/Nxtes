package net.mildhope.nxtes.edit

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
}
