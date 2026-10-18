package net.mildhope.nxtes.search

import net.mildhope.nxtes.data.MarkKind
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.StickyNote
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.data.TextMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchIndexTest {
    @Test
    fun queriesBecomePrefixWordsWithoutPunctuation() {
        assertEquals("\"slum*\" \"upgr*\" \"OR*\"", matchExpression("  slum, upgr\" OR "))
        assertEquals("\"café*\"", matchExpression("café"))
        assertNull(matchExpression(" ** "))
    }

    @Test
    fun searchableTextTakesTextBoxesNotesAndMarks() {
        val content = PageContent(
            objects = listOf(TextBox("t", "Box words", 0f, 0f, 10f, 10f)),
            notes = listOf(StickyNote("n", 0f, 0f, "Note words", 0, 0), StickyNote("e", 0f, 0f, " ", 0, 0)),
            marks = listOf(TextMark("m", MarkKind.HIGHLIGHT, 0, floatArrayOf(0f, 0f, 1f, 1f), "Marked words")),
        )

        assertEquals("Box words\nNote words\nMarked words", searchableText(content))
    }
}
