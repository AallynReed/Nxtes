package net.mildhope.nxtes.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class PageTextTest {
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
    fun wordsRunOverLettersOnly() {
        assertEquals(3..4, text.wordAt(4))
        assertEquals(2..2, text.wordAt(2))
        assertEquals("cd\nef", text.textOf(3, 7))
    }

    @Test
    fun charactersAreFoundUnderOrNearThePoint() {
        assertEquals(1, text.charAt(15f, 6f, 0f))
        assertEquals(7, text.charAt(22f, 26f, 5f))
        assertEquals(-1, text.charAt(80f, 6f, 5f))
    }

    @Test
    fun rectsCoverEachLineOnce() {
        assertEquals(listOf(10f, 0f, 35f, 12f, 0f, 20f, 20f, 12f), text.rects(1, 7).toList())
    }

    @Test
    fun aQuarterTurnMovesBoxesWithThePage() {
        val turned = text.turned(1, 100f, 200f)

        assertEquals(listOf(188f, 0f, 200f, 10f), turned.boxes.copyOfRange(0, 4).toList())
    }
}
