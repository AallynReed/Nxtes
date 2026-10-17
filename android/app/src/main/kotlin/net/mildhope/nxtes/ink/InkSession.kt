package net.mildhope.nxtes.ink

import android.util.Log
import androidx.compose.runtime.getValue
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

/**
 * The ink of one open document. Pages load on demand, every change can be undone, and changed
 * pages are saved shortly after the last edit. Call from the main thread.
 */
class InkSession(
    private val load: (pageId: String) -> List<InkStroke>,
    private val save: (Map<String, List<InkStroke>>) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val saveDelayMs: Long = 1000,
) {
    private class Edit(val pages: Map<String, Change>, val group: Any?)
    private class Change(val before: List<InkStroke>, val after: List<InkStroke>)

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private val dirty = mutableSetOf<String>()
    private var saveJob: Job? = null

    var pages by mutableStateOf<Map<String, List<InkStroke>>>(emptyMap())
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    fun strokes(pageId: String): List<InkStroke> = pages[pageId].orEmpty()

    /** Loads the given pages in the background, leaving pages that are already loaded alone. */
    suspend fun ensureLoaded(pageIds: Collection<String>) {
        val missing = pageIds.filter { it !in pages }
        if (missing.isEmpty()) return
        val loaded = withContext(io) { missing.associateWith(::loadOrEmpty) }
        pages = pages + loaded.filterKeys { it !in pages }
    }

    fun add(pageId: String, stroke: InkStroke) = replace(pageId, current(pageId) + stroke)

    /** Replaces a page's strokes. Consecutive changes with the same non-null [group] are undone together. */
    fun replace(pageId: String, strokes: List<InkStroke>, group: Any? = null) {
        val before = current(pageId)
        if (before == strokes) return
        val top = undoStack.lastOrNull()
        if (group != null && top?.group == group) {
            val original = top.pages[pageId]?.before ?: before
            undoStack[undoStack.lastIndex] = Edit(top.pages + (pageId to Change(original, strokes)), group)
        } else {
            undoStack.addLast(Edit(mapOf(pageId to Change(before, strokes)), group))
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        redoStack.clear()
        set(pageId, strokes)
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

    /** Writes every unsaved page now. */
    suspend fun flush() {
        saveJob?.cancel()
        writeDirty()
    }

    private suspend fun writeDirty() {
        if (dirty.isEmpty()) return
        val changed = dirty.associateWith(::strokes)
        dirty.clear()
        try {
            withContext(io) { save(changed) }
        } catch (e: CancellationException) {
            dirty += changed.keys
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Saving ink failed", e)
            dirty += changed.keys
        }
    }

    private fun current(pageId: String): List<InkStroke> =
        pages[pageId] ?: loadOrEmpty(pageId).also { pages = pages + (pageId to it) }

    private fun loadOrEmpty(pageId: String): List<InkStroke> =
        runCatching { load(pageId) }.onFailure { Log.e(TAG, "Loading ink for $pageId failed", it) }.getOrDefault(emptyList())

    private fun set(pageId: String, strokes: List<InkStroke>) {
        pages = pages + (pageId to strokes)
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
        const val TAG = "InkSession"
        const val MAX_UNDO = 200
    }
}
