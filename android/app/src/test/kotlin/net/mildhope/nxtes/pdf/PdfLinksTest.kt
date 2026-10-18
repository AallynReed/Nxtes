package net.mildhope.nxtes.pdf

import net.mildhope.nxtes.data.Link
import net.mildhope.nxtes.data.Page
import net.mildhope.nxtes.data.PdfSource
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfLinksTest {
    private val link = PdfLink(0.1f, 0.2f, 0.3f, 0.25f, url = "https://example.org")

    /** A page showing a 600x800 PDF page turned by [turns]. */
    private fun page(turns: Int) =
        if (turns % 2 == 1) Page("p", 800f, 600f, PdfSource("a", 0), turns = turns) else Page("p", 600f, 800f, PdfSource("a", 0), turns = turns)

    private fun assertRect(x: Float, y: Float, width: Float, height: Float, actual: Link) {
        assertEquals(listOf(x, y, width, height), listOf(actual.x, actual.y, actual.width, actual.height).map { Math.round(it * 100) / 100f })
    }

    @Test
    fun linksScaleToThePage() {
        assertRect(60f, 160f, 120f, 40f, pdfPageLinks(page(0), listOf(link), emptyList()).single())
    }

    @Test
    fun linksTurnWithThePage() {
        assertRect(600f, 60f, 40f, 120f, pdfPageLinks(page(1), listOf(link), emptyList()).single())
        assertRect(420f, 600f, 120f, 40f, pdfPageLinks(page(2), listOf(link), emptyList()).single())
        assertRect(160f, 420f, 40f, 120f, pdfPageLinks(page(3), listOf(link), emptyList()).single())
    }

    @Test
    fun goToLinksPointAtThePageShowingTheTarget() {
        val pages = listOf(
            Page("first", 600f, 800f, PdfSource("a", 0)),
            Page("other", 600f, 800f, PdfSource("b", 1)),
            Page("second", 600f, 800f, PdfSource("a", 1)),
        )
        val links = listOf(
            link.copy(url = null, pdfPage = 1),
            link.copy(url = null, pdfPage = 7),
            link.copy(url = " "),
            link,
        )
        val found = pdfPageLinks(pages[0], links, pages)
        assertEquals(listOf("second" to null, null to "https://example.org"), found.map { it.pageId to it.url })
    }

    @Test
    fun pagesWithoutAPdfHaveNoPdfLinks() {
        assertEquals(emptyList<Link>(), pdfPageLinks(Page("p", 600f, 800f), listOf(link), emptyList()))
    }
}
