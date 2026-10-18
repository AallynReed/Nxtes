package net.mildhope.nxtes.viewer

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

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
    private var content = Size.Zero
    private var flow = PageFlow()
    private var slotSize = 0f
    private var spreadOf = IntArray(0)
    private var spreadCount = 0

    /** A page at a time: the spread the view last came to rest on. */
    private var settled = 0

    /** Page rectangles at zoom 1, kept here so other UI can jump to a page. */
    var pageRects by mutableStateOf<List<Rect>>(emptyList())
        private set

    /** The page under the middle of the screen, or the nearest one before it along the scroll direction. */
    val currentPage: Int by derivedStateOf {
        val centre = (Offset(size.width / 2, size.height / 2) - offset) / zoom
        pageRects.indexOfFirst { it.contains(centre) }.takeIf { it >= 0 }
            ?: if (flow.axis == ScrollAxis.VERTICAL) {
                pageRects.indexOfFirst { it.bottom >= centre.y }.coerceAtLeast(0)
            } else {
                pageRects.indexOfFirst { it.right >= centre.x }.coerceAtLeast(0)
            }
    }

    private var pendingPage: Int? = null

    /** The page last jumped to, until the user scrolls away; a new layout returns to it. */
    private var anchor: Int? = null

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

    /**
     * Scrolls to the page, or does so once the layout is known. With [focus] (a point as fractions
     * of the page) that point comes to a third of the way into the view instead of the page's start.
     */
    fun goToPage(index: Int, focus: Offset? = null) {
        if (pageRects.isEmpty() || size.height <= 0f) {
            pendingPage = index
            return
        }
        if (focus != null && slotSize == 0f) {
            val r = pageRects[index.coerceIn(pageRects.indices)]
            anchor = index
            offset = clamp(
                if (flow.axis == ScrollAxis.VERTICAL) Offset(offset.x, -(r.top + focus.y * r.height) * zoom + size.height / 3)
                else Offset(-(r.left + focus.x * r.width) * zoom + size.width / 3, offset.y),
                zoom,
            )
            return
        }
        val i = index.coerceIn(pageRects.indices)
        anchor = i
        val rect = pageRects[i]
        val vertical = flow.axis == ScrollAxis.VERTICAL
        if (slotSize > 0f) settled = spreadOf[i]
        val along = if (slotSize > 0f) spreadOf[i] * slotSize else (if (vertical) rect.top else rect.left) - PAGE_MARGIN
        offset = clamp(if (vertical) Offset(offset.x, -along * zoom) else Offset(-along * zoom, offset.y), zoom)
    }

    /**
     * Applies a new view size and layout. A new page flow keeps the current page on screen; otherwise
     * the same part of the document stays in view.
     */
    fun updateLayout(newSize: Size, layout: PageLayout) {
        val keep = anchor ?: currentPage
        val newFlow = layout.flow != flow && pageRects.isNotEmpty()
        val fx = if (content.width > 0) -offset.x / (content.width * zoom) else 0f
        val fy = if (content.height > 0) -offset.y / (content.height * zoom) else 0f
        pageRects = layout.rects
        flow = layout.flow
        slotSize = layout.slotSize
        spreadOf = layout.spreadOf
        spreadCount = layout.spreads.size
        size = newSize
        content = Size(layout.contentWidth, layout.contentHeight)
        if (newFlow) {
            zoom = 1f
            pendingPage = pendingPage ?: keep
        } else {
            offset = clamp(Offset(-fx * content.width * zoom, -fy * content.height * zoom), zoom)
        }
        if (newSize.height > 0) pendingPage?.let {
            pendingPage = null
            goToPage(it)
        }
    }

    fun transformBy(centroid: Offset, pan: Offset, zoomChange: Float) {
        anchor = null
        val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        offset = clamp((offset - centroid) * (newZoom / zoom) + centroid + pan, newZoom)
        zoom = newZoom
    }

    /** A mouse wheel turn: scrolls along the pages' direction. */
    fun wheel(delta: Float) {
        anchor = null
        val pan = if (flow.axis == ScrollAxis.VERTICAL) Offset(0f, -delta) else Offset(-delta, 0f)
        offset = clamp(offset + pan, zoom)
    }

    /** Coasts after a drag, or a page at a time settles on the spread the drag was heading for. */
    suspend fun fling(velocity: Velocity, decay: DecayAnimationSpec<Float>) {
        if (slotSize > 0f && zoom < SNAP_ZOOM) return snap(velocity)
        coroutineScope {
            launch { decayAxis(offset.x, velocity.x, decay) { x -> scrollTo(Offset(x, offset.y)).x == x } }
            launch { decayAxis(offset.y, velocity.y, decay) { y -> scrollTo(Offset(offset.x, y)).y == y } }
        }
    }

    private suspend fun snap(velocity: Velocity) {
        val vertical = flow.axis == ScrollAxis.VERTICAL
        val speed = if (vertical) velocity.y else velocity.x
        val start = if (vertical) offset.y else offset.x
        val position = -start / zoom / slotSize
        val target = when {
            speed < -SNAP_VELOCITY -> ceil(position)
            speed > SNAP_VELOCITY -> floor(position)
            position - settled > SNAP_DISTANCE -> ceil(position)
            settled - position > SNAP_DISTANCE -> floor(position)
            else -> round(position)
        }.coerceIn(0f, (spreadCount - 1).coerceAtLeast(0).toFloat())
        settled = target.toInt()
        AnimationState(start).animateTo(-target * slotSize * zoom, tween(SNAP_MS)) {
            offset = clamp(if (vertical) Offset(offset.x, value) else Offset(value, offset.y), zoom)
        }
    }

    private fun scrollTo(target: Offset): Offset = clamp(target, zoom).also { offset = it }

    private fun clamp(target: Offset, zoom: Float): Offset {
        val width = content.width * zoom
        val height = content.height * zoom
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
        private const val PAGE_MARGIN = 8f
        private const val SNAP_ZOOM = 1.05f
        private const val SNAP_VELOCITY = 300f

        /** The share of a page a slow drag must cover to turn it. */
        private const val SNAP_DISTANCE = 0.15f
        private const val SNAP_MS = 220
    }
}
