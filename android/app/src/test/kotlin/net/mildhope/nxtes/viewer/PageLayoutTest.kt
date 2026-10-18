package net.mildhope.nxtes.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import net.mildhope.nxtes.data.Page
import org.junit.Assert.assertEquals
import org.junit.Test

class PageLayoutTest {
    private val pages = List(5) { Page("p$it", 100f, 200f) }
    private val view = Size(1000f, 800f)

    @Test
    fun twoPagesPairUpWithTheCoverAloneOrNot() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), spreads(5, PageFlow(twoPage = true)))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), spreads(5, PageFlow(twoPage = true, coverAlone = false)))
    }

    @Test
    fun scrollingDownFitsTheWidthAndStacksPages() {
        val layout = PageLayout(pages, view, 10f)

        assertEquals(9.8f, layout.scale, 0.001f)
        assertEquals(10f, layout.rects[0].top, 0.001f)
        assertEquals(layout.rects[0].bottom + 10f, layout.rects[1].top, 0.001f)
        assertEquals(1000f, layout.contentWidth, 0.001f)
    }

    @Test
    fun scrollingAcrossFitsTheHeightAndLinesPagesUp() {
        val layout = PageLayout(pages, view, 10f, PageFlow(axis = ScrollAxis.HORIZONTAL))

        assertEquals(3.9f, layout.scale, 0.001f)
        assertEquals(layout.rects[0].right + 10f, layout.rects[1].left, 0.001f)
        assertEquals(800f, layout.contentHeight, 0.001f)
    }

    @Test
    fun aPageAtATimeGivesEachSpreadAWholeSlot() {
        val layout = PageLayout(pages, view, 10f, PageFlow(axis = ScrollAxis.HORIZONTAL, continuous = false, twoPage = true))

        assertEquals(1000f, layout.slotSize, 0.001f)
        assertEquals(3000f, layout.contentWidth, 0.001f)
        assertEquals(2, layout.spreadOf[3])
        assertEquals(1500f, (layout.rects[1].right + layout.rects[2].left) / 2, 0.001f)
        assertEquals(3, layout.pageAt(Offset(layout.rects[3].center.x, layout.rects[3].center.y) / layout.scale))
    }
}
