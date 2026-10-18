package net.mildhope.nxtes.viewer

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.createBitmap
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.strokes.StrokeInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Affine
import net.mildhope.nxtes.data.Page
import net.mildhope.nxtes.edit.Geometry
import net.mildhope.nxtes.edit.Handle
import net.mildhope.nxtes.edit.SelectionState
import net.mildhope.nxtes.ink.InputMode
import net.mildhope.nxtes.ink.LassoShape
import net.mildhope.nxtes.ink.PageSession
import net.mildhope.nxtes.ink.StrokeGeometry
import net.mildhope.nxtes.ink.Tool
import net.mildhope.nxtes.ink.ToolState
import net.mildhope.nxtes.ink.style
import net.mildhope.nxtes.ink.erase
import net.mildhope.nxtes.page.PagePainter
import net.mildhope.nxtes.page.PageResources
import androidx.core.graphics.withMatrix
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.data.Shape
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.edit.ShapeBuilder
import net.mildhope.nxtes.edit.ShapeRecognizer
import net.mildhope.nxtes.edit.newTextBox
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Draws [width]x[height] of page [index] into [bitmap] at ([left], [top]); offsets may be negative. */
fun interface PageRenderer {
    suspend fun render(index: Int, bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int)
}

/** Maps between screen pixels and page points for the current layout and viewport. */
class PageMapper(private val layout: PageLayout, private val state: ViewportState, val pages: List<Page>) {
    /** Screen pixels per page point. */
    val pixelsPerPoint get() = layout.scale * state.zoom

    fun pageAt(screen: Offset) = layout.pageAt((screen - state.offset) / pixelsPerPoint)

    fun indexOf(pageId: String?) = pages.indexOfFirst { it.id == pageId }

    fun toPage(index: Int, screen: Offset): Offset = (screen - state.offset) / pixelsPerPoint - layout.origin(index)

    fun toScreen(index: Int, point: Offset): Offset = (point + layout.origin(index)) * pixelsPerPoint + state.offset
}

/** A crisp render of the visible part of one page at a specific zoom and scroll position. */
private class Detail(val bitmap: ImageBitmap, val zoom: Float, val offset: Offset, val topLeft: Offset)

/** The drag in progress, kept across recompositions. */
private class DragState {
    var kind: DragKind? = null
    var last = Offset.Zero
    var group: Any? = null
    var page = 0
    var start = Offset.Zero
    val path = mutableListOf<Offset>()

    /** A shape the next finished ink stroke turns into, from holding the pen still. */
    var convert: Pair<Int, Shape>? = null
}

private const val DETAIL_DELAY_MS = 120L
private const val DETAIL_MIN_ZOOM = 1.05f
private val HANDLE_RADIUS = 9.dp
private val HANDLE_TOUCH = 28.dp
private val ROTATE_DISTANCE = 36.dp

@Composable
fun PageCanvas(
    pages: List<Page>,
    renderer: PageRenderer,
    ink: PageSession,
    resources: PageResources,
    tools: ToolState,
    state: ViewportState,
    selection: SelectionState,
    onPageCounter: () -> Unit,
    modifier: Modifier = Modifier,
    assetsLoaded: () -> Int = { 0 },
    overlay: @Composable BoxScope.(PageMapper) -> Unit = {},
) {
    val density = LocalDensity.current
    val gap = with(density) { 16.dp.toPx() }
    val eraserRadius = with(density) { tools.eraserRadiusDp.dp.toPx() }
    val handleTouch = with(density) { HANDLE_TOUCH.toPx() }
    val rotateDistance = with(density) { ROTATE_DISTANCE.toPx() }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    var size by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(pages, size.width, gap) { PageLayout(pages, size.width.toFloat(), gap) }
    val mapper = PageMapper(layout, state, pages)
    val base = remember(layout) { mutableStateMapOf<Int, ImageBitmap>() }
    val detail = remember(layout) { mutableStateMapOf<Int, Detail>() }
    val painter = remember { PagePainter() }
    val strokeMatrix = remember { android.graphics.Matrix() }
    val strokeClip = remember { RectF() }
    var eraserAt by remember { mutableStateOf<Offset?>(null) }
    var shapePreview by remember { mutableStateOf<Pair<Int, Shape>?>(null) }
    val drag = remember { DragState() }

    /** The shape the shape pen makes for the current drag. */
    fun dragShape(end: Offset): Shape? {
        val preset = tools.pen
        val xs = drag.path.map { it.x }.toFloatArray()
        val ys = drag.path.map { it.y }.toFloatArray()
        return ShapeBuilder.build(
            UUID.randomUUID().toString(), preset.shape, preset.sides, drag.start.x, drag.start.y, end.x, end.y, xs to ys,
            preset.color, preset.size, preset.fill, preset.line,
        )
    }

    /** The selection's corners on screen, clockwise from top-left, with the dragged transform applied. */
    fun selectionCorners(): List<Offset>? {
        val box = selection.bounds ?: return null
        val index = mapper.indexOf(selection.pageId).takeIf { it >= 0 } ?: return null
        val t = selection.preview
        return listOf(box.left to box.top, box.right to box.top, box.right to box.bottom, box.left to box.bottom).map { (x, y) ->
            val (px, py) = t.map(x, y)
            mapper.toScreen(index, Offset(px, py))
        }
    }

    val canvasTools = object : CanvasTools {
        override fun handleAt(at: Offset): Handle {
            if (!selection.active) return Handle.NONE
            val corners = selectionCorners() ?: return Handle.NONE
            val top = (corners[0] + corners[1]) / 2f
            if ((at - (top - Offset(0f, rotateDistance))).getDistance() < handleTouch) return Handle.ROTATE
            val scales = listOf(Handle.SCALE_TOP_LEFT, Handle.SCALE_TOP_RIGHT, Handle.SCALE_BOTTOM_RIGHT, Handle.SCALE_BOTTOM_LEFT)
            corners.forEachIndexed { i, c -> if ((at - c).getDistance() < handleTouch) return scales[i] }
            val xs = corners.map { it.x }.toFloatArray()
            val ys = corners.map { it.y }.toFloatArray()
            return if (Geometry.inside(at.x, at.y, xs, ys)) Handle.BODY else Handle.NONE
        }

        override fun drawingDrag() = when {
            tools.tool == Tool.LASSO -> DragKind.LASSO
            tools.tool == Tool.PEN && tools.pen.pen == Pen.SHAPE -> DragKind.SHAPE
            tools.tool == Tool.TEXT -> DragKind.PLACE
            else -> null
        }

        override fun dragStart(kind: DragKind, at: Offset, handle: Handle) {
            drag.kind = kind
            drag.last = at
            drag.group = Any()
            selection.menuAt = null
            when (kind) {
                DragKind.ERASE -> eraseAlong(at, at)
                DragKind.LASSO -> {
                    selection.clear()
                    drag.page = mapper.pageAt(at)
                    selection.lasso = listOf(at)
                }
                DragKind.SELECTION -> selection.startDrag(handle, mapper.toPage(mapper.indexOf(selection.pageId), at))
                DragKind.PLACE -> Unit
                DragKind.SHAPE -> {
                    selection.clear()
                    drag.page = mapper.pageAt(at)
                    drag.start = mapper.toPage(drag.page, at)
                    drag.path.clear()
                    drag.path += drag.start
                }
            }
        }

        override fun dragMove(to: Offset) {
            when (drag.kind) {
                DragKind.ERASE -> eraseAlong(drag.last, to)
                DragKind.LASSO -> selection.lasso =
                    if (tools.lassoShape == LassoShape.RECT) listOf(selection.lasso.first(), to) else selection.lasso + to
                DragKind.SELECTION -> selection.drag(mapper.toPage(mapper.indexOf(selection.pageId), to))
                DragKind.PLACE -> Unit
                DragKind.SHAPE -> {
                    val point = mapper.toPage(drag.page, to)
                    drag.path += point
                    shapePreview = dragShape(point)?.let { drag.page to it }
                }
                null -> Unit
            }
            drag.last = to
        }

        override fun dragEnd() {
            when (drag.kind) {
                DragKind.ERASE -> eraserAt = null
                DragKind.LASSO -> finishLasso()
                DragKind.SELECTION -> {
                    selection.endDrag(ink)
                    if (tools.autoDeselect) selection.clear()
                }
                DragKind.PLACE -> Unit
                DragKind.SHAPE -> {
                    shapePreview = null
                    dragShape(mapper.toPage(drag.page, drag.last))?.let { shape ->
                        pages.getOrNull(drag.page)?.let { page -> ink.update(page.id) { it.copy(objects = it.objects + shape) } }
                    }
                }
                null -> Unit
            }
            drag.kind = null
            drag.group = null
        }

        override fun tap(at: Offset) {
            if (selection.text != null) {
                selection.commitText(ink, resources)?.let { tools.updateTextStyle(it.style) }
                return
            }
            selection.clear()
            selection.menuAt = null
            if (tools.mode == InputMode.PEN && tools.tool == Tool.TEXT) placeText(at)
        }

        /** Edits the text box under the point, or starts a new one there. */
        private fun placeText(at: Offset) {
            val index = mapper.pageAt(at)
            val page = pages.getOrNull(index) ?: return
            val point = mapper.toPage(index, at)
            val existing = ink.content(page.id).objects.lastOrNull { it is TextBox && Geometry.hit(PageContent(objects = listOf(it)), point.x, point.y, 2f) != null }
            if (existing is TextBox) return selection.startText(page.id, existing, isNew = false)
            selection.startText(page.id, newTextBox(page.width, point.x, point.y, tools.textStyle), isNew = true)
        }

        override fun penDown(at: Offset) {
            tap(at)
            drag.convert = null
        }

        override fun penHeld(path: List<Offset>) {
            val index = mapper.pageAt(path.first())
            val local = path.map { mapper.toPage(index, it) }
            val found = ShapeRecognizer.recognize(local.map { it.x }.toFloatArray(), local.map { it.y }.toFloatArray(), tools.convertShapes) ?: return
            val preset = tools.pen
            val shape = Shape(UUID.randomUUID().toString(), found.kind, found.xs, found.ys, preset.color, preset.size)
            drag.convert = index to shape
            shapePreview = index to shape
        }

        override fun longPress(at: Offset) {
            val index = mapper.pageAt(at)
            val page = pages.getOrNull(index) ?: return
            val point = mapper.toPage(index, at)
            val content = ink.content(page.id)
            val hit = Geometry.hit(content, point.x, point.y, slop = handleTouch / mapper.pixelsPerPoint / 2)
            if (hit != null) {
                selection.select(page.id, hit, content)
            } else {
                selection.clear()
                selection.menuAt = page.id to point
            }
        }

        private fun eraseAlong(from: Offset, to: Offset) {
            eraserAt = to
            val index = mapper.pageAt(to)
            val k = mapper.pixelsPerPoint
            erase(ink, pages[index].id, mapper.toPage(index, from), mapper.toPage(index, to), eraserRadius / k, tools.eraserMode, drag.group ?: Any())
        }

        private fun finishLasso() {
            val points = selection.lasso
            selection.lasso = emptyList()
            val page = pages.getOrNull(drag.page) ?: return
            val local = points.map { mapper.toPage(drag.page, it) }
            val polygon = if (tools.lassoShape == LassoShape.RECT && local.size == 2) {
                val (a, b) = local
                listOf(a, Offset(b.x, a.y), b, Offset(a.x, b.y))
            } else {
                local
            }
            val content = ink.content(page.id)
            val picked = Geometry.select(content, polygon.map { it.x }.toFloatArray(), polygon.map { it.y }.toFloatArray(), tools.lassoTypes)
            selection.select(page.id, picked, content)
        }
    }

    val currentTools by rememberUpdatedState(canvasTools)

    LaunchedEffect(layout, size) { state.updateLayout(size.toSize(), layout.contentHeight, layout.rects) }

    LaunchedEffect(layout, ink, ink.generation) {
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

    Box(modifier.clipToBounds().onSizeChanged { size = it }) {
        Box(Modifier.fillMaxSize().canvasGestures(state, tools) { currentTools }) {
        Canvas(Modifier.fillMaxSize()) {
            assetsLoaded()
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
                val editing = selection.text?.takeIf { it.pageId == pages[i].id }?.box?.id
                    ?: selection.crop?.takeIf { it.pageId == pages[i].id }?.picture?.id
                val content = ink.content(pages[i].id).let { c -> if (editing == null) c else c.copy(objects = c.objects.filter { it.id != editing }) }
                if (!content.isEmpty) {
                    val k = layout.scale * zoom
                    strokeMatrix.setScale(k, k)
                    strokeMatrix.postTranslate(screen.left, screen.top)
                    strokeClip.set(screen.left, screen.top, screen.right, screen.bottom)
                    val moving = selection.pageId == pages[i].id && selection.preview != Affine()
                    drawIntoCanvas { canvas ->
                        if (moving) {
                            painter.draw(canvas.nativeCanvas, selection.picked.without(content), strokeMatrix, strokeClip, resources)
                            val moved = android.graphics.Matrix(strokeMatrix).apply { preConcat(selection.preview.toMatrix()) }
                            painter.draw(canvas.nativeCanvas, selection.picked.of(content), moved, strokeClip, resources)
                        } else {
                            painter.draw(canvas.nativeCanvas, content, strokeMatrix, strokeClip, resources)
                        }
                    }
                }
                drawRect(outline, screen.topLeft, screen.size, style = Stroke(1f))
            }
            eraserAt?.let { drawCircle(outline, eraserRadius, it, style = Stroke(2f)) }
            shapePreview?.let { (index, shape) ->
                val origin = mapper.toScreen(index, Offset.Zero)
                val k = mapper.pixelsPerPoint
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.withMatrix(android.graphics.Matrix().apply { setScale(k, k); postTranslate(origin.x, origin.y) }) {
                        painter.drawShape(this, shape)
                    }
                }
            }
            drawLasso(selection.lasso, tools.lassoShape, accent)
            if (selection.active && selection.lasso.isEmpty()) selectionCorners()?.let { drawSelection(it, accent, rotateDistance) }
        }
        InkLayer(state, layout, pages, ink, tools) {
            drag.convert.also {
                drag.convert = null
                shapePreview = null
            }
        }
        }
        if (pages.size > 1) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                onClick = onPageCounter,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.page_of, state.currentPage + 1, pages.size),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        overlay(mapper)
    }
}

private fun Affine.toMatrix() = android.graphics.Matrix().apply { setValues(floatArrayOf(a, c, e, b, d, f, 0f, 0f, 1f)) }

private fun DrawScope.drawLasso(points: List<Offset>, shape: LassoShape, colour: Color) {
    if (points.size < 2) return
    val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))
    val path = Path().apply {
        if (shape == LassoShape.RECT) {
            val (a, b) = points.first() to points.last()
            moveTo(a.x, a.y); lineTo(b.x, a.y); lineTo(b.x, b.y); lineTo(a.x, b.y); close()
        } else {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
    }
    drawPath(path, colour, style = Stroke(1.5.dp.toPx(), pathEffect = dash))
}

private fun DrawScope.drawSelection(corners: List<Offset>, colour: Color, rotateDistance: Float) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
    val box = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        corners.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(box, colour, style = Stroke(1.5.dp.toPx(), pathEffect = dash))
    val top = (corners[0] + corners[1]) / 2f
    val rotate = top - Offset(0f, rotateDistance)
    drawLine(colour, top, rotate, 1.5.dp.toPx())
    (corners + rotate).forEach {
        drawCircle(Color.White, HANDLE_RADIUS.toPx(), it)
        drawCircle(colour, HANDLE_RADIUS.toPx(), it, style = Stroke(2.dp.toPx()))
    }
}

/** Live, low-latency strokes; finished strokes move onto the page they started on. */
@Composable
private fun InkLayer(
    state: ViewportState,
    layout: PageLayout,
    pages: List<Page>,
    ink: PageSession,
    tools: ToolState,
    takeConversion: () -> Pair<Int, Shape>?,
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
        defaultBrush = if (tools.mode == InputMode.PEN && tools.tool == Tool.PEN && tools.pen.pen.inks) tools.pen.brush else null,
        pointerEventToWorldTransform = pointerToWorld,
        onStrokesFinished = { strokes ->
            val now = System.currentTimeMillis()
            takeConversion()?.let { (index, shape) ->
                pages.getOrNull(index)?.let { page -> ink.update(page.id) { it.copy(objects = it.objects + shape) } }
                return@InProgressStrokes
            }
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
