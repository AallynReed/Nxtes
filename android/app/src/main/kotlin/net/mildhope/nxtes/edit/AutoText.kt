package net.mildhope.nxtes.edit

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.ink.PageSession

/**
 * Turns writing into text as it's written. Once the pen has been up for [pauseMs] after the last
 * stroke, the strokes written since are [read] and [place]d as a text box in their stead, in one
 * undo step. [place] gets the box made just before, so writing that carries on can join it.
 * Batches are converted in order. Call from the main thread.
 */
class AutoText(
    private val scope: CoroutineScope,
    private val session: PageSession,
    private val read: suspend (writing: List<InkStroke>, before: String) -> String,
    private val place: (pageId: String, writing: List<InkStroke>, text: String, previous: TextBox?) -> TextBox,
    private val onFailed: () -> Unit = {},
    private val pauseMs: Long = PAUSE_MS,
) {
    private var pageId: String? = null
    private val pending = mutableListOf<String>()
    private var timer: Job? = null
    private var pressed = false
    private val converting = Mutex()
    private var last: Pair<String, String>? = null

    fun written(pageId: String, stroke: InkStroke) {
        if (!stroke.isWriting) return
        if (this.pageId != pageId) convertPending()
        this.pageId = pageId
        pending += stroke.id
        if (!pressed) startPause()
    }

    /** Whether a pen is on the page; the pause only counts while it's up. */
    fun pressed(down: Boolean) {
        if (down == pressed) return
        pressed = down
        if (down) timer?.cancel() else if (pending.isNotEmpty()) startPause()
    }

    /** Leaves what hasn't been converted yet as writing. */
    fun cancel() {
        timer?.cancel()
        pending.clear()
        pageId = null
    }

    private fun startPause() {
        timer?.cancel()
        timer = scope.launch {
            delay(pauseMs)
            timer = null
            convertPending()
        }
    }

    private fun convertPending() {
        timer?.cancel()
        val page = pageId ?: return
        val ids = pending.toSet()
        pending.clear()
        if (ids.isNotEmpty()) scope.launch { converting.withLock { convert(page, ids) } }
    }

    private suspend fun convert(page: String, ids: Set<String>) {
        val writing = session.content(page).strokes.filter { it.id in ids }
        if (writing.isEmpty()) return
        val text = try {
            read(writing, lastBox(page)?.text.orEmpty()).trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AutoText", "Reading failed", e)
            onFailed()
            return
        }
        if (text.isEmpty()) return
        val strokes = session.strokes(page)
        if (writing.any { w -> strokes.none { it.id == w.id } }) return
        val box = place(page, writing, text, lastBox(page))
        session.update(page) { c ->
            val objects = if (c.objects.any { it.id == box.id }) c.objects.map { if (it.id == box.id) box else it } else c.objects + box
            c.copy(strokes = c.strokes.filter { it.id !in ids }, objects = objects)
        }
        last = page to box.id
    }

    private fun lastBox(page: String) =
        last?.takeIf { it.first == page }?.let { (_, id) -> session.content(page).objects.firstOrNull { it.id == id } as? TextBox }

    companion object {
        const val PAUSE_MS = 1500L
    }
}
