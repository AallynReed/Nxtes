package net.mildhope.nxtes.viewer

import android.graphics.Bitmap
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Page
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Draws [width]x[height] of page [index] into [bitmap] at ([left], [top]); offsets may be negative. */
fun interface PageRenderer {
    suspend fun render(index: Int, bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int)
}

/** A crisp render of the visible part of one page at a specific zoom and scroll position. */
private class Detail(val bitmap: ImageBitmap, val zoom: Float, val offset: Offset, val topLeft: Offset)

private const val DETAIL_DELAY_MS = 120L
private const val DETAIL_MIN_ZOOM = 1.05f

@Composable
fun PageCanvas(pages: List<Page>, renderer: PageRenderer, modifier: Modifier = Modifier) {
    val gap = with(LocalDensity.current) { 16.dp.toPx() }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val state = remember(pages) { ViewportState() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(pages, size.width, gap) { PageLayout(pages, size.width.toFloat(), gap) }
    val base = remember(layout) { mutableStateMapOf<Int, ImageBitmap>() }
    val detail = remember(layout) { mutableStateMapOf<Int, Detail>() }

    LaunchedEffect(layout, size) { state.updateLayout(size.toSize(), layout.contentHeight) }

    LaunchedEffect(layout) {
        snapshotFlow { layout.visible(state.zoom, state.offset, state.size.height, extra = 1) }
            .distinctUntilChanged()
            .collectLatest { range ->
                base.keys.filter { it !in range }.forEach { base.remove(it) }
                for (i in range) {
                    if (i in base) continue
                    val rect = layout.rects[i]
                    val w = rect.width.roundToInt().coerceAtLeast(1)
                    val h = rect.height.roundToInt().coerceAtLeast(1)
                    val bitmap = createBitmap(w, h)
                    renderer.render(i, bitmap, 0, 0, w, h)
                    base[i] = bitmap.asImageBitmap()
                }
            }
    }

    LaunchedEffect(layout) {
        snapshotFlow { Triple(state.zoom, state.offset, state.size) }.collectLatest { (zoom, offset, viewportSize) ->
            if (zoom < DETAIL_MIN_ZOOM) {
                detail.clear()
                return@collectLatest
            }
            delay(DETAIL_DELAY_MS)
            val viewport = Rect(Offset.Zero, viewportSize)
            val fresh = HashMap<Int, Detail>()
            for (i in layout.visible(zoom, offset, viewportSize.height)) {
                val screen = layout.rects[i].toScreen(zoom, offset)
                val shown = screen.intersect(viewport)
                if (shown.isEmpty) continue
                val left = floor(shown.left)
                val top = floor(shown.top)
                val w = (ceil(shown.right) - left).toInt().coerceAtLeast(1)
                val h = (ceil(shown.bottom) - top).toInt().coerceAtLeast(1)
                val bitmap = createBitmap(w, h)
                renderer.render(
                    i, bitmap,
                    (screen.left - left).roundToInt(), (screen.top - top).roundToInt(),
                    screen.width.roundToInt(), screen.height.roundToInt(),
                )
                fresh[i] = Detail(bitmap.asImageBitmap(), zoom, offset, Offset(left, top))
            }
            detail.clear()
            detail.putAll(fresh)
        }
    }

    val currentPage by remember(layout) {
        derivedStateOf {
            val centre = (state.size.height / 2 - state.offset.y) / state.zoom
            layout.rects.indexOfFirst { it.bottom >= centre }.coerceAtLeast(0)
        }
    }

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { size = it }
                .viewportGestures(state),
        ) {
            val zoom = state.zoom
            val offset = state.offset
            val viewport = Rect(Offset.Zero, this.size)
            layout.rects.forEachIndexed { i, rect ->
                val screen = rect.toScreen(zoom, offset)
                if (!screen.overlaps(viewport)) return@forEachIndexed
                drawRect(Color.White, screen.topLeft, screen.size)
                base[i]?.let {
                    drawImage(
                        it,
                        dstOffset = IntOffset(screen.left.roundToInt(), screen.top.roundToInt()),
                        dstSize = IntSize(screen.width.roundToInt(), screen.height.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
                detail[i]?.takeIf { it.zoom == zoom }?.let { d ->
                    drawImage(d.bitmap, topLeft = d.topLeft + (offset - d.offset))
                }
                drawRect(outline, screen.topLeft, screen.size, style = Stroke(1f))
            }
        }
        if (pages.size > 1) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.page_of, currentPage + 1, pages.size),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** One- or two-finger pan, pinch zoom around the fingers, and fling. */
private fun Modifier.viewportGestures(state: ViewportState): Modifier = pointerInput(state) {
    val decay = splineBasedDecay<Float>(this)
    val pointerScope = this
    coroutineScope {
        var fling: Job? = null
        pointerScope.awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            fling?.cancel()
            val tracker = VelocityTracker()
            var travelled = Offset.Zero
            do {
                val event = awaitPointerEvent()
                val zoomChange = event.calculateZoom()
                val pan = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = false)
                if (centroid.isValid() && (zoomChange != 1f || pan != Offset.Zero)) {
                    state.transformBy(centroid, pan, zoomChange)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                travelled += pan
                event.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, travelled) }
            } while (event.changes.any { it.pressed })
            val velocity = tracker.calculateVelocity()
            fling = launch { state.fling(velocity, decay) }
        }
    }
}
