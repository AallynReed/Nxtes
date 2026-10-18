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
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.StrokeCap
import net.mildhope.nxtes.ink.LaserMode
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import net.mildhope.nxtes.data.Mask
import net.mildhope.nxtes.data.recoloured
import net.mildhope.nxtes.ink.Brushes
import net.mildhope.nxtes.edit.PageText
import net.mildhope.nxtes.edit.TextPick
import net.mildhope.nxtes.data.MaskKind
import net.mildhope.nxtes.data.Shape
import net.mildhope.nxtes.edit.MaskBuilder
import net.mildhope.nxtes.data.StickyNote
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

    /** The end of picked PDF text being dragged, if that's what this drag is. */
    var textHandle: Handle? = null

    /** A shape the next finished ink stroke turns into, from holding the pen still. */
    var convert: Pair<Int, Shape>? = null
}

/** A point the laser passed, in screen pixels, and when. */
private class LaserPoint(val at: Offset, val time: Long)

/** The laser's recent path; it fades and is never saved. */
private class LaserTrail {
    val points = mutableStateListOf<LaserPoint>()
    var down by mutableStateOf(false)
    var liftedAt = 0L
    var now by mutableLongStateOf(0L)
    val visible get() = down || points.isNotEmpty()

    fun add(at: Offset, mode: LaserMode) {
        val now = SystemClock.uptimeMillis()
        if (mode == LaserMode.DOT) points.clear()
        points += LaserPoint(at, now)
    }

    /** Drops what has faded by [time]; the head stays while the pointer is down. */
    fun tick(time: Long, mode: LaserMode) {
        now = time
        if (mode == LaserMode.DOT) {
            if (!down && time - liftedAt > LASER_DOT_FADE_MS) points.clear()
            return
        }
        val keep = if (down) 1 else 0
        while (points.size > keep && time - points.first().time > LASER_TRAIL_MS) points.removeAt(0)
    }
}

private const val LASER_TRAIL_MS = 700L
private const val LASER_DOT_FADE_MS = 300L
private val TEXT_KNOB = 8.dp
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
    flow: PageFlow,
    onPageCounter: () -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    darkPages: Boolean = false,
    assetsLoaded: () -> Int = { 0 },
    onStamp: (pageIndex: Int, point: Offset) -> Unit = { _, _ -> },
    pageText: suspend (pageIndex: Int) -> PageText? = { null },
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
    val layout = remember(pages, size, gap, flow) { PageLayout(pages, size.toSize(), gap, flow) }
    val mapper = PageMapper(layout, state, pages)
    val base = remember(layout) { mutableStateMapOf<Int, ImageBitmap>() }
    val detail = remember(layout) { mutableStateMapOf<Int, Detail>() }
    val painter = remember { PagePainter() }
    val strokeMatrix = remember { android.graphics.Matrix() }
    val strokeClip = remember { RectF() }
    var eraserAt by remember { mutableStateOf<Offset?>(null) }
    var shapePreview by remember { mutableStateOf<Pair<Int, Shape>?>(null) }
    var maskPreview by remember { mutableStateOf<Pair<Int, Mask>?>(null) }
    val drag = remember { DragState() }
    val darkPaint = remember { Paint().apply { colorFilter = ColorFilter.colorMatrix(darkPagesMatrix) } }
    val laser = remember { LaserTrail() }
    val scope = rememberCoroutineScope()
    val knob = with(density) { TEXT_KNOB.toPx() }

    /** Where the picked text's two handles sit on screen: below its first and last characters. */
    fun textHandles(pick: TextPick): Pair<Offset, Offset>? {
        val r = pick.rects
        val index = mapper.indexOf(pick.pageId)
        if (r.isEmpty() || index < 0) return null
        val last = r.size - 4
        val start = mapper.toScreen(index, Offset(r[0], r[1] + r[3])) + Offset(0f, knob)
        val end = mapper.toScreen(index, Offset(r[last] + r[last + 2], r[last + 1] + r[last + 3])) + Offset(0f, knob)
        return start to end
    }

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

    /** The mask the masking tool makes for the current drag. */
    fun dragMask(end: Offset): Mask? = if (tools.maskKind == MaskKind.TAPE) {
        MaskBuilder.tape(UUID.randomUUID().toString(), drag.start.x, drag.start.y, end.x, end.y, tools.maskColor)
    } else {
        MaskBuilder.pen(UUID.randomUUID().toString(), drag.path, tools.maskWidth, tools.maskColor)
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
            selection.textPick?.let(::textHandles)?.let { (start, end) ->
                if ((at - start).getDistance() < handleTouch) return Handle.TEXT_START
                if ((at - end).getDistance() < handleTouch) return Handle.TEXT_END
            }
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
            tools.tool == Tool.LASER -> DragKind.LASER
            tools.tool == Tool.MASK -> DragKind.MASK
            tools.tool == Tool.LASSO -> DragKind.LASSO
            tools.tool == Tool.PEN && tools.pen.pen == Pen.SHAPE -> DragKind.SHAPE
            tools.tool == Tool.TEXT || tools.tool == Tool.NOTE || tools.tool == Tool.STICKER -> DragKind.PLACE
            else -> null
        }

        override fun readOnly() = readOnly

        override fun opensOnTap(at: Offset) = noteAt(at) != null || maskAt(at) != null

        /** The topmost mask under the point, with its page. */
        private fun maskAt(at: Offset): Pair<String, Mask>? {
            if (tools.annotationsHidden) return null
            val index = mapper.pageAt(at)
            val page = pages.getOrNull(index) ?: return null
            val point = mapper.toPage(index, at)
            val mask = ink.content(page.id).masks.lastOrNull { Geometry.hit(it, point.x, point.y, handleTouch / mapper.pixelsPerPoint / 4) } ?: return null
            return page.id to mask
        }

        /** The sticky note under the point, with its page. */
        private fun noteAt(at: Offset): Pair<String, StickyNote>? {
            if (tools.annotationsHidden) return null
            val index = mapper.pageAt(at)
            val page = pages.getOrNull(index) ?: return null
            val point = mapper.toPage(index, at)
            val slop = handleTouch / mapper.pixelsPerPoint / 4
            val note = ink.content(page.id).notes.lastOrNull { Geometry.bounds(it).inflate(slop).contains(point.x, point.y) } ?: return null
            return page.id to note
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
                DragKind.SELECTION -> if (handle == Handle.TEXT_START || handle == Handle.TEXT_END) {
                    drag.textHandle = handle
                } else {
                    selection.startDrag(handle, mapper.toPage(mapper.indexOf(selection.pageId), at))
                }
                DragKind.PLACE -> Unit
                DragKind.LASER -> {
                    laser.down = true
                    laser.add(at, tools.laserMode)
                }
                DragKind.MASK -> {
                    selection.clear()
                    selection.asking = null
                    drag.page = mapper.pageAt(at)
                    drag.start = mapper.toPage(drag.page, at)
                    drag.path.clear()
                    drag.path += drag.start
                }
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
                DragKind.SELECTION -> if (drag.textHandle != null) moveTextHandle(to) else selection.drag(mapper.toPage(mapper.indexOf(selection.pageId), to))
                DragKind.PLACE -> Unit
                DragKind.LASER -> laser.add(to, tools.laserMode)
                DragKind.MASK -> {
                    val point = mapper.toPage(drag.page, to)
                    drag.path += point
                    maskPreview = dragMask(point)?.let { drag.page to it }
                }
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
                DragKind.SELECTION -> if (drag.textHandle != null) {
                    drag.textHandle = null
                } else {
                    selection.endDrag(ink)
                    if (tools.autoDeselect) selection.clear()
                }
                DragKind.PLACE -> Unit
                DragKind.LASER -> {
                    laser.down = false
                    laser.liftedAt = SystemClock.uptimeMillis()
                }
                DragKind.MASK -> {
                    maskPreview = null
                    dragMask(mapper.toPage(drag.page, drag.last))?.let { mask ->
                        pages.getOrNull(drag.page)?.let { page -> ink.update(page.id) { it.copy(masks = it.masks + mask) } }
                    }
                }
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
            if (readOnly) {
                maskAt(at)?.let { (pageId, mask) -> selection.toggleMask(pageId, mask.id) }
                selection.asking = null
                return
            }
            if (finishEditing()) return
            selection.clear()
            selection.menuAt = null
            selection.asking = null
            if (openNote(at)) return
            maskAt(at)?.let { (pageId, mask) -> return selection.toggleMask(pageId, mask.id) }
            if (tools.mode != InputMode.PEN) return
            when (tools.tool) {
                Tool.TEXT -> placeText(at)
                Tool.NOTE -> placeNote(at)
                Tool.STICKER -> {
                    val index = mapper.pageAt(at)
                    if (index >= 0) onStamp(index, mapper.toPage(index, at))
                }
                else -> Unit
            }
        }

        /** Moves the dragged end of the picked text to the character nearest the point, aiming above the handle. */
        private fun moveTextHandle(to: Offset) {
            val pick = selection.textPick ?: return
            val index = mapper.indexOf(pick.pageId).takeIf { it >= 0 } ?: return
            val point = mapper.toPage(index, to - Offset(0f, knob * 2))
            val i = pick.text.charAt(point.x, point.y, Float.MAX_VALUE).takeIf { it >= 0 } ?: return
            val start = if (drag.textHandle == Handle.TEXT_START) i else pick.start
            val end = if (drag.textHandle == Handle.TEXT_END) i else pick.end
            if (start > end) drag.textHandle = if (drag.textHandle == Handle.TEXT_START) Handle.TEXT_END else Handle.TEXT_START
            selection.textPick = TextPick(pick.pageId, pick.text, minOf(start, end), maxOf(start, end))
        }

        /** Picks the PDF word under the point, or opens the page menu when there's no text there. */
        private fun pickWord(index: Int, point: Offset) {
            val page = pages[index]
            scope.launch {
                val text = pageText(index)
                val i = text?.charAt(point.x, point.y, handleTouch / mapper.pixelsPerPoint / 2) ?: -1
                selection.clear()
                if (text == null || i < 0) {
                    selection.menuAt = page.id to point
                } else {
                    val word = text.wordAt(i)
                    selection.textPick = TextPick(page.id, text, word.first, word.last)
                }
            }
        }

        /** Closes a text box or note being written; false if there was none. */
        private fun finishEditing(): Boolean {
            if (selection.text != null) {
                selection.commitText(ink, resources)?.let { tools.updateTextStyle(it.style) }
                return true
            }
            if (selection.note != null) {
                selection.commitNote(ink)
                return true
            }
            return false
        }

        /** Opens the sticky note under the point, if there is one. */
        private fun openNote(at: Offset): Boolean {
            val (pageId, note) = noteAt(at) ?: return false
            selection.startNote(ink, pageId, note, isNew = false)
            return true
        }

        private fun placeNote(at: Offset) {
            val index = mapper.pageAt(at)
            val page = pages.getOrNull(index) ?: return
            val point = mapper.toPage(index, at)
            val half = PagePainter.NOTE_SIZE / 2
            val note = StickyNote(
                UUID.randomUUID().toString(),
                (point.x - half).coerceIn(0f, (page.width - 2 * half).coerceAtLeast(0f)),
                (point.y - half).coerceIn(0f, (page.height - 2 * half).coerceAtLeast(0f)),
                "", tools.noteColor, System.currentTimeMillis(),
            )
            selection.startNote(ink, page.id, note, isNew = true)
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
            if (!finishEditing()) {
                selection.clear()
                selection.menuAt = null
                selection.asking = null
            }
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

        override fun fingerTap(fingers: Int) {
            if (readOnly || selection.text != null || selection.note != null) return
            selection.clear()
            when (fingers) {
                2 -> ink.undo()
                3 -> ink.redo()
            }
        }

        override fun longPress(at: Offset) {
            if (readOnly || tools.annotationsHidden) return
            val index = mapper.pageAt(at)
            val page = pages.getOrNull(index) ?: return
            val point = mapper.toPage(index, at)
            val content = ink.content(page.id)
            val hit = Geometry.hit(content, point.x, point.y, slop = handleTouch / mapper.pixelsPerPoint / 2)
            val mark = content.marks.lastOrNull { Geometry.hit(it, point.x, point.y) }
            if (hit != null) {
                selection.select(page.id, hit, content)
            } else if (tools.mode == InputMode.GESTURE && mark != null) {
                selection.clear()
                selection.markAt = page.id to mark.id
            } else if (tools.mode == InputMode.GESTURE && page.pdf != null) {
                pickWord(index, point)
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

    LaunchedEffect(layout, size) { state.updateLayout(size.toSize(), layout) }

    LaunchedEffect(laser.visible) {
        while (laser.visible) withFrameMillis { laser.tick(SystemClock.uptimeMillis(), tools.laserMode) }
    }

    LaunchedEffect(tools.annotationsHidden) { if (tools.annotationsHidden) selection.clear() }

    LaunchedEffect(layout, ink, ink.generation) {
        snapshotFlow { layout.visible(state.zoom, state.offset, state.size, extra = 1) }
            .distinctUntilChanged()
            .collect { range -> ink.ensureLoaded(range.map { pages[it].id }) }
    }

    LaunchedEffect(layout) {
        snapshotFlow { layout.visible(state.zoom, state.offset, state.size, extra = 1) }
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
            for (i in layout.visible(zoom, offset, viewportSize)) {
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
            if (darkPages) drawIntoCanvas { it.saveLayer(viewport, darkPaint) }
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
                if (!content.isEmpty && !tools.annotationsHidden) {
                    val k = layout.scale * zoom
                    strokeMatrix.setScale(k, k)
                    strokeMatrix.postTranslate(screen.left, screen.top)
                    strokeClip.set(screen.left, screen.top, screen.right, screen.bottom)
                    val moving = selection.pageId == pages[i].id && selection.preview != Affine()
                    drawIntoCanvas { canvas ->
                        if (moving) {
                            painter.draw(canvas.nativeCanvas, selection.picked.without(content), strokeMatrix, strokeClip, resources, selection.revealed)
                            val moved = android.graphics.Matrix(strokeMatrix).apply { preConcat(selection.preview.toMatrix()) }
                            painter.draw(canvas.nativeCanvas, selection.picked.of(content), moved, strokeClip, resources, selection.revealed)
                        } else {
                            painter.draw(canvas.nativeCanvas, content, strokeMatrix, strokeClip, resources, selection.revealed)
                        }
                    }
                }
                drawRect(outline, screen.topLeft, screen.size, style = Stroke(1f))
            }
            if (darkPages) drawIntoCanvas { it.restore() }
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
            maskPreview?.let { (index, mask) ->
                val origin = mapper.toScreen(index, Offset.Zero)
                val k = mapper.pixelsPerPoint
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.withMatrix(android.graphics.Matrix().apply { setScale(k, k); postTranslate(origin.x, origin.y) }) {
                        painter.drawMask(this, mask, revealed = false)
                    }
                }
            }
            selection.textPick?.let { pick ->
                val index = mapper.indexOf(pick.pageId)
                if (index >= 0) drawTextPick(pick.rects, textHandles(pick), knob, accent) { x, y -> mapper.toScreen(index, Offset(x, y)) }
            }
            drawLasso(selection.lasso, tools.lassoShape, accent)
            if (laser.visible) drawLaser(laser, tools.laserMode, Color(tools.laserColor))
            if (selection.active && selection.lasso.isEmpty()) selectionCorners()?.let { drawSelection(it, accent, rotateDistance) }
        }
        InkLayer(state, layout, pages, ink, tools, !readOnly, darkPages) {
            drag.convert.also {
                drag.convert = null
                shapePreview = null
            }
        }
        }
        if (tools.annotationsHidden) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                onClick = tools::toggleAnnotations,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.annotations_hidden),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
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

/** The laser: a glowing dot at its head, and in line mode a trail that fades with age. */
private fun DrawScope.drawLaser(trail: LaserTrail, mode: LaserMode, colour: Color) {
    val points = trail.points
    if (points.isEmpty()) return
    val now = trail.now
    if (mode == LaserMode.LINE) {
        for (i in 1 until points.size) {
            val alpha = (1f - (now - points[i].time).toFloat() / LASER_TRAIL_MS).coerceIn(0f, 1f)
            drawLine(colour.copy(alpha = alpha * 0.3f), points[i - 1].at, points[i].at, 12.dp.toPx(), StrokeCap.Round)
            drawLine(colour.copy(alpha = alpha), points[i - 1].at, points[i].at, 4.dp.toPx(), StrokeCap.Round)
        }
    }
    val fade = if (trail.down) 1f else (1f - (now - trail.liftedAt).toFloat() / LASER_DOT_FADE_MS).coerceIn(0f, 1f)
    if (fade <= 0f) return
    val head = points.last().at
    drawCircle(colour.copy(alpha = 0.3f * fade), 12.dp.toPx(), head)
    drawCircle(colour.copy(alpha = fade), 6.dp.toPx(), head)
    drawCircle(Color.White.copy(alpha = 0.8f * fade), 2.5.dp.toPx(), head)
}

/** Picked text: a tint over each line, and a handle hanging below each end. */
private fun DrawScope.drawTextPick(rects: FloatArray, handles: Pair<Offset, Offset>?, knob: Float, colour: Color, toScreen: (Float, Float) -> Offset) {
    for (i in rects.indices step 4) {
        val a = toScreen(rects[i], rects[i + 1])
        val b = toScreen(rects[i] + rects[i + 2], rects[i + 1] + rects[i + 3])
        drawRect(colour.copy(alpha = 0.25f), a, androidx.compose.ui.geometry.Size(b.x - a.x, b.y - a.y))
    }
    handles?.toList()?.forEach { at ->
        drawLine(colour, at - Offset(0f, knob * 2), at, 2.dp.toPx())
        drawCircle(colour, knob * 0.75f, at)
    }
}

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
    enabled: Boolean,
    darkPages: Boolean,
    takeConversion: () -> Pair<Int, Shape>?,
) {
    val pen = tools.pen
    val brush = remember(pen, darkPages) { if (darkPages) Brushes.brush(pen.pen, darkColor(pen.color), pen.size) else pen.brush }
    val toWorld = 1f / (state.zoom * layout.scale)
    val pointerToWorld = Matrix().apply {
        values[Matrix.ScaleX] = toWorld
        values[Matrix.ScaleY] = toWorld
        values[Matrix.TranslateX] = -state.offset.x * toWorld
        values[Matrix.TranslateY] = -state.offset.y * toWorld
    }
    val scratch = remember { StrokeInput() }
    InProgressStrokes(
        defaultBrush = if (enabled && tools.mode == InputMode.PEN && tools.tool == Tool.PEN && pen.pen.inks && !tools.annotationsHidden) brush else null,
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
                val finished = StrokeGeometry.fromFinished(stroke, origin.x, origin.y, started)
                ink.add(pages[index].id, if (darkPages) finished.recoloured(pen.color) else finished)
            }
        },
    )
}
