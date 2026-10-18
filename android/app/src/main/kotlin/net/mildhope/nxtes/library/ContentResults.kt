package net.mildhope.nxtes.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Library
import net.mildhope.nxtes.search.ContentHit
import net.mildhope.nxtes.search.MATCH_END
import net.mildhope.nxtes.search.MATCH_START

/** A snippet with its matches in bold [accent]. */
fun highlighted(snippet: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var rest = snippet.replace(Regex("\\s+"), " ")
    while (true) {
        val start = rest.indexOf(MATCH_START)
        val end = rest.indexOf(MATCH_END, start + 1)
        if (start < 0 || end < 0) {
            append(rest.replace(MATCH_START.toString(), "").replace(MATCH_END.toString(), ""))
            return@buildAnnotatedString
        }
        append(rest.substring(0, start))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) { append(rest.substring(start + 1, end)) }
        rest = rest.substring(end + 1)
    }
}

/** Pages whose words match the search, grouped by document; tapping one opens the document there. */
@Composable
fun ContentResults(library: Library, hits: List<ContentHit>, onOpenPage: (documentId: String, pageIndex: Int) -> Unit) {
    if (hits.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.search_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val accent = MaterialTheme.colorScheme.primary
    val byDocument = hits.groupBy { it.documentId }
        .mapNotNull { (id, pages) -> library.document(id)?.let { it to pages } }
        .sortedBy { (doc, _) -> doc.title.lowercase() }
    LazyColumn(Modifier.fillMaxSize()) {
        byDocument.forEach { (doc, pages) ->
            item(key = doc.id) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            pages.forEach { hit ->
                item(key = "${doc.id}-${hit.pageIndex}") {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPage(doc.id, hit.pageIndex) }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.page_title, hit.pageIndex + 1),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(72.dp).padding(top = 2.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(highlighted(hit.snippet, accent), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
