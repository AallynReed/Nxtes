package net.mildhope.nxtes.viewer

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.createBitmap
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.strokes.StrokeInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Page
import net.mildhope.nxtes.ink.InkPainter
import net.mildhope.nxtes.ink.InkSession
import net.mildhope.nxtes.ink.StrokeGeometry
import net.mildhope.nxtes.ink.Tool
import net.mildhope.nxtes.ink.ToolState
import net.mildhope.nxtes.ink.erase
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
private const val PALM_REJECT_MS = 600L
private const val WHEEL_STEP_PX = 64f

@Composable
fun PageCanvas(
    pages: List<Page>,
    renderer: PageRenderer,
    ink: InkSession,
    tools: ToolState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gap = with(density) { 16.dp.toPx() }
    val eraserRadius = with(density) { tools.eraserRadiusDp.dp.toPx() }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val state = remember(pages) { ViewportState() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(pages, size.width, gap) { PageLayout(pages, size.width.toFloat(), gap) }
    val base = remember(layout) { mutableStateMapOf<Int, ImageBitmap>() }
    val detail = remember(layout) { mutableStateMapOf<Int, Detail>() }
    val painter = remember { InkPainter() }
    val strokeMatrix = remember { android.graphics.Matrix() }
    val strokeClip = remember { RectF() }
    var eraserAt by remember { mutableStateOf<Offset?>(null) }
    var eraseGroup by remember { mutableStateOf<Any?>(null) }
    val eraser by rememberUpdatedState(
        EraserInput(
            onMove = { from, to ->
                eraserAt = to
                val group = eraseGroup ?: Any().also { eraseGroup = it }
                val k = layout.scale * state.zoom
                val worldTo = (to - state.offset) / k
                val index = layout.pageAt(worldTo)
                val origin = layout.origin(index)
                erase(ink, pages[index].id, (from - state.offset) / k - origin, worldTo - origin, eraserRadius / k, tools.eraserMode, group)
            },
            onEnd = {
                eraserAt = null
                eraseGroup = null
            },
        ),
    )

    LaunchedEffect(layout, size) { state.updateLayout(size.toSize(), layout.contentHeight) }

    LaunchedEffect(layout, ink) {
        snapshotFlow { layout.visible(state.zoom, state.offset, state.size.height, extra = 1) }
            .distinctUntilChanged()
            .collect { range -> ink.ensureLoaded(range.map { pages[it].id }) }
    }

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

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { size = it }
            .canvasGestures(state, tools) { eraser },
    ) {
        Canvas(Modifier.fillMaxSize()) {
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
                val strokes = ink.strokes(pages[i].id)
                if (strokes.isNotEmpty()) {
                    val k = layout.scale * zoom
                    strokeMatrix.setScale(k, k)
                    strokeMatrix.postTranslate(screen.left, screen.top)
                    strokeClip.set(screen.left, screen.top, screen.right, screen.bottom)
                    drawIntoCanvas { painter.draw(it.nativeCanvas, strokes, strokeMatrix, strokeClip) }
                }
                drawRect(outline, screen.topLeft, screen.size, style = Stroke(1f))
            }
            eraserAt?.let { drawCircle(outline, eraserRadius, it, style = Stroke(2f)) }
        }
        InkLayer(state, layout, pages, ink, tools)
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

/** Live, low-latency strokes; finished strokes move onto the page they started on. */
@Composable
private fun InkLayer(
    state: ViewportState,
    layout: PageLayout,
    pages: List<Page>,
    ink: InkSession,
    tools: ToolState,
) {
    val toWorld = 1f / (state.zoom * layout.scale)
    val pointerToWorld = Matrix().apply {
        values[Matrix.ScaleX] = toWorld
        values[Matrix.ScaleY] = toWorld
        values[Matrix.TranslateX] = -state.offset.x * toWorld
        values[Matrix.TranslateY] = -state.offset.y * toWorld
    }
    val scratch = remember { StrokeInput() }
    InProgressStrokes(
        defaultBrush = tools.pen.brush,
        pointerEventToWorldTransform = pointerToWorld,
        onStrokesFinished = { strokes ->
            val now = System.currentTimeMillis()
            for (stroke in strokes) {
                if (stroke.inputs.isEmpty()) continue
                stroke.inputs.populate(0, scratch)
                val index = layout.pageAt(Offset(scratch.x, scratch.y))
                if (index < 0) continue
                val origin = layout.origin(index)
                val started = now - stroke.inputs.getDurationMillis()
                ink.add(pages[index].id, StrokeGeometry.fromFinished(stroke, origin.x, origin.y, started))
            }
        },
    )
}

private class EraserInput(val onMove: (from: Offset, to: Offset) -> Unit, val onEnd: () -> Unit)

private class PenTracker {
    var lastSeen = Long.MIN_VALUE / 2
}

private fun PointerType.isPen() = this == PointerType.Stylus || this == PointerType.Eraser

private fun PointerType.draws() = this == PointerType.Stylus || this == PointerType.Mouse

/**
 * Routes input in the initial pass, before the ink layer sees it. Pens are left alone so they draw.
 * Fingers pan and zoom, unless finger drawing is on, when one finger draws and two navigate.
 * Touches right after the pen was near are treated as a resting palm and ignored. The eraser end of
 * a stylus always erases, as does any drawing pointer while the eraser tool is selected.
 */
private fun Modifier.canvasGestures(state: ViewportState, tools: ToolState, eraser: () -> EraserInput) = pointerInput(state, tools) {
    val decay = splineBasedDecay<Float>(this)
    val pointerScope = this
    val pen = PenTracker()
    coroutineScope {
        var fling: Job? = null
        pointerScope.awaitPointerEventScope {
            while (true) {
                val event = nextEvent(pen)
                if (event.type == PointerEventType.Scroll) {
                    val delta = event.changes.first().scrollDelta
                    state.transformBy(Offset.Zero, -delta * WHEEL_STEP_PX, 1f)
                    event.changes.forEach { it.consume() }
                    continue
                }
                val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
                val erasing = tools.tool == Tool.ERASER
                when {
                    down.type == PointerType.Eraser || (erasing && down.type.draws()) -> erase(pen, event, down.id, eraser())
                    down.type.draws() -> Unit
                    down.uptimeMillis - pen.lastSeen < PALM_REJECT_MS -> swallow(pen, event) { it.type == PointerType.Touch }
                    erasing && tools.drawWithFinger -> erase(pen, event, down.id, eraser())
                    else -> {
                        fling?.cancel()
                        val navigation = if (tools.drawWithFinger) secondFingerJoins(pen, event) else event
                        if (navigation != null) {
                            val velocity = navigate(pen, state, navigation)
                            fling = launch { state.fling(velocity, decay) }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.nextEvent(pen: PenTracker): PointerEvent =
    awaitPointerEvent(PointerEventPass.Initial).also { event ->
        event.changes.forEach { if (it.type.isPen()) pen.lastSeen = it.uptimeMillis }
    }

/** Erases along one pointer's path until it lifts. */
private suspend fun AwaitPointerEventScope.erase(pen: PenTracker, first: PointerEvent, pointer: PointerId, eraser: EraserInput) {
    var event = first
    var last: Offset? = null
    while (true) {
        val change = event.changes.firstOrNull { it.id == pointer }
        event.changes.forEach { if (it.id == pointer || it.type == PointerType.Touch) it.consume() }
        if (change == null || !change.pressed) {
            eraser.onEnd()
            return
        }
        eraser.onMove(last ?: change.position, change.position)
        last = change.position
        event = nextEvent(pen)
    }
}

/** Consumes matching pointers until none of them is still pressed. */
private suspend fun AwaitPointerEventScope.swallow(
    pen: PenTracker,
    first: PointerEvent,
    matches: (PointerInputChange) -> Boolean,
) {
    var event = first
    while (true) {
        event.changes.filter(matches).forEach { it.consume() }
        if (event.changes.none { matches(it) && it.pressed }) return
        event = nextEvent(pen)
    }
}

/** Lets one finger draw; returns the event where a second finger joined, or null if it never did. */
private suspend fun AwaitPointerEventScope.secondFingerJoins(pen: PenTracker, first: PointerEvent): PointerEvent? {
    var event = first
    while (true) {
        val touching = event.changes.count { it.type == PointerType.Touch && it.pressed }
        if (touching >= 2) return event
        if (touching == 0) return null
        event = nextEvent(pen)
    }
}

/** Pans and zooms until every finger lifts; returns the release velocity for a fling. */
private suspend fun AwaitPointerEventScope.navigate(pen: PenTracker, state: ViewportState, first: PointerEvent): Velocity {
    val tracker = VelocityTracker()
    var travelled = Offset.Zero
    var event = first
    while (true) {
        val zoomChange = event.calculateZoom()
        val pan = event.calculatePan()
        val centroid = event.calculateCentroid(useCurrent = false)
        if (centroid.isValid() && (zoomChange != 1f || pan != Offset.Zero)) {
            state.transformBy(centroid, pan, zoomChange)
        }
        event.changes.forEach { if (it.type == PointerType.Touch) it.consume() }
        travelled += pan
        event.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, travelled) }
        if (event.changes.none { it.type == PointerType.Touch && it.pressed }) return tracker.calculateVelocity()
        event = nextEvent(pen)
    }
}
