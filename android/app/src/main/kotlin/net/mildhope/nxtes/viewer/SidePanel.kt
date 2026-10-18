package net.mildhope.nxtes.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.data.OutlineItem
import net.mildhope.nxtes.edit.SelectionState
import net.mildhope.nxtes.ink.PageSession
import net.mildhope.nxtes.library.NameDialog
import net.mildhope.nxtes.library.runLibraryAction
import net.mildhope.nxtes.nxtes

enum class SideTab { PAGES, OUTLINE, BOOKMARKS, ANNOTATIONS, MASKS }

/** An outline row: the PDF's own entries are read-only, the user's ([item]) can be edited. */
private class OutlineRow(val title: String, val pageIndex: Int, val depth: Int, val item: OutlineItem? = null)

private sealed interface PanelDialog {
    data object AddBookmark : PanelDialog
    data class RenameBookmark(val id: String, val title: String) : PanelDialog
    data object AddOutline : PanelDialog
    data class EditOutline(val item: OutlineItem) : PanelDialog
}

/** The panel that slides in from the right: page thumbnails, the outline and bookmarks. */
@Composable
fun SidePanel(
    doc: Document,
    viewport: ViewportState,
    ink: PageSession,
    selection: SelectionState,
    tab: SideTab,
    onTab: (SideTab) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<PanelDialog?>(null) }
    fun edit(action: LibraryStore.() -> Unit) {
        dialog = null
        scope.launch { runLibraryAction(context, action) }
    }
    val tabs = listOf(
        Triple(SideTab.PAGES, painterResource(R.drawable.ic_grid_view), stringResource(R.string.pages)),
        Triple(SideTab.OUTLINE, rememberVectorPainter(Icons.AutoMirrored.Filled.List), stringResource(R.string.outline)),
        Triple(SideTab.BOOKMARKS, painterResource(R.drawable.ic_bookmark), stringResource(R.string.bookmarks)),
        Triple(SideTab.ANNOTATIONS, painterResource(R.drawable.ic_annotations), stringResource(R.string.annotations)),
        Triple(SideTab.MASKS, painterResource(R.drawable.ic_masking), stringResource(R.string.masking)),
    )

    Surface(shadowElevation = 8.dp, modifier = modifier.width(320.dp).fillMaxHeight()) {
        Column {
            Box(Modifier.fillMaxWidth().height(48.dp)) {
                Text(
                    tabs.first { it.first == tab }.third,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Default.Close, stringResource(R.string.close))
                }
            }
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { (option, icon, label) -> SideTabButton(icon, label, option == tab) { onTab(option) } }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (tab) {
                SideTab.PAGES -> PageGrid(doc, viewport)
                SideTab.MASKS -> MaskList(doc, ink, selection, viewport)
                SideTab.ANNOTATIONS -> AnnotationList(doc, ink, viewport)
                SideTab.OUTLINE -> Outline(doc, viewport, { dialog = PanelDialog.AddOutline }, { dialog = PanelDialog.EditOutline(it) }) {
                    edit { deleteOutline(doc.id, it.id) }
                }
                SideTab.BOOKMARKS -> Bookmarks(
                    doc,
                    viewport,
                    onAdd = { dialog = PanelDialog.AddBookmark },
                    onRename = { id, title -> dialog = PanelDialog.RenameBookmark(id, title) },
                    onDelete = { edit { deleteBookmark(doc.id, it) } },
                )
            }
        }
    }

    val current = viewport.currentPage.coerceIn(doc.pages.indices)
    val pageTitle = stringResource(R.string.page_title, current + 1)
    when (val d = dialog) {
        PanelDialog.AddBookmark -> NameDialog(stringResource(R.string.add_bookmark), pageTitle, { title ->
            edit { addBookmark(doc.id, doc.pages[current].id, title) }
        }, { dialog = null })
        is PanelDialog.RenameBookmark -> NameDialog(stringResource(R.string.rename_bookmark), d.title, { title ->
            edit { renameBookmark(doc.id, d.id, title) }
        }, { dialog = null })
        PanelDialog.AddOutline -> OutlineDialog(stringResource(R.string.add_outline), pageTitle, current, doc.pages.size, { title, page ->
            edit { addOutline(doc.id, doc.pages[page].id, title) }
        }, { dialog = null })
        is PanelDialog.EditOutline -> {
            val page = doc.pages.indexOfFirst { it.id == d.item.pageId }.coerceAtLeast(0)
            OutlineDialog(stringResource(R.string.edit_outline), d.item.title, page, doc.pages.size, { title, index ->
                edit { updateOutline(doc.id, d.item.id, doc.pages[index].id, title) }
            }, { dialog = null })
        }
        null -> Unit
    }
}

@Composable
private fun RowScope.SideTabButton(icon: Painter, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = label, onClick = onClick),
    ) {
        Icon(icon, label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun PageGrid(doc: Document, viewport: ViewportState) {
    val grid = rememberLazyGridState()
    LaunchedEffect(doc.id) { grid.scrollToItem(viewport.currentPage) }
    PageJump(doc.pages.size, viewport::goToPage, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = grid,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(doc.pages.size, key = { doc.pages[it].id }) { index ->
            val current = index == viewport.currentPage
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { viewport.goToPage(index) }) {
                PageThumbnail(
                    doc,
                    index,
                    Modifier.fillMaxWidth().border(
                        if (current) 2.dp else 1.dp,
                        if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** The PDFs' own outlines merged with the user's entries, in page order. */
@Composable
private fun ColumnScope.Outline(
    doc: Document,
    viewport: ViewportState,
    onAdd: () -> Unit,
    onEdit: (OutlineItem) -> Unit,
    onDelete: (OutlineItem) -> Unit,
) {
    val context = LocalContext.current
    val pdfRows by produceState<List<OutlineRow>?>(null, doc.id, doc.pages) {
        val app = context.nxtes
        value = doc.pages.mapNotNull { it.pdf?.blob }.distinct().flatMap { blob ->
            val entries = runCatching { app.pdf.outline(app.library.blobFile(doc.id, blob)) }.getOrDefault(emptyList())
            entries.mapNotNull { entry ->
                val index = doc.pages.indexOfFirst { it.pdf?.blob == blob && it.pdf.index == entry.pdfPage }
                if (index < 0) null else OutlineRow(entry.title, index, entry.depth)
            }
        }
    }
    val pdf = pdfRows ?: return
    val user = doc.outline.mapNotNull { item ->
        val index = doc.pages.indexOfFirst { it.id == item.pageId }
        if (index < 0) null else OutlineRow(item.title, index, 0, item)
    }
    val rows = (pdf + user).sortedBy { it.pageIndex }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (rows.isEmpty()) {
            EmptyMessage(stringResource(R.string.no_outline))
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(rows) { row ->
                    EntryRow(
                        title = row.title,
                        pageIndex = row.pageIndex,
                        depth = row.depth,
                        onClick = { viewport.goToPage(row.pageIndex) },
                        menu = row.item?.let { item ->
                            listOf(stringResource(R.string.edit_outline) to { onEdit(item) }, stringResource(R.string.delete) to { onDelete(item) })
                        },
                    )
                }
            }
        }
    }
    AddRow(stringResource(R.string.add_outline), onAdd)
}

@Composable
private fun ColumnScope.Bookmarks(
    doc: Document,
    viewport: ViewportState,
    onAdd: () -> Unit,
    onRename: (id: String, title: String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val marks = doc.bookmarks
        .map { mark -> mark to doc.pages.indexOfFirst { it.id == mark.pageId } }
        .filter { it.second >= 0 }
        .sortedBy { it.second }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (marks.isEmpty()) {
            EmptyMessage(stringResource(R.string.no_bookmarks))
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(marks, key = { it.first.id }) { (mark, index) ->
                    EntryRow(
                        title = mark.title,
                        pageIndex = index,
                        depth = 0,
                        onClick = { viewport.goToPage(index) },
                        menu = listOf(
                            stringResource(R.string.rename) to { onRename(mark.id, mark.title) },
                            stringResource(R.string.delete) to { onDelete(mark.id) },
                        ),
                    )
                }
            }
        }
    }
    AddRow(stringResource(R.string.add_bookmark), onAdd)
}

/** A title and its page number; touch and hold opens [menu] when there is one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(title: String, pageIndex: Int, depth: Int, onClick: () -> Unit, menu: List<Pair<String, () -> Unit>>?) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .combinedClickable(onClick = onClick, onLongClick = menu?.let { { open = true } })
                .padding(start = (16 + depth * 16).dp, end = 16.dp),
        ) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${pageIndex + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (menu != null) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                menu.forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        open = false
                        action()
                    })
                }
            }
        }
    }
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick),
    ) {
        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Asks for an outline entry's name and page. */
@Composable
private fun OutlineDialog(
    heading: String,
    initialTitle: String,
    initialPage: Int,
    pageCount: Int,
    onConfirm: (title: String, pageIndex: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var page by remember { mutableStateOf("${initialPage + 1}") }
    val index = page.toIntOrNull()?.takeIf { it in 1..pageCount }?.minus(1)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.outline_name)) }, singleLine = true)
                OutlinedTextField(
                    page,
                    { page = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.outline_page)) },
                    supportingText = { Text(stringResource(R.string.page_range, pageCount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { index?.let { onConfirm(title.trim(), it) } }, enabled = index != null && title.isNotBlank()) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
