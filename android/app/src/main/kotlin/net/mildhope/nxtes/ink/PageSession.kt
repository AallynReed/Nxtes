package net.mildhope.nxtes.ink

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.PageContent

/**
 * What's on the pages of one open document. Pages load on demand, every change can be undone, and
 * changed pages are saved shortly after the last edit. Call from the main thread.
 */
class PageSession(
    private val load: (pageId: String) -> PageContent,
    private val save: (Map<String, PageContent>) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val saveDelayMs: Long = 1000,
) {
    private class Edit(val pages: Map<String, Change>, val group: Any?)
    private class Change(val before: PageContent, val after: PageContent)

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private val dirty = mutableSetOf<String>()
    private var saveJob: Job? = null

    var pages by mutableStateOf<Map<String, PageContent>>(emptyMap())
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Bumped by [reset], so views reload the pages they show. */
    var generation by mutableIntStateOf(0)
        private set

    fun content(pageId: String): PageContent = pages[pageId] ?: PageContent.EMPTY

    fun strokes(pageId: String): List<InkStroke> = content(pageId).strokes

    /** Loads the given pages in the background, leaving pages that are already loaded alone. */
    suspend fun ensureLoaded(pageIds: Collection<String>) {
        val missing = pageIds.filter { it !in pages }
        if (missing.isEmpty()) return
        val loaded = withContext(io) { missing.associateWith(::loadOrEmpty) }
        pages = pages + loaded.filterKeys { it !in pages }
    }

    fun add(pageId: String, stroke: InkStroke) = update(pageId) { it.copy(strokes = it.strokes + stroke) }

    /** Replaces a page's strokes. Consecutive changes with the same non-null [group] are undone together. */
    fun replace(pageId: String, strokes: List<InkStroke>, group: Any? = null) = update(pageId, group) { it.copy(strokes = strokes) }

    /** Changes a page's content. Consecutive changes with the same non-null [group] are undone together. */
    fun update(pageId: String, group: Any? = null, change: (PageContent) -> PageContent) {
        val before = current(pageId)
        val after = change(before)
        if (before == after) return
        val top = undoStack.lastOrNull()
        if (group != null && top?.group == group) {
            val original = top.pages[pageId]?.before ?: before
            undoStack[undoStack.lastIndex] = Edit(top.pages + (pageId to Change(original, after)), group)
        } else {
            undoStack.addLast(Edit(mapOf(pageId to Change(before, after)), group))
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        redoStack.clear()
        set(pageId, after)
    }

    fun undo() {
        val edit = undoStack.removeLastOrNull() ?: return
        edit.pages.forEach { (pageId, change) -> set(pageId, change.before) }
        redoStack.addLast(edit)
        updateFlags()
    }

    fun redo() {
        val edit = redoStack.removeLastOrNull() ?: return
        edit.pages.forEach { (pageId, change) -> set(pageId, change.after) }
        undoStack.addLast(edit)
        updateFlags()
    }

    /** Forgets loaded pages and history, after pages changed on disk. Call [flush] first. */
    fun reset() {
        saveJob?.cancel()
        pages = emptyMap()
        undoStack.clear()
        redoStack.clear()
        updateFlags()
        generation++
    }

    /** Writes every unsaved page now. */
    suspend fun flush() {
        saveJob?.cancel()
        writeDirty()
    }

    private suspend fun writeDirty() {
        if (dirty.isEmpty()) return
        val changed = dirty.associateWith(::content)
        dirty.clear()
        try {
            withContext(io) { save(changed) }
        } catch (e: CancellationException) {
            dirty += changed.keys
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Saving pages failed", e)
            dirty += changed.keys
        }
    }

    private fun current(pageId: String): PageContent =
        pages[pageId] ?: loadOrEmpty(pageId).also { pages = pages + (pageId to it) }

    private fun loadOrEmpty(pageId: String): PageContent =
        runCatching { load(pageId) }.onFailure { Log.e(TAG, "Loading page $pageId failed", it) }.getOrDefault(PageContent.EMPTY)

    private fun set(pageId: String, content: PageContent) {
        pages = pages + (pageId to content)
        dirty += pageId
        updateFlags()
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(saveDelayMs)
            writeDirty()
        }
    }

    private fun updateFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private companion object {
        const val TAG = "PageSession"
        const val MAX_UNDO = 200
    }
}
