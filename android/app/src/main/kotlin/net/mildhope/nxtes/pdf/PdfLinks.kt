package net.mildhope.nxtes.pdf

import net.mildhope.nxtes.data.Affine
import net.mildhope.nxtes.data.Link
import net.mildhope.nxtes.data.Page
import net.mildhope.nxtes.data.PdfSource

/** A PDF page's own link annotation, in fractions of the page as it's shown unturned, from its top-left corner. */
data class PdfLink(val left: Float, val top: Float, val right: Float, val bottom: Float, val pdfPage: Int? = null, val url: String? = null)

/**
 * [links] of [page]'s PDF page as page links in its points. Go-to links point at the first page of
 * [pages] showing the target PDF page; those whose target isn't there are left out.
 */
fun pdfPageLinks(page: Page, links: List<PdfLink>, pages: List<Page>): List<Link> {
    val blob = page.pdf?.blob ?: return emptyList()
    val w = page.width
    val h = page.height
    val turn = when (page.turns.mod(4)) {
        1 -> Affine(0f, h, -w, 0f, w, 0f)
        2 -> Affine(-w, 0f, 0f, -h, w, h)
        3 -> Affine(0f, -h, w, 0f, 0f, h)
        else -> Affine(w, 0f, 0f, h, 0f, 0f)
    }
    return links.mapIndexedNotNull { i, link ->
        val target = link.pdfPage?.let { index -> pages.firstOrNull { it.pdf == PdfSource(blob, index) }?.id ?: return@mapIndexedNotNull null }
        val url = link.url?.takeIf { target == null && it.isNotBlank() }
        if (target == null && url == null) return@mapIndexedNotNull null
        Link("pdf-$i", link.left, link.top, link.right - link.left, link.bottom - link.top, target, url).transformed(turn)
    }
}
