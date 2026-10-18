package net.mildhope.nxtes.edit

import net.mildhope.nxtes.data.Page
import net.mildhope.nxtes.data.PdfSource
import org.junit.Assert.assertArrayEquals
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

    /** One character, then a made-up one, in fractions of the PDF page. */
    private val fractions = PageText("a\n", floatArrayOf(0.1f, 0.2f, 0.3f, 0.25f, 0f, 0f, 0f, 0f))

    /** A page showing a 600x800 PDF page turned by [turns]. */
    private fun page(turns: Int) =
        if (turns % 2 == 1) Page("p", 800f, 600f, PdfSource("a", 0), turns = turns) else Page("p", 600f, 800f, PdfSource("a", 0), turns = turns)

    private fun assertBox(expected: FloatArray, text: PageText) = assertArrayEquals(expected, text.boxes.copyOfRange(0, 4), 0.01f)

    @Test
    fun boxesScaleToThePage() {
        assertBox(floatArrayOf(60f, 160f, 180f, 200f), fractions.shownOn(page(0)))
        assertBox(floatArrayOf(120f, 200f, 360f, 250f), fractions.shownOn(Page("p", 1200f, 1000f, PdfSource("a", 0))))
    }

    @Test
    fun boxesTurnWithThePage() {
        assertBox(floatArrayOf(600f, 60f, 640f, 180f), fractions.shownOn(page(1)))
        assertBox(floatArrayOf(420f, 600f, 540f, 640f), fractions.shownOn(page(2)))
        assertBox(floatArrayOf(160f, 420f, 200f, 540f), fractions.shownOn(page(3)))
    }

    @Test
    fun madeUpCharactersStayEmpty() {
        for (turns in 0..3) assertEquals(0, fractions.shownOn(page(turns)).rects(1, 1).size)
    }
}
