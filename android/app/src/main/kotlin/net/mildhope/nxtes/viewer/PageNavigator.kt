package net.mildhope.nxtes.viewer

import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.CopiedPages
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.Folder
import net.mildhope.nxtes.data.Library
import net.mildhope.nxtes.data.LibraryItem
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.data.TemplateChoice
import net.mildhope.nxtes.library.DEFAULT_FOLDER_COLOR
import net.mildhope.nxtes.notes.CoverPick
import net.mildhope.nxtes.notes.NoteSettingsDialog
import net.mildhope.nxtes.nxtes
import net.mildhope.nxtes.page.openAsPdf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PageFilter { ALL, ANNOTATED }

private enum class FilesTab { DOCUMENTS, RECENTS, FAVORITES }

/** A page being dragged: [centre] is where it started, in root coordinates. */
/** What a picked template is for: the selected pages, or a new page at an index. */
private sealed interface TemplateUse {
    data object Selected : TemplateUse
    data class Insert(val index: Int) : TemplateUse
}

private class PageDrag(val key: String, val offset: Offset, val centre: Offset)

private const val ADD_KEY = "add"
private val CELL_WIDTH = 128.dp
private val CELL_HEIGHT = 150.dp
private val INSERT_WIDTH = 22.dp

/**
 * The page navigator from docs/ui.md: documents on the left and the shown document's pages on the
 * right. Edit mode selects pages to turn, duplicate or delete, and drags them to reorder.
 * [onEdit] runs a store change for a document, keeping the open document's ink in step.
 */
@Composable
fun PageNavigator(
    current: Document,
    currentPage: Int,
    onOpenPage: (documentId: String, pageIndex: Int) -> Unit,
    onEdit: (documentId: String, action: LibraryStore.() -> Unit) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.nxtes
    val library by app.library.state.collectAsState()
    var shownId by rememberSaveable { mutableStateOf(current.id) }
    val shown = library.document(shownId)?.takeIf { it.trashedAt == null && !library.isInTrash(it.folderId) } ?: current
    var filesOpen by rememberSaveable { mutableStateOf(true) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var filter by rememberSaveable { mutableStateOf(PageFilter.ALL) }
    var confirmDelete by remember { mutableStateOf(false) }
    var choosingTarget by remember { mutableStateOf(false) }
    var templateUse by remember { mutableStateOf<TemplateUse?>(null) }
    var insertAt by remember { mutableIntStateOf(0) }
    var dropTarget by remember { mutableStateOf<String?>(null) }
    val documentBounds = remember { mutableStateMapOf<String, Rect>() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val copied = app.copiedPages
    val pasteCount = copied?.takeIf { library.document(it.documentId)?.trashedAt == null }?.pageIds?.size ?: 0
    val selected = picked.intersect(shown.pages.map { it.id }.toSet())

    fun insertFile(uri: Uri?, image: Boolean) {
        uri ?: return
        val index = insertAt
        onEdit(shown.id) { openAsPdf(context, uri, image) { insertPdfPages(shown.id, index, it) } }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { insertFile(it, image = true) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { insertFile(it, image = false) }
    val annotated by produceState(emptySet<String>(), shown.id, shown.modified) {
        value = withContext(Dispatchers.IO) { app.library.annotatedPages(shown.id) }
    }

    fun stopEditing() {
        editing = false
        picked = emptySet()
    }

    BackHandler { if (editing) stopEditing() else onClose() }
    LaunchedEffect(shown.id) { picked = emptySet() }

    Surface(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (filesOpen) {
                DocumentsColumn(library, shown.id, dropTarget, current.folderId, documentBounds, onShow = { shownId = it })
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Column(Modifier.weight(1f)) {
                NavigatorHeader(
                    title = shown.title,
                    editing = editing,
                    selectedCount = selected.size,
                    allSelected = selected.size == shown.pages.size,
                    onToggleFiles = { filesOpen = !filesOpen },
                    onEdit = { editing = true },
                    onStopEditing = ::stopEditing,
                    onSelectAll = { picked = if (selected.size == shown.pages.size) emptySet() else shown.pages.map { it.id }.toSet() },
                    onClose = onClose,
                ) {
                    val enabled = selected.isNotEmpty()
                    ToolButton(painterResource(R.drawable.ic_template), stringResource(R.string.change_template), enabled) {
                        templateUse = TemplateUse.Selected
                    }
                    ToolButton(painterResource(R.drawable.ic_rotate_left), stringResource(R.string.turn_left), enabled) {
                        onEdit(shown.id) { turnPages(shown.id, selected, clockwise = false) }
                    }
                    ToolButton(painterResource(R.drawable.ic_rotate_right), stringResource(R.string.turn_right), enabled) {
                        onEdit(shown.id) { turnPages(shown.id, selected, clockwise = true) }
                    }
                    ToolButton(painterResource(R.drawable.ic_move_to), stringResource(R.string.move_pages), enabled) { choosingTarget = true }
                    ToolButton(painterResource(R.drawable.ic_duplicate), stringResource(R.string.duplicate), enabled) {
                        onEdit(shown.id) { duplicatePages(shown.id, selected) }
                    }
                    ToolButton(painterResource(R.drawable.ic_copy), stringResource(R.string.copy_pages), enabled) {
                        app.copiedPages = CopiedPages(shown.id, shown.pages.map { it.id }.filter { it in selected })
                        val message = resources.getQuantityString(R.plurals.pages_copied, selected.size, selected.size)
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    ToolButton(
                        rememberVectorPainter(Icons.Default.Delete),
                        stringResource(R.string.delete),
                        enabled,
                        tint = MaterialTheme.colorScheme.error,
                    ) { confirmDelete = true }
                }
                FilterTabs(filter) { filter = it }
                PageGrid(
                    doc = shown,
                    currentPage = if (shown.id == current.id) currentPage else -1,
                    filter = filter,
                    annotated = annotated,
                    editing = editing,
                    selected = selected,
                    onPage = { index ->
                        val id = shown.pages[index].id
                        when {
                            editing -> picked = if (id in selected) selected - id else selected + id
                            else -> onOpenPage(shown.id, index)
                        }
                    },
                    onLongPage = { index ->
                        editing = true
                        picked = setOf(shown.pages[index].id)
                    },
                    pasteCount = pasteCount,
                    onInsert = { index -> onEdit(shown.id) { insertPage(shown.id, index) } },
                    onInsertTemplate = { templateUse = TemplateUse.Insert(it) },
                    onInsertImage = {
                        insertAt = it
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onInsertPdf = {
                        insertAt = it
                        pdfPicker.launch(arrayOf("application/pdf"))
                    },
                    onPaste = { index: Int ->
                        copied?.let { clip -> onEdit(shown.id) { copyPages(clip.documentId, clip.pageIds, shown.id, index) } }
                    },
                    onReorder = { moving, toIndex -> onEdit(shown.id) { reorderPages(shown.id, moving, toIndex) } },
                    documentBounds = documentBounds,
                    onDropTarget = { dropTarget = it },
                    onMoveTo = { moving, target ->
                        picked = emptySet()
                        onEdit(shown.id) { movePages(shown.id, moving, target) }
                    },
                )
            }
        }
    }

    templateUse?.let { use ->
        NoteSettingsDialog(
            title = null,
            cover = CoverPick.FirstPage,
            choice = TemplateChoice.of(shown.template, app.libraryPrefs.lastTemplate),
            onDone = { _, _, choice ->
                templateUse = null
                when (use) {
                    TemplateUse.Selected -> onEdit(shown.id) { setPagesTemplate(shown.id, selected, choice) }
                    is TemplateUse.Insert -> onEdit(shown.id) { insertPage(shown.id, use.index, choice) }
                }
            },
            onDismiss = { templateUse = null },
        )
    }

    if (choosingTarget) {
        DocumentPickerDialog(
            library = library,
            excludeId = shown.id,
            onPick = { target ->
                choosingTarget = false
                val moving = selected
                picked = emptySet()
                onEdit(shown.id) { movePages(shown.id, moving, target) }
            },
            onDismiss = { choosingTarget = false },
        )
    }

    if (confirmDelete) {
        val everyPage = selected.size == shown.pages.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(pluralStringResource(R.plurals.delete_pages_title, selected.size, selected.size)) },
            text = { Text(stringResource(if (everyPage) R.string.delete_every_page else R.string.cannot_undo)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val doomed = selected
                    picked = emptySet()
                    onEdit(shown.id) { deletePages(shown.id, doomed) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun NavigatorHeader(
    title: String,
    editing: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onToggleFiles: () -> Unit,
    onEdit: () -> Unit,
    onStopEditing: () -> Unit,
    onSelectAll: () -> Unit,
    onClose: () -> Unit,
    tools: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        Row(Modifier.align(Alignment.CenterStart).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleFiles) {
                Icon(painterResource(R.drawable.ic_menu_open), stringResource(R.string.documents_panel), Modifier.rotate(180f))
            }
            if (editing) {
                IconButton(onClick = onStopEditing) { Icon(Icons.Default.Close, stringResource(R.string.done)) }
                Text(pluralStringResource(R.plurals.pages_selected, selectedCount, selectedCount), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onSelectAll) { Text(stringResource(if (allSelected) R.string.deselect_all else R.string.select_all)) }
            }
        }
        if (!editing) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 180.dp),
            )
        }
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                tools()
            } else {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.edit_pages)) }
                OutlinedButton(onClick = onClose, shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 16.dp)) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

@Composable
private fun ToolButton(painter: Painter, label: String, enabled: Boolean, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painter, label, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun FilterTabs(filter: PageFilter, onFilter: (PageFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        PageFilter.entries.forEach { option ->
            val active = option == filter
            Text(
                stringResource(if (option == PageFilter.ALL) R.string.filter_all else R.string.filter_annotated),
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clickable { onFilter(option) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageGrid(
    doc: Document,
    currentPage: Int,
    filter: PageFilter,
    annotated: Set<String>,
    editing: Boolean,
    selected: Set<String>,
    onPage: (Int) -> Unit,
    onLongPage: (Int) -> Unit,
    pasteCount: Int,
    onInsert: (Int) -> Unit,
    onInsertTemplate: (Int) -> Unit,
    onInsertImage: (Int) -> Unit,
    onInsertPdf: (Int) -> Unit,
    onPaste: (Int) -> Unit,
    onReorder: (moving: Set<String>, toIndex: Int) -> Unit,
    documentBounds: Map<String, Rect>,
    onDropTarget: (String?) -> Unit,
    onMoveTo: (moving: Set<String>, documentId: String) -> Unit,
) {
    val grid = rememberLazyGridState()
    var drag by remember { mutableStateOf<PageDrag?>(null) }
    val all = filter == PageFilter.ALL
    val shownPages = doc.pages.indices.filter { all || doc.pages[it].id in annotated }
    LaunchedEffect(doc.id) { if (currentPage >= 0) grid.scrollToItem(currentPage) }

    fun targetOf(moving: PageDrag): String? =
        documentBounds.entries.firstOrNull { (id, bounds) -> id != doc.id && bounds.contains(moving.centre + moving.offset) }?.key

    fun drop(finished: PageDrag) {
        val moving = if (finished.key in selected) selected else setOf(finished.key)
        val target = targetOf(finished)
        if (target != null) {
            onMoveTo(moving, target)
            return
        }
        val toIndex = dropIndex(grid, doc, finished) ?: return
        onReorder(moving, toIndex)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(CELL_WIDTH),
        state = grid,
        contentPadding = PaddingValues(start = 8.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(shownPages, key = { doc.pages[it].id }) { index ->
            val page = doc.pages[index]
            val dragging = drag?.takeIf { it.key == page.id }
            var bounds by remember { mutableStateOf(Rect.Zero) }
            Row(
                Modifier
                    .height(CELL_HEIGHT)
                    .onGloballyPositioned { bounds = it.boundsInRoot() }
                    .zIndex(if (dragging != null) 1f else 0f)
                    .graphicsLayer {
                        translationX = dragging?.offset?.x ?: 0f
                        translationY = dragging?.offset?.y ?: 0f
                        if (dragging != null) {
                            scaleX = 1.05f
                            scaleY = 1.05f
                        }
                    },
            ) {
                if (all) {
                    AddPageMenu(index, pasteCount, onInsert, onInsertTemplate, onInsertImage, onInsertPdf, onPaste) { open -> InsertButton(open) }
                } else {
                    Spacer(Modifier.width(INSERT_WIDTH))
                }
                val reorder = if (editing && all) {
                    Modifier.pointerInput(page.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { drag = PageDrag(page.id, Offset.Zero, bounds.center) },
                            onDrag = { change, amount ->
                                change.consume()
                                drag = drag?.let { PageDrag(it.key, it.offset + amount, it.centre) }?.also { onDropTarget(targetOf(it)) }
                            },
                            onDragEnd = {
                                drag?.let(::drop)
                                drag = null
                                onDropTarget(null)
                            },
                            onDragCancel = {
                                drag = null
                                onDropTarget(null)
                            },
                        )
                    }
                } else {
                    Modifier
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(reorder)
                        .combinedClickable(onClick = { onPage(index) }, onLongClick = if (editing) null else ({ onLongPage(index) })),
                ) {
                    PageCellContent(doc, index, current = index == currentPage, editing = editing, checked = page.id in selected)
                }
            }
        }
        if (all) {
            item(key = ADD_KEY) {
                Row(Modifier.height(CELL_HEIGHT)) {
                    Spacer(Modifier.width(INSERT_WIDTH))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        AddPageMenu(doc.pages.size, pasteCount, onInsert, onInsertTemplate, onInsertImage, onInsertPdf, onPaste) { open ->
                            AddPageTile(Modifier.fillMaxSize(), open)
                        }
                    }
                }
            }
        }
    }
}

/** Where a dragged page lands: before the page it's dropped on, or after it when moving forward. */
private fun dropIndex(grid: LazyGridState, doc: Document, drag: PageDrag): Int? {
    val items = grid.layoutInfo.visibleItemsInfo
    val dragged = items.firstOrNull { it.key == drag.key } ?: return null
    val centre = dragged.offset.toOffset() + Offset(dragged.size.width / 2f, dragged.size.height / 2f) + drag.offset
    val target = items.firstOrNull { Rect(it.offset.toOffset(), it.size.toSize()).contains(centre) } ?: return null
    if (target.key == drag.key) return null
    if (target.key == ADD_KEY) return doc.pages.size
    val from = doc.pages.indexOfFirst { it.id == drag.key }
    val to = doc.pages.indexOfFirst { it.id == target.key }
    if (to < 0) return null
    return if (to > from) to + 1 else to
}

@Composable
private fun PageCellContent(doc: Document, index: Int, current: Boolean, editing: Boolean, checked: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        PageThumbnail(
            doc,
            index,
            Modifier.border(
                if (current) 2.dp else 1.dp,
                if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        )
        if (editing) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (checked) 0f else 0.25f)))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (checked) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            ) {
                if (checked) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            }
        }
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = if (current || checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.75f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        ) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = if (current || checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
            )
        }
    }
}

/** A menu to add pages at [index]: blank, from another template, from an image or PDF, or pasted. */
@Composable
private fun AddPageMenu(
    index: Int,
    pasteCount: Int,
    onBlank: (Int) -> Unit,
    onTemplate: (Int) -> Unit,
    onImage: (Int) -> Unit,
    onPdf: (Int) -> Unit,
    onPaste: (Int) -> Unit,
    anchor: @Composable (open: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    @Composable
    fun Item(label: String, icon: Painter, enabled: Boolean = true, action: (Int) -> Unit) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(icon, null) },
            enabled = enabled,
            onClick = {
                open = false
                action(index)
            },
        )
    }
    Box {
        anchor { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Item(stringResource(R.string.blank_page), rememberVectorPainter(Icons.Default.Add), action = onBlank)
            Item(stringResource(R.string.page_from_template), painterResource(R.drawable.ic_template), action = onTemplate)
            Item(stringResource(R.string.page_from_image), painterResource(R.drawable.ic_image), action = onImage)
            Item(stringResource(R.string.page_from_pdf), painterResource(R.drawable.ic_document), action = onPdf)
            DropdownMenuItem(
                text = {
                    Text(
                        if (pasteCount > 0) {
                            pluralStringResource(R.plurals.paste_pages, pasteCount, pasteCount)
                        } else {
                            stringResource(R.string.paste_nothing)
                        },
                    )
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_paste), null) },
                enabled = pasteCount > 0,
                onClick = {
                    open = false
                    onPaste(index)
                },
            )
        }
    }
}

@Composable
private fun InsertButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.width(INSERT_WIDTH).fillMaxHeight().clickable(onClickLabel = stringResource(R.string.insert_page), onClick = onClick),
    ) {
        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun AddPageTile(modifier: Modifier, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 10.dp)
            .drawBehind {
                drawRoundRect(
                    outline,
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .clickable(onClickLabel = stringResource(R.string.add_page), onClick = onClick),
    ) {
        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The navigator's left column: browse folders, recents or favourites, and pick a document to show. */
@Composable
private fun DocumentsColumn(
    library: Library,
    shownId: String,
    dropTargetId: String?,
    startFolder: String?,
    bounds: MutableMap<String, Rect>,
    onShow: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(FilesTab.DOCUMENTS) }
    var folderId by rememberSaveable { mutableStateOf(startFolder) }
    val folder = library.folder(folderId)?.takeIf { !library.isInTrash(it.id) }
    val items: List<LibraryItem> = when (tab) {
        FilesTab.DOCUMENTS -> library.childFolders(folder?.id) + library.childDocuments(folder?.id).sortedBy { it.title.lowercase() }
        FilesTab.RECENTS -> library.recents()
        FilesTab.FAVORITES -> library.favorites()
    }
    Column(Modifier.width(288.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.CenterStart) {
            if (tab == FilesTab.DOCUMENTS && folder != null) {
                TextButton(onClick = { folderId = folder.parentId }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
                    Text(library.folder(folder.parentId)?.name ?: stringResource(R.string.documents), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            when (tab) {
                FilesTab.DOCUMENTS -> folder?.name ?: stringResource(R.string.documents)
                FilesTab.RECENTS -> stringResource(R.string.recents)
                FilesTab.FAVORITES -> stringResource(R.string.favorites)
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.id }) { item ->
                when (item) {
                    is Folder -> FolderRow(item) {
                        tab = FilesTab.DOCUMENTS
                        folderId = item.id
                    }
                    is Document -> DocumentRow(item, item.id == shownId, item.id == dropTargetId, bounds) { onShow(item.id) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            FilesTab.entries.forEach { option ->
                val active = option == tab
                val colour = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable { tab = option },
                ) {
                    Icon(
                        when (option) {
                            FilesTab.DOCUMENTS -> painterResource(R.drawable.ic_document)
                            FilesTab.RECENTS -> painterResource(R.drawable.ic_schedule)
                            FilesTab.FAVORITES -> rememberVectorPainter(Icons.Default.Star)
                        },
                        null,
                        tint = colour,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(
                            when (option) {
                                FilesTab.DOCUMENTS -> R.string.documents
                                FilesTab.RECENTS -> R.string.recents
                                FilesTab.FAVORITES -> R.string.favorites
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = colour,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: Folder, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
    ) {
        Icon(painterResource(R.drawable.ic_folder), null, tint = Color(folder.color ?: DEFAULT_FOLDER_COLOR), modifier = Modifier.size(28.dp))
        Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun DocumentRow(doc: Document, shown: Boolean, dropTarget: Boolean, bounds: MutableMap<String, Rect>, onClick: () -> Unit) {
    val date = remember(doc.modified) { DATE_FORMAT.format(Date(doc.modified)) }
    DisposableEffect(doc.id) { onDispose { bounds.remove(doc.id) } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .onGloballyPositioned { bounds[doc.id] = it.boundsInRoot() }
            .background(
                when {
                    dropTarget -> MaterialTheme.colorScheme.primaryContainer
                    shown -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(doc.title, fontWeight = if (shown) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(R.plurals.page_count, doc.pages.size, doc.pages.size) + "  " + date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.size(width = 40.dp, height = 48.dp), contentAlignment = Alignment.Center) {
            PageThumbnail(doc, 0, Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant), widthPx = 120)
        }
    }
}

private val DATE_FORMAT = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT)
