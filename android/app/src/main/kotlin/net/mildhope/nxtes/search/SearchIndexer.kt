package net.mildhope.nxtes.search

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.Library
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.pdf.PdfEngine

/** The words on a page that search should find, apart from its PDF text. */
fun searchableText(content: PageContent): String = buildList {
    content.objects.filterIsInstance<TextBox>().forEach { add(it.text) }
    content.notes.forEach { add(it.text) }
    content.marks.forEach { add(it.text) }
}.filter { it.isNotBlank() }.joinToString("\n")

/** Keeps the search index in step with the library, a while after each change, off the main thread. */
class SearchIndexer(private val index: SearchIndex, private val library: LibraryStore, private val pdf: PdfEngine, scope: CoroutineScope) {
    private val _version = MutableStateFlow(0)

    /** Goes up each time the index changes, so open searches can run again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            library.state.collectLatest { state ->
                if (!state.loaded) return@collectLatest
                delay(SETTLE_MS)
                runCatching { sync(state) }.onFailure { if (it is CancellationException) throw it else Log.w("Search", "Indexing failed", it) }
            }
        }
    }

    private suspend fun sync(state: Library) {
        val stamps = index.stamps()
        val ids = state.documents.map { it.id }.toSet()
        stamps.keys.filter { it !in ids }.forEach { index.remove(it); _version.value++ }
        for (doc in state.documents) {
            if (stamps[doc.id] == doc.modified) continue
            index.replace(doc.id, doc.modified, pageTexts(doc))
            _version.value++
            yield()
        }
    }

    private suspend fun pageTexts(doc: Document): List<String> = doc.pages.map { page ->
        yield()
        val pdfText = page.pdf?.let { source ->
            runCatching { pdf.text(library.blobFile(doc.id, source.blob), source.index).text }.getOrNull()
        }.orEmpty()
        val written = runCatching { searchableText(library.readPage(doc.id, page.id)) }.getOrDefault("")
        listOf(pdfText, written).filter { it.isNotBlank() }.joinToString("\n")
    }

    private companion object {
        const val SETTLE_MS = 2000L
    }
}
