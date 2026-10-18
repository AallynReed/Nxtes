package net.mildhope.nxtes.export

import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import net.mildhope.nxtes.data.MarkKind
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.Picture
import net.mildhope.nxtes.data.Shape
import net.mildhope.nxtes.data.ShapeKind
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.data.TextMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExporterTest {
    @Test
    fun pageRangesReadLikePrintDialogs() {
        assertEquals(listOf(0, 1, 2, 4), parsePages("1-3, 5", 10))
        assertEquals(listOf(4, 0), parsePages("5,1,5", 10))
        assertEquals(listOf(1, 2), parsePages("2–3", 10))
        assertNull(parsePages("0", 10))
        assertNull(parsePages("3-11", 10))
        assertNull(parsePages("4-2", 10))
        assertNull(parsePages("one", 10))
        assertNull(parsePages(" ", 10))
    }

    /** Applies [m] by hand; PDFBox's own transformPoint returns an Android type that's a stub in these tests. */
    private fun apply(m: com.tom_roush.pdfbox.util.Matrix, x: Float, y: Float) =
        (m.scaleX * x + m.shearX * y + m.translateX) to (m.shearY * x + m.scaleY * y + m.translateY)

    @Test
    fun overlaysLandOnTurnedPagesCornerToCorner() {
        val crop = PDRectangle(10f, 20f, 100f, 200f)
        val turned = overlayToPage(crop, 90, 200f, 100f)

        val (lx, ly) = apply(turned, 0f, 100f)
        assertEquals(10f, lx, 0.01f)
        assertEquals(20f, ly, 0.01f)
        val (rx, ry) = apply(turned, 200f, 0f)
        assertEquals(110f, rx, 0.01f)
        assertEquals(220f, ry, 0.01f)

        val (ux, uy) = apply(overlayToPage(crop, 0, 50f, 100f), 50f, 100f)
        assertEquals(110f, ux, 0.01f)
        assertEquals(220f, uy, 0.01f)
    }

    @Test
    fun exportOptionsFilterWhatsIncluded() {
        val content = PageContent(
            objects = listOf(
                Shape("s", ShapeKind.LINE, floatArrayOf(0f, 1f), floatArrayOf(0f, 1f), 0, 1f),
                TextBox("t", "words", 0f, 0f, 10f, 10f),
                Picture("p", "blob", 5f, 5f, 10f, 10f),
            ),
            marks = listOf(TextMark("m", MarkKind.HIGHLIGHT, 0, floatArrayOf(0f, 0f, 1f, 1f), "x")),
        )

        val textOnly = content.forExport(ExportOptions(handwriting = false, images = false))
        assertEquals(listOf("t"), textOnly.objects.map { it.id })
        assertEquals(0, textOnly.marks.size)
    }
}
