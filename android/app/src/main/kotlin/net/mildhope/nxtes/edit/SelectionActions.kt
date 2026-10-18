package net.mildhope.nxtes.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.NxtesApplication
import net.mildhope.nxtes.data.Affine
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.data.Picture
import net.mildhope.nxtes.data.Shape
import net.mildhope.nxtes.data.Sticker
import net.mildhope.nxtes.data.StickyNote
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.data.recolored
import net.mildhope.nxtes.ink.PageSession
import net.mildhope.nxtes.page.PagePainter
import net.mildhope.nxtes.page.PageResources
import net.mildhope.nxtes.page.keepBitmap
import java.util.UUID
import kotlin.math.roundToInt

/** Page points kept clear around a selection turned into a sticker. */
private const val STICKER_MARGIN = 2f

/** What the selection menu does to the picked items. */
class SelectionActions(
    private val app: NxtesApplication,
    private val documentId: String,
    private val session: PageSession,
    private val selection: SelectionState,
) {
    /** Whether anything picked has a color to change. */
    val canRecolor: Boolean
        get() {
            val page = selection.pageId ?: return false
            val picked = selection.picked.of(session.content(page))
            return picked.strokes.isNotEmpty() || picked.objects.any { it is Shape || it is TextBox }
        }

    /** The text box, when that's all that's picked. */
    fun singleText(): TextBox? = selection.pageId?.let { selection.singleText(session.content(it)) }

    /** The picture, when that's all that's picked. */
    fun singlePicture(): Picture? {
        val page = selection.pageId ?: return null
        val picked = selection.picked.of(session.content(page))
        return (picked.objects.singleOrNull() as? Picture)?.takeIf { picked.strokes.isEmpty() && picked.notes.isEmpty() && picked.masks.isEmpty() }
    }

    /** The sticky note, when that's all that's picked. */
    fun singleNote(): StickyNote? {
        val page = selection.pageId ?: return null
        val picked = selection.picked.of(session.content(page))
        return picked.notes.singleOrNull()?.takeIf { picked.strokes.isEmpty() && picked.objects.isEmpty() && picked.masks.isEmpty() }
    }

    /** The handwriting among the picked items. */
    val writing: List<InkStroke>
        get() {
            val page = selection.pageId ?: return emptyList()
            return selection.picked.of(session.content(page)).strokes.filter { it.isWriting }
        }

    /** Puts a text box saying [text] in [font] in place of the picked handwriting, and selects it. [pageWidth] is as far as it may reach. */
    fun replaceWriting(text: String, font: String, pageWidth: Float, resources: PageResources) {
        val page = selection.pageId ?: return
        val writing = writing.ifEmpty { return }
        val box = textBoxFor(writing, text, font, pageWidth, resources)
        val ids = writing.map { it.id }.toSet()
        session.update(page) { c -> c.copy(strokes = c.strokes.filter { it.id !in ids }, objects = c.objects + box) }
        selection.select(page, Picked(objects = setOf(box.id)), session.content(page))
    }

    /** Whether anything picked can change its stacking order. */
    val canReorder get() = selection.picked.objects.isNotEmpty()

    fun recolor(color: Int) = change { content ->
        selection.picked.map(
            content,
            stroke = { if (it.pen == Pen.HIGHLIGHTER) it.recolored((color and 0xFFFFFF) or (it.color and 0xFF000000.toInt())) else it.recolored(color) },
            obj = {
                when (it) {
                    is Shape -> it.copy(color = color)
                    is TextBox -> it.copy(color = color)
                    else -> it
                }
            },
        )
    }

    fun copy() {
        val page = selection.pageId ?: return
        val box = selection.bounds ?: return
        app.copiedContent = ContentClip(documentId, selection.picked.of(session.content(page)), box)
    }

    fun cut() {
        copy()
        delete()
    }

    fun delete() {
        val page = selection.pageId ?: return
        session.update(page) { selection.picked.without(it) }
        selection.clear()
    }

    fun reorder(toFront: Boolean) = change { content ->
        val moving = content.objects.filter { it.id in selection.picked.objects }
        val rest = content.objects.filter { it.id !in selection.picked.objects }
        content.copy(objects = if (toFront) rest + moving else moving + rest)
    }

    /** Pastes the copied items centred on [at] (page points) and selects them. */
    suspend fun paste(pageId: String, at: Offset) {
        val clip = app.copiedContent ?: return
        if (clip.documentId != documentId) {
            withContext(Dispatchers.IO) { app.library.copyBlobs(clip.documentId, documentId, clip.content.blobs) }
        }
        val placed = clip.content
            .withNewIds { UUID.randomUUID().toString() }
            .transformed(Affine.translate(at.x - clip.bounds.centerX, at.y - clip.bounds.centerY))
        session.update(pageId) { it + placed }
        selection.select(pageId, Picked.all(placed), session.content(pageId))
    }

    /** Draws the picked items into a sticker in the pack called [packName], making the pack if needed. */
    suspend fun addToStickers(context: Context, packName: String): Sticker? {
        val page = selection.pageId ?: return null
        val box = selection.bounds?.inflate(STICKER_MARGIN) ?: return null
        val content = selection.picked.of(session.content(page))
        return withContext(Dispatchers.IO) {
            val scale = STICKER_PIXELS / maxOf(box.width, box.height)
            val w = (box.width * scale).roundToInt().coerceAtLeast(1)
            val h = (box.height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = createBitmap(w, h)
            try {
                val matrix = Matrix().apply {
                    setTranslate(-box.left, -box.top)
                    postScale(scale, scale)
                }
                PagePainter().draw(Canvas(bitmap), content, matrix, RectF(0f, 0f, w.toFloat(), h.toFloat()), app.assets.blockingResources(documentId))
                val library = app.library
                val pack = library.stickerPacks.value.firstOrNull { it.name == packName } ?: library.addStickerPack(packName)
                keepBitmap(context, bitmap) { library.addSticker(pack.id, it, w, h) }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun change(transform: (net.mildhope.nxtes.data.PageContent) -> net.mildhope.nxtes.data.PageContent) {
        val page = selection.pageId ?: return
        session.update(page, change = transform)
        selection.select(page, selection.picked, session.content(page))
    }
}
