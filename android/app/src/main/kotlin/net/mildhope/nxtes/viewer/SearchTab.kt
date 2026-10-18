package net.mildhope.nxtes.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.edit.PageText
import net.mildhope.nxtes.edit.SelectionState
import net.mildhope.nxtes.ink.PageSession

/** Search the open document; tapping a match goes to it and marks it on the page. */
@Composable
fun ColumnScope.SearchTab(
    doc: Document,
    search: DocumentSearch,
    ink: PageSession,
    pageText: suspend (Int) -> PageText?,
    viewport: ViewportState,
    selection: SelectionState,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (search.query.isEmpty()) focus.requestFocus() }
    LaunchedEffect(search.query, doc.pages) {
        delay(SEARCH_DELAY_MS)
        search.run(doc, ink, pageText)
    }
    OutlinedTextField(
        value = search.query,
        onValueChange = { search.query = it },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_document)) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (search.query.isEmpty()) null else {
            { IconButton(onClick = { search.query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search.run(doc, ink, pageText, force = true) }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).focusRequester(focus),
    )
    if (search.query.isNotBlank()) {
        Text(
            when {
                search.searched < search.pageCount -> stringResource(R.string.searching, search.searched, search.pageCount)
                search.hits.isEmpty() -> stringResource(R.string.search_no_results)
                else -> pluralStringResource(R.plurals.search_matches, search.hits.size, search.hits.size)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val accent = MaterialTheme.colorScheme.primary
    val sources = mapOf(
        HitSource.PDF to stringResource(R.string.hit_pdf),
        HitSource.TEXT_BOX to stringResource(R.string.text_box),
        HitSource.NOTE to stringResource(R.string.sticky_note),
        HitSource.MARK to stringResource(R.string.highlight),
    )
    val pageTitle = LocalContext.current.resources
    LazyColumn(Modifier.weight(1f)) {
        var lastPage = -1
        search.hits.forEachIndexed { n, hit ->
            if (hit.pageIndex != lastPage) {
                lastPage = hit.pageIndex
                item(key = "page-${hit.pageIndex}") {
                    Text(
                        pageTitle.getString(R.string.page_title, hit.pageIndex + 1),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            item(key = n) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            selection.searchMark = hit.pageId to hit.rects
                            val page = doc.pages.getOrNull(hit.pageIndex)
                            val focusPoint = if (page != null && hit.rects.size >= 4) Offset(hit.rects[0] / page.width, hit.rects[1] / page.height) else null
                            viewport.goToPage(hit.pageIndex, focusPoint)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        buildAnnotatedString {
                            append(hit.snippet.substring(0, hit.matchStart))
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) {
                                append(hit.snippet.substring(hit.matchStart, hit.matchStart + hit.matchLength))
                            }
                            append(hit.snippet.substring(hit.matchStart + hit.matchLength))
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(sources.getValue(hit.source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private const val SEARCH_DELAY_MS = 300L
