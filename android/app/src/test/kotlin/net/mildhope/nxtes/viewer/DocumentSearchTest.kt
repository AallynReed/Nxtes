package net.mildhope.nxtes.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentSearchTest {
    @Test
    fun matchesIgnoreCaseAndDontOverlap() {
        assertEquals(listOf(0, 9), matches("Slum and slum", "SLUM"))
        assertEquals(listOf(0, 2), matches("aaaa", "aa"))
        assertEquals(emptyList<Int>(), matches("text", " "))
    }

    @Test
    fun snippetsFlattenLinesAndPointAtTheMatch() {
        val text = "first line\n\nsecond   line with the word here"
        val (snip, at) = snippet(text, text.indexOf("word"), 4)

        assertEquals("… line second line with the word here", snip)
        assertEquals("word", snip.substring(at, at + 4))
    }

    @Test
    fun longTextIsCutWithEllipses() {
        val text = "x".repeat(100) + "needle" + "y".repeat(100)
        val (snip, at) = snippet(text, 100, 6)

        assertEquals('…', snip.first())
        assertEquals('…', snip.last())
        assertEquals("needle", snip.substring(at, at + 6))
    }
}
