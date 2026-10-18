package net.mildhope.nxtes.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.edit.Geometry
import net.mildhope.nxtes.edit.PageText
import net.mildhope.nxtes.ink.PageSession
import net.mildhope.nxtes.page.PagePainter

/** Where a match was found. */
enum class HitSource { PDF, TEXT_BOX, NOTE, MARK }

/**
 * A match on a page: a snippet of the text around it, where the match sits in the snippet, and the
 * rectangles to mark on the page (x, y, width, height each, in page points).
 */
class SearchHit(
    val pageIndex: Int,
    val pageId: String,
    val source: HitSource,
    val snippet: String,
    val matchStart: Int,
    val matchLength: Int,
    val rects: FloatArray,
)

/** Every case-insensitive match of [query] in [text], as start indices. */
fun matches(text: String, query: String): List<Int> {
    if (query.isBlank()) return emptyList()
    val out = mutableListOf<Int>()
    var at = text.indexOf(query, ignoreCase = true)
    while (at >= 0) {
        out += at
        at = text.indexOf(query, at + query.length, ignoreCase = true)
    }
    return out
}

/** A snippet of [text] around a match at [start], on one line, and where the match begins in it. */
fun snippet(text: String, start: Int, length: Int): Pair<String, Int> {
    val from = (start - SNIPPET_BEFORE).coerceAtLeast(0)
    val to = (start + length + SNIPPET_AFTER).coerceAtMost(text.length)
    val prefix = if (from > 0) "…" else ""
    val suffix = if (to < text.length) "…" else ""
    fun flat(part: String) = part.replace(WHITESPACE, " ")
    val before = flat(text.substring(from, start))
    val rest = flat(text.substring(start, start + length)) + flat(text.substring(start + length, to))
    return prefix + before + rest + suffix to prefix.length + before.length
}

private val WHITESPACE = Regex("\\s+")

/** Finds text in an open document: its PDF text, text boxes, sticky notes and marked text. */
@Stable
class DocumentSearch(private val scope: CoroutineScope) {
    var query by mutableStateOf("")
    val hits = mutableStateListOf<SearchHit>()

    /** Pages searched so far, out of [pageCount]; they're equal when the search is done. */
    var searched by mutableIntStateOf(0)
        private set
    var pageCount by mutableIntStateOf(0)
        private set
    private var job: Job? = null
    private var last: Pair<String, Any>? = null

    /** Searches for [query], unless that search already ran on these pages and [force] is off. */
    fun run(doc: Document, ink: PageSession, pageText: suspend (Int) -> PageText?, force: Boolean = false) {
        val key = query.trim() to doc.pages
        if (!force && key == last) return
        last = key
        job?.cancel()
        hits.clear()
        searched = 0
        pageCount = doc.pages.size
        val q = query.trim()
        if (q.isEmpty()) {
            searched = pageCount
            return
        }
        job = scope.launch {
            ink.ensureLoaded(doc.pages.map { it.id })
            doc.pages.forEachIndexed { i, page ->
                pageText(i)?.let { text -> hits += pdfHits(i, page.id, text, q) }
                hits += contentHits(i, page.id, ink.content(page.id), q)
                searched = i + 1
            }
        }
    }

    private fun pdfHits(index: Int, pageId: String, text: PageText, q: String) = matches(text.text, q).map { start ->
        val (snip, at) = snippet(text.text, start, q.length)
        SearchHit(index, pageId, HitSource.PDF, snip, at, q.length, text.rects(start, start + q.length - 1))
    }

    private fun contentHits(index: Int, pageId: String, content: PageContent, q: String): List<SearchHit> = buildList {
        fun add(source: HitSource, text: String, rects: FloatArray) = matches(text, q).forEach { start ->
            val (snip, at) = snippet(text, start, q.length)
            add(SearchHit(index, pageId, source, snip, at, q.length, rects))
        }
        content.objects.filterIsInstance<TextBox>().forEach { box ->
            val b = Geometry.bounds(box)
            add(HitSource.TEXT_BOX, box.text, floatArrayOf(b.left, b.top, b.right - b.left, b.bottom - b.top))
        }
        content.notes.forEach { add(HitSource.NOTE, it.text, floatArrayOf(it.x, it.y, PagePainter.NOTE_SIZE, PagePainter.NOTE_SIZE)) }
        content.marks.forEach { add(HitSource.MARK, it.text, it.rects) }
    }
}

private const val SNIPPET_BEFORE = 30
private const val SNIPPET_AFTER = 60
