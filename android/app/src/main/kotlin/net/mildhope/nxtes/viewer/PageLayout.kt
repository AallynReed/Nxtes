package net.mildhope.nxtes.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import net.mildhope.nxtes.data.Page
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Pages shown together: one, or two side by side. */
fun spreads(count: Int, flow: PageFlow): List<List<Int>> {
    if (!flow.twoPage) return List(count) { listOf(it) }
    val out = mutableListOf<List<Int>>()
    var i = 0
    if (flow.coverAlone && count > 0) out += listOf(i++)
    while (i < count) {
        out += if (i + 1 < count) listOf(i, i + 1) else listOf(i)
        i += 2
    }
    return out
}

/**
 * Page rectangles at zoom 1, in pixels, laid out by [flow] in a [viewport]-sized view. Continuous
 * flows fit the pages across the scroll direction; a page at a time fits each spread in the view
 * and gives it a whole view-sized slot. World coordinates are these pixels divided by [scale], so
 * they're in PDF points.
 */
class PageLayout(pages: List<Page>, val viewport: Size, gap: Float, val flow: PageFlow = PageFlow()) {
    val spreads: List<List<Int>> = spreads(pages.size, flow)
    val rects: List<Rect>
    val contentWidth: Float
    val contentHeight: Float

    /** Pixels per point at zoom 1. */
    val scale: Float

    /** A page at a time: the length of each spread's slot along the scroll direction; 0 when continuous. */
    val slotSize: Float

    /** The spread each page is in. */
    val spreadOf: IntArray = IntArray(pages.size)

    init {
        val vertical = flow.axis == ScrollAxis.VERTICAL
        val inner = if (flow.twoPage) gap / 4 else 0f
        val widths = spreads.map { s -> s.sumOf { pages[it].width.toDouble() }.toFloat() }
        val heights = spreads.map { s -> s.maxOf { pages[it].height } }
        val widest = widths.maxOrNull() ?: 1f
        val tallest = heights.maxOrNull() ?: 1f
        val innerTotal = inner * ((spreads.maxOfOrNull { it.size } ?: 1) - 1)
        val fitWidth = max(1f, viewport.width - 2 * gap - innerTotal) / widest
        val fitHeight = max(1f, viewport.height - 2 * gap) / tallest
        scale = when {
            !flow.continuous -> min(fitWidth, fitHeight)
            vertical -> fitWidth
            else -> fitHeight
        }
        slotSize = if (flow.continuous) 0f else if (vertical) viewport.height else viewport.width
        val placed = arrayOfNulls<Rect>(pages.size)
        var cursor = gap
        spreads.forEachIndexed { s, spread ->
            val w = widths[s] * scale + inner * (spread.size - 1)
            val h = heights[s] * scale
            val left = when {
                !vertical && flow.continuous -> cursor
                !vertical -> s * slotSize + (slotSize - w) / 2
                else -> (viewport.width - w) / 2
            }
            val top = when {
                vertical && flow.continuous -> cursor
                vertical -> s * slotSize + (slotSize - h) / 2
                else -> (viewport.height - h) / 2
            }
            var x = left
            spread.forEach { i ->
                val pw = pages[i].width * scale
                val ph = pages[i].height * scale
                val y = top + (h - ph) / 2
                placed[i] = Rect(x, y, x + pw, y + ph)
                spreadOf[i] = s
                x += pw + inner
            }
            cursor += (if (vertical) h else w) + gap
        }
        rects = placed.map { it ?: Rect.Zero }
        contentWidth = when {
            vertical -> viewport.width
            flow.continuous -> cursor
            else -> spreads.size * slotSize
        }
        contentHeight = when {
            !vertical -> viewport.height
            flow.continuous -> cursor
            else -> spreads.size * slotSize
        }
    }

    /** The page's top-left corner in world coordinates. */
    fun origin(index: Int): Offset = rects[index].topLeft / scale

    /** The page containing the world point, or the nearest one. */
    fun pageAt(world: Offset): Int {
        val p = world * scale
        var best = 0
        var bestDistance = Float.MAX_VALUE
        rects.forEachIndexed { i, r ->
            val dx = max(0f, max(r.left - p.x, p.x - r.right))
            val dy = max(0f, max(r.top - p.y, p.y - r.bottom))
            if (dx == 0f && dy == 0f) return i
            val d = hypot(dx, dy)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /** Pages overlapping the view, widened by [extra] pages on each side. */
    fun visible(zoom: Float, offset: Offset, viewport: Size, extra: Int = 0): IntRange {
        val view = Rect(-offset.x / zoom, -offset.y / zoom, (viewport.width - offset.x) / zoom, (viewport.height - offset.y) / zoom)
        val first = rects.indexOfFirst { it.overlaps(view) }
        val last = rects.indexOfLast { it.overlaps(view) }
        if (first < 0 || last < first) return IntRange.EMPTY
        return (first - extra).coerceAtLeast(0)..(last + extra).coerceAtMost(rects.lastIndex)
    }
}
