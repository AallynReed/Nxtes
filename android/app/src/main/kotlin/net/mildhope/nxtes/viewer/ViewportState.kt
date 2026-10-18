package net.mildhope.nxtes.viewer

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.mildhope.nxtes.data.Page
import kotlin.math.abs

/**
 * Page rectangles at zoom 1, in pixels, with pages stacked vertically and fitted to the width.
 * World coordinates are these pixels divided by [scale], so they're in PDF points.
 */
class PageLayout(pages: List<Page>, viewportWidth: Float, gap: Float) {
    val rects: List<Rect>
    val contentHeight: Float

    /** Pixels per point at zoom 1. */
    val scale: Float = (viewportWidth - 2 * gap).coerceAtLeast(1f) / (pages.maxOfOrNull { it.width } ?: 1f)

    init {
        var y = gap
        rects = pages.map { page ->
            val w = page.width * scale
            val h = page.height * scale
            Rect((viewportWidth - w) / 2, y, (viewportWidth + w) / 2, y + h).also { y += h + gap }
        }
        contentHeight = y
    }

    /** The page's top-left corner in world coordinates. */
    fun origin(index: Int): Offset = rects[index].topLeft / scale

    /** The page containing the world point, or the nearest page above or below it. */
    fun pageAt(world: Offset): Int {
        val y = world.y * scale
        val index = rects.indexOfFirst { y < it.bottom }
        return if (index < 0) rects.lastIndex else index
    }

    /** Pages overlapping the viewport, widened by [extra] pages on each side. */
    fun visible(zoom: Float, offset: Offset, viewportHeight: Float, extra: Int = 0): IntRange {
        val top = -offset.y / zoom
        val bottom = (viewportHeight - offset.y) / zoom
        val first = rects.indexOfFirst { it.bottom >= top }
        val last = rects.indexOfLast { it.top <= bottom }
        if (first < 0 || last < first) return IntRange.EMPTY
        return (first - extra).coerceAtLeast(0)..(last + extra).coerceAtMost(rects.lastIndex)
    }
}

fun Rect.toScreen(zoom: Float, offset: Offset) =
    Rect(left * zoom + offset.x, top * zoom + offset.y, right * zoom + offset.x, bottom * zoom + offset.y)

/** Zoom and scroll position of the page canvas. Offset is where content point (0, 0) sits on screen. */
@Stable
class ViewportState {
    var zoom by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var size by mutableStateOf(Size.Zero)
        private set
    private var contentHeight = 0f

    /** Page rectangles at zoom 1, kept here so other UI can jump to a page. */
    var pageRects by mutableStateOf<List<Rect>>(emptyList())
        private set

    /** The page under the middle of the screen. */
    val currentPage: Int by derivedStateOf {
        val centre = (size.height / 2 - offset.y) / zoom
        pageRects.indexOfFirst { it.bottom >= centre }.coerceAtLeast(0)
    }

    private var pendingPage: Int? = null

    /** The page under the middle of the screen and that point on it, in page points. */
    fun visibleCenter(pages: List<Page>): Pair<Int, Offset> {
        val index = currentPage.coerceIn(pages.indices)
        val rect = pageRects.getOrNull(index) ?: return index to Offset(pages[index].width / 2, pages[index].height / 2)
        val centre = (Offset(size.width / 2, size.height / 2) - offset) / zoom
        val scale = rect.width / pages[index].width
        return index to Offset(
            ((centre.x - rect.left) / scale).coerceIn(0f, pages[index].width),
            ((centre.y - rect.top) / scale).coerceIn(0f, pages[index].height),
        )
    }

    /** Scrolls to the page, or does so once the layout is known. */
    fun goToPage(index: Int) {
        if (pageRects.isEmpty()) {
            pendingPage = index
            return
        }
        val rect = pageRects.getOrNull(index.coerceIn(pageRects.indices)) ?: return
        offset = clamp(Offset(offset.x, -(rect.top - PAGE_TOP_MARGIN) * zoom), zoom)
    }

    /** Applies a new viewport and layout while keeping the same part of the document on screen. */
    fun updateLayout(newSize: Size, newContentHeight: Float, rects: List<Rect>) {
        pageRects = rects
        val fx = if (size.width > 0) -offset.x / (size.width * zoom) else 0f
        val fy = if (contentHeight > 0) -offset.y / (contentHeight * zoom) else 0f
        size = newSize
        contentHeight = newContentHeight
        offset = clamp(Offset(-fx * newSize.width * zoom, -fy * newContentHeight * zoom), zoom)
        if (newSize.height > 0) pendingPage?.let {
            pendingPage = null
            goToPage(it)
        }
    }

    fun transformBy(centroid: Offset, pan: Offset, zoomChange: Float) {
        val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        offset = clamp((offset - centroid) * (newZoom / zoom) + centroid + pan, newZoom)
        zoom = newZoom
    }

    suspend fun fling(velocity: Velocity, decay: DecayAnimationSpec<Float>) = coroutineScope {
        launch { decayAxis(offset.x, velocity.x, decay) { x -> scrollTo(Offset(x, offset.y)).x == x } }
        launch { decayAxis(offset.y, velocity.y, decay) { y -> scrollTo(Offset(offset.x, y)).y == y } }
    }

    private fun scrollTo(target: Offset): Offset = clamp(target, zoom).also { offset = it }

    private fun clamp(target: Offset, zoom: Float): Offset {
        val width = size.width * zoom
        val height = contentHeight * zoom
        val x = if (width <= size.width) (size.width - width) / 2 else target.x.coerceIn(size.width - width, 0f)
        val y = if (height <= size.height) 0f else target.y.coerceIn(size.height - height, 0f)
        return Offset(x, y)
    }

    private suspend fun decayAxis(
        start: Float,
        velocity: Float,
        decay: DecayAnimationSpec<Float>,
        apply: (Float) -> Boolean,
    ) {
        if (abs(velocity) < MIN_FLING_VELOCITY) return
        AnimationState(start, velocity).animateDecay(decay) {
            if (!apply(value)) cancelAnimation()
        }
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
        private const val MIN_FLING_VELOCITY = 50f
        private const val PAGE_TOP_MARGIN = 8f
    }
}
