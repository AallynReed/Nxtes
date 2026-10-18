package net.mildhope.nxtes.edit

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.NxtesApplication
import net.mildhope.nxtes.data.Affine
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.data.Shape
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.data.recoloured
import net.mildhope.nxtes.ink.PageSession
import java.util.UUID

/** What the selection menu does to the picked items. */
class SelectionActions(
    private val app: NxtesApplication,
    private val documentId: String,
    private val session: PageSession,
    private val selection: SelectionState,
) {
    /** Whether anything picked has a colour to change. */
    val canRecolour: Boolean
        get() {
            val page = selection.pageId ?: return false
            val picked = selection.picked.of(session.content(page))
            return picked.strokes.isNotEmpty() || picked.objects.any { it is Shape || it is TextBox }
        }

    /** The text box, when that's all that's picked. */
    fun singleText(): TextBox? {
        val page = selection.pageId ?: return null
        val picked = selection.picked.of(session.content(page))
        return (picked.objects.singleOrNull() as? TextBox)?.takeIf { picked.strokes.isEmpty() && picked.notes.isEmpty() && picked.masks.isEmpty() }
    }

    /** Whether anything picked can change its stacking order. */
    val canReorder get() = selection.picked.objects.isNotEmpty()

    fun recolour(colour: Int) = change { content ->
        selection.picked.map(
            content,
            stroke = { if (it.pen == Pen.HIGHLIGHTER) it.recoloured((colour and 0xFFFFFF) or (it.color and 0xFF000000.toInt())) else it.recoloured(colour) },
            obj = {
                when (it) {
                    is Shape -> it.copy(color = colour)
                    is TextBox -> it.copy(color = colour)
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

    private fun change(transform: (net.mildhope.nxtes.data.PageContent) -> net.mildhope.nxtes.data.PageContent) {
        val page = selection.pageId ?: return
        session.update(page, change = transform)
        selection.select(page, selection.picked, session.content(page))
    }
}
