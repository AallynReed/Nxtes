package net.mildhope.nxtes.edit

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import net.mildhope.nxtes.data.Affine
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.ink.PageSession
import net.mildhope.nxtes.page.PagePainter
import net.mildhope.nxtes.page.PageResources
import kotlin.math.atan2
import kotlin.math.hypot

/** Where a touch landed on a selection. */
enum class Handle { NONE, BODY, SCALE_TOP_LEFT, SCALE_TOP_RIGHT, SCALE_BOTTOM_RIGHT, SCALE_BOTTOM_LEFT, ROTATE }

/** A text box being typed into: the page it's on and how it looks now. */
class TextEdit(val pageId: String, val box: TextBox, val isNew: Boolean)

/** Items copied with the lasso, with the document whose blobs they use. */
class ContentClip(val documentId: String, val content: PageContent, val bounds: Box)

/**
 * The lasso selection in the open document: what's picked on which page, and the move, resize or
 * turn being dragged right now, which is applied when the drag ends.
 */
@Stable
class SelectionState {
    var pageId by mutableStateOf<String?>(null)
        private set
    var picked by mutableStateOf(Picked())
        private set
    var bounds by mutableStateOf<Box?>(null)
        private set

    /** The transform being dragged, in page points. */
    var preview by mutableStateOf(Affine())
        private set

    /** The lasso being drawn, in screen pixels. */
    var lasso by mutableStateOf<List<Offset>>(emptyList())

    /** A page and point, in page points, where a touch-and-hold opened the page menu. */
    var menuAt by mutableStateOf<Pair<String, Offset>?>(null)

    var text by mutableStateOf<TextEdit?>(null)
        private set

    val active get() = pageId != null && !picked.isEmpty

    private var dragHandle = Handle.NONE
    private var dragStart = Offset.Zero

    fun select(pageId: String, picked: Picked, content: PageContent) {
        val box = Geometry.bounds(picked.of(content))
        if (picked.isEmpty || box == null) return clear()
        this.pageId = pageId
        this.picked = picked
        bounds = box
        preview = Affine()
        menuAt = null
    }

    fun startText(pageId: String, box: TextBox, isNew: Boolean) {
        clear()
        menuAt = null
        text = TextEdit(pageId, box, isNew)
    }

    fun updateText(box: TextBox) {
        text = text?.let { TextEdit(it.pageId, box, it.isNew) }
    }

    /** Saves the text box being typed into, sized to its text, or drops it if it's empty. */
    fun commitText(session: PageSession, resources: PageResources): TextBox? {
        val edit = text ?: return null
        text = null
        val box = edit.box
        if (box.text.isBlank()) {
            if (!edit.isNew) session.update(edit.pageId) { c -> c.copy(objects = c.objects.filter { it.id != box.id }) }
            return null
        }
        val saved = box.copy(height = PagePainter.textLayout(box, resources).height.toFloat().coerceAtLeast(box.size))
        session.update(edit.pageId) { c ->
            if (c.objects.any { it.id == box.id }) c.copy(objects = c.objects.map { if (it.id == box.id) saved else it }) else c.copy(objects = c.objects + saved)
        }
        return saved
    }

    fun clear() {
        pageId = null
        picked = Picked()
        bounds = null
        preview = Affine()
        dragHandle = Handle.NONE
    }

    /** Starts dragging [handle] from [at], in page points. */
    fun startDrag(handle: Handle, at: Offset) {
        dragHandle = handle
        dragStart = at
        preview = Affine()
    }

    fun drag(to: Offset) {
        val box = bounds ?: return
        preview = when (dragHandle) {
            Handle.NONE -> Affine()
            Handle.BODY -> Affine.translate(to.x - dragStart.x, to.y - dragStart.y)
            Handle.ROTATE -> {
                val before = atan2(dragStart.y - box.centerY, dragStart.x - box.centerX)
                val after = atan2(to.y - box.centerY, to.x - box.centerX)
                Affine.around(box.centerX, box.centerY, 1f, Math.toDegrees((after - before).toDouble()).toFloat())
            }
            else -> {
                val (ax, ay) = anchor(box, dragHandle)
                val start = hypot(dragStart.x - ax, dragStart.y - ay)
                val now = hypot(to.x - ax, to.y - ay)
                val scale = if (start < 1f) 1f else (now / start).coerceIn(MIN_SCALE, MAX_SCALE)
                Affine.around(ax, ay, scale, 0f)
            }
        }
    }

    /** Applies the dragged transform to the page. */
    fun endDrag(session: PageSession) {
        val page = pageId ?: return
        val t = preview
        dragHandle = Handle.NONE
        if (t == Affine()) return
        session.update(page) { picked.transform(it, t) }
        select(page, picked, session.content(page))
    }

    private fun anchor(box: Box, handle: Handle) = when (handle) {
        Handle.SCALE_TOP_LEFT -> box.right to box.bottom
        Handle.SCALE_TOP_RIGHT -> box.left to box.bottom
        Handle.SCALE_BOTTOM_RIGHT -> box.left to box.top
        else -> box.right to box.top
    }

    private companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 10f
    }
}
