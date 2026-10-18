package net.mildhope.nxtes.library

import androidx.compose.material3.FilterChip
import kotlinx.coroutines.delay
import net.mildhope.nxtes.search.ContentHit
import net.mildhope.nxtes.export.ExportDialog
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.Folder
import net.mildhope.nxtes.data.Library
import net.mildhope.nxtes.data.LibraryItem
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.data.TemplateChoice
import net.mildhope.nxtes.notes.CoverPick
import net.mildhope.nxtes.notes.NoteSettingsDialog
import net.mildhope.nxtes.notes.importCover
import net.mildhope.nxtes.notes.TemplateScopeDialog
import net.mildhope.nxtes.nxtes
import net.mildhope.nxtes.viewer.PageThumbnail

private sealed interface LibraryDialog {
    data object NewFolder : LibraryDialog
    data object NewNote : LibraryDialog
    data class Settings(val doc: Document) : LibraryDialog
    data class ApplyTemplate(val doc: Document, val choice: TemplateChoice) : LibraryDialog
    data class Rename(val item: LibraryItem) : LibraryDialog
    data class Move(val items: List<LibraryItem>) : LibraryDialog
    data class Merge(val ids: List<String>, val title: String) : LibraryDialog
    data class DeleteSelected(val items: List<LibraryItem>) : LibraryDialog
    data class Colour(val folder: Folder) : LibraryDialog
    data class DeleteForever(val item: LibraryItem) : LibraryDialog
    data object EmptyTrash : LibraryDialog
    data class Export(val doc: Document) : LibraryDialog
}

/** The library: side menu on the left, the selected section on the right. See docs/ui.md. */
@Composable
fun LibraryScreen(
    library: Library,
    folderId: String?,
    onOpenFolder: (String?) -> Unit,
    onOpenDocument: (String) -> Unit,
    onOpenPage: (documentId: String, pageIndex: Int) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(LibrarySection.DOCUMENTS) }
    var query by rememberSaveable { mutableStateOf("") }
    var shortcutsShowFavorites by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val folder = library.folder(folderId)

    fun open(item: LibraryItem) {
        when (item) {
            is Folder -> {
                query = ""
                section = LibrarySection.DOCUMENTS
                onOpenFolder(item.id)
            }
            is Document -> onOpenDocument(item.id)
        }
    }

    BackHandler(enabled = query.isNotEmpty() || section != LibrarySection.DOCUMENTS || folder != null) {
        when {
            query.isNotEmpty() -> query = ""
            section != LibrarySection.DOCUMENTS -> section = LibrarySection.DOCUMENTS
            else -> onOpenFolder(folder?.parentId)
        }
    }

    val sideMenu = @Composable {
        LibrarySideMenu(
            library = library,
            section = section,
            query = query,
            onQuery = { query = it },
            onSection = { section = it },
            shortcutsShowFavorites = shortcutsShowFavorites,
            onShortcutsShowFavorites = { shortcutsShowFavorites = it },
            onOpen = ::open,
            onSettings = { settingsOpen = true },
        )
    }
    if (settingsOpen) SettingsDialog(onDismiss = { settingsOpen = false })

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        if (maxWidth < 600.dp) {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(drawerState = drawer, drawerContent = { ModalDrawerSheet { sideMenu() } }) {
                MainPane(library, section, query, folder, ::open, onOpenFolder, onOpenPage, onMenu = { scope.launch { drawer.open() } })
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                sideMenu()
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MainPane(library, section, query, folder, ::open, onOpenFolder, onOpenPage, onMenu = null)
            }
        }
    }
}

@Composable
private fun MainPane(
    library: Library,
    section: LibrarySection,
    query: String,
    folder: Folder?,
    onOpen: (LibraryItem) -> Unit,
    onOpenFolder: (String?) -> Unit,
    onOpenPage: (documentId: String, pageIndex: Int) -> Unit,
    onMenu: (() -> Unit)?,
) {
    val context = LocalContext.current
    val prefs = context.nxtes.libraryPrefs
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var filter by rememberSaveable { mutableStateOf(KindFilter.ALL) }
    var importing by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(listOf<String>()) }

    fun stopSelecting() {
        selecting = false
        selection = emptyList()
    }

    fun edit(action: LibraryStore.() -> Unit) {
        dialog = null
        scope.launch { runLibraryAction(context, action) }
    }

    val copyName = stringResource(R.string.copy_title)
    fun duplicate(ids: List<String>) = edit { ids.forEach { duplicate(it) { name -> copyName.format(name) } } }

    BackHandler(enabled = selecting, onBack = ::stopSelecting)
    LaunchedEffect(section, query, folder?.id) { stopSelecting() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importing = true
                importFiles(context, uris, folder?.id)
                importing = false
            }
        }
    }

    val flexcilPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                importFlexcil(context, uri, folder?.id)
                importing = false
            }
        }
    }

    val searching = query.isNotBlank()
    val inTrash = section == LibrarySection.TRASH && !searching
    val title = when {
        searching -> stringResource(R.string.search_results)
        section == LibrarySection.TRASH -> stringResource(R.string.trash_title)
        section == LibrarySection.RECENTS -> stringResource(R.string.recents)
        section == LibrarySection.FAVORITES -> stringResource(R.string.favorites)
        else -> folder?.name ?: stringResource(R.string.documents)
    }
    val items = when {
        searching -> prefs.arrange(library.search(query))
        section == LibrarySection.TRASH -> library.trash()
        section == LibrarySection.RECENTS -> library.recents()
        section == LibrarySection.FAVORITES -> prefs.arrange(library.favorites())
        else -> prefs.arrange(library.childFolders(folder?.id) + library.childDocuments(folder?.id), filter)
    }
    val indexVersion by context.nxtes.indexer.version.collectAsState()
    val contentHits by produceState(emptyList<ContentHit>(), query, indexVersion) {
        if (query.isBlank()) {
            value = emptyList()
            return@produceState
        }
        delay(SEARCH_DELAY_MS)
        value = withContext(Dispatchers.IO) { runCatching { context.nxtes.searchIndex.search(query) }.getOrDefault(emptyList()) }
    }
    val visibleHits = contentHits.filter { hit -> library.document(hit.documentId)?.let { it.trashedAt == null && !library.isInTrash(it.folderId) } == true }
    var searchContents by rememberSaveable { mutableStateOf(false) }
    val showContents = searching && (searchContents || (items.isEmpty() && visibleHits.isNotEmpty()))
    val showsFolder = section == LibrarySection.DOCUMENTS && !searching
    val selected = selection.mapNotNull { id -> items.find { it.id == id } }
    val mergedName = stringResource(R.string.merged_title)

    val renameLabel = stringResource(R.string.rename)
    val settingsLabel = stringResource(R.string.cover_and_template)
    val exportLabel = stringResource(R.string.export)
    val moveLabel = stringResource(R.string.move)
    val duplicateLabel = stringResource(R.string.duplicate)
    val colourLabel = stringResource(R.string.folder_colour)
    val favoriteLabel = stringResource(R.string.add_to_favorites)
    val unfavoriteLabel = stringResource(R.string.remove_from_favorites)
    val trashLabel = stringResource(R.string.move_to_trash)
    val restoreLabel = stringResource(R.string.restore)
    val deleteLabel = stringResource(R.string.delete)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TitleBar(
                title = title,
                onMenu = onMenu,
                leading = {
                    when {
                        selecting && selected.isEmpty() -> TextButton(onClick = { selection = items.map { it.id } }) {
                            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(20.dp))
                            Text(stringResource(R.string.select_all), Modifier.padding(start = 6.dp))
                        }
                        selecting -> {
                            IconButton(onClick = { selection = emptyList() }) { Icon(Icons.Default.Close, stringResource(R.string.deselect_all)) }
                            Text(pluralStringResource(R.plurals.items_selected, selected.size, selected.size), style = MaterialTheme.typography.titleSmall)
                        }
                        showsFolder && folder != null -> TextButton(onClick = { onOpenFolder(folder.parentId) }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
                            Text(library.folder(folder.parentId)?.name ?: stringResource(R.string.documents))
                        }
                    }
                },
            ) {
                when {
                    selecting && selected.isEmpty() -> OutlinedButton(onClick = ::stopSelecting, shape = RoundedCornerShape(50)) {
                        Text(stringResource(R.string.done))
                    }
                    selecting && inTrash -> {
                        TextButton(onClick = {
                            val ids = selection
                            stopSelecting()
                            edit { ids.forEach { restore(it) } }
                        }) { Text(restoreLabel) }
                        TextButton(onClick = { dialog = LibraryDialog.DeleteSelected(selected) }) { Text(deleteLabel) }
                    }
                    selecting -> {
                        val documents = selected.filterIsInstance<Document>()
                        if (documents.size >= 2 && documents.size == selected.size) {
                            BarIcon(painterResource(R.drawable.ic_merge), stringResource(R.string.merge)) {
                                dialog = LibraryDialog.Merge(documents.map { it.id }, mergedName.format(documents.first().title))
                            }
                        }
                        BarIcon(painterResource(R.drawable.ic_duplicate), duplicateLabel) {
                            val ids = selection
                            stopSelecting()
                            duplicate(ids)
                        }
                        BarIcon(painterResource(R.drawable.ic_move_to), moveLabel) { dialog = LibraryDialog.Move(selected) }
                        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        BarIcon(rememberVectorPainter(Icons.Default.Delete), trashLabel, MaterialTheme.colorScheme.error) {
                            val ids = selection
                            stopSelecting()
                            edit { ids.forEach { trash(it) } }
                        }
                    }
                    else -> {
                        if (inTrash && items.isNotEmpty()) {
                            TextButton(onClick = { edit { library.trash().forEach { restore(it.id) } } }) { Text(stringResource(R.string.restore_all)) }
                            TextButton(onClick = { dialog = LibraryDialog.EmptyTrash }) { Text(stringResource(R.string.empty_trash)) }
                        }
                        if (items.isNotEmpty()) {
                            IconButton(onClick = { selecting = true }) { Icon(Icons.Outlined.CheckCircle, stringResource(R.string.select)) }
                        }
                    }
                }
            }
            FilterRow(filter.takeIf { showsFolder }, { filter = it }, prefs)
            if (searching) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                    FilterChip(selected = !showContents, onClick = { searchContents = false }, label = { Text(stringResource(R.string.search_in_titles, items.size)) })
                    FilterChip(selected = showContents, onClick = { searchContents = true }, label = { Text(stringResource(R.string.search_in_contents, visibleHits.size)) })
                }
            }
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (showContents) {
                ContentResults(library, visibleHits, onOpenPage)
            } else if (library.loaded && items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            when {
                                searching -> R.string.search_no_results
                                inTrash -> R.string.trash_empty
                                section == LibrarySection.DOCUMENTS -> R.string.library_empty
                                else -> R.string.nothing_here
                            },
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ItemGrid(
                    items = items,
                    library = library,
                    listView = prefs.listView,
                    selection = selection.takeIf { selecting },
                    onClick = { item ->
                        when {
                            selecting -> selection = if (item.id in selection) selection - item.id else selection + item.id
                            !inTrash -> onOpen(item)
                        }
                    },
                    onFavorite = if (inTrash) null else { item -> edit { setFavorite(item.id, !item.favorite) } },
                    actions = { item ->
                        if (inTrash) {
                            listOf(
                                MenuAction(restoreLabel) { edit { restore(item.id) } },
                                MenuAction(deleteLabel) { dialog = LibraryDialog.DeleteForever(item) },
                            )
                        } else {
                            listOfNotNull(
                                MenuAction(renameLabel) { dialog = LibraryDialog.Rename(item) },
                                (item as? Document)?.let { MenuAction(settingsLabel) { dialog = LibraryDialog.Settings(it) } },
                                (item as? Document)?.let { MenuAction(exportLabel) { dialog = LibraryDialog.Export(it) } },
                                MenuAction(moveLabel) { dialog = LibraryDialog.Move(listOf(item)) },
                                MenuAction(duplicateLabel) { duplicate(listOf(item.id)) },
                                (item as? Folder)?.let { MenuAction(colourLabel) { dialog = LibraryDialog.Colour(it) } },
                                MenuAction(if (item.favorite) unfavoriteLabel else favoriteLabel) { edit { setFavorite(item.id, !item.favorite) } },
                                MenuAction(trashLabel) { edit { trash(item.id) } },
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (showsFolder && !selecting) {
            AddMenu(
                onNewFolder = { dialog = LibraryDialog.NewFolder },
                onNewNote = { dialog = LibraryDialog.NewNote },
                onImport = { picker.launch(arrayOf("application/pdf", "image/*")) },
                onImportFlexcil = { flexcilPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }

    when (val d = dialog) {
        LibraryDialog.NewFolder -> NameDialog(stringResource(R.string.new_folder), "", { name -> edit { createFolder(name, folder?.id) } }, { dialog = null })
        LibraryDialog.NewNote -> NoteSettingsDialog(
            title = "",
            cover = CoverPick.FirstPage,
            choice = prefs.lastTemplate,
            onDone = { title, cover, choice ->
                dialog = null
                prefs.updateLastTemplate(choice)
                scope.launch {
                    runLibraryAction(context) {
                        createNote(title, folder?.id, choice, (cover as? CoverPick.Generated)?.index).also { doc ->
                            if (cover is CoverPick.NewImage) importCover(context, doc.id, cover.uri)
                        }
                    }?.let(onOpen)
                }
            },
            onDismiss = { dialog = null },
        )
        is LibraryDialog.Export -> ExportDialog(d.doc, 0) { dialog = null }
        is LibraryDialog.Settings -> {
            val doc = d.doc
            val original = TemplateChoice.of(doc.template, prefs.lastTemplate)
            NoteSettingsDialog(
                title = doc.title,
                cover = CoverPick.of(doc),
                choice = original,
                noCover = { PageThumbnail(doc, 0, it) },
                coverImage = doc.coverImage?.let { context.nxtes.library.blobFile(doc.id, it) },
                onDone = { title, cover, choice ->
                    edit {
                        if (title != doc.title) rename(doc.id, title)
                        when (cover) {
                            CoverPick.CurrentImage -> Unit
                            is CoverPick.NewImage -> importCover(context, doc.id, cover.uri)
                            else -> if (cover != CoverPick.of(doc)) setCover(doc.id, (cover as? CoverPick.Generated)?.index)
                        }
                        if (choice != original && !doc.note) setTemplate(doc.id, choice, allPages = false)
                    }
                    if (choice != original && doc.note) dialog = LibraryDialog.ApplyTemplate(doc, choice)
                },
                onDismiss = { dialog = null },
            )
        }
        is LibraryDialog.ApplyTemplate -> TemplateScopeDialog(
            onPick = { allPages -> edit { setTemplate(d.doc.id, d.choice, allPages) } },
            onDismiss = { dialog = null },
        )
        is LibraryDialog.Rename -> NameDialog(renameLabel, d.item.displayName, { name -> edit { rename(d.item.id, name) } }, { dialog = null })
        is LibraryDialog.Move -> MoveDialog(library, d.items, { target ->
            stopSelecting()
            edit { d.items.forEach { move(it.id, target) } }
        }, { dialog = null })
        is LibraryDialog.Merge -> NameDialog(stringResource(R.string.merge), d.title, { name ->
            stopSelecting()
            dialog = null
            scope.launch { runLibraryAction(context) { merge(d.ids, name) }?.let(onOpen) }
        }, { dialog = null })
        is LibraryDialog.DeleteSelected -> ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_items_forever_title, d.items.size, d.items.size),
            confirm = deleteLabel,
            onConfirm = {
                stopSelecting()
                edit { d.items.forEach { deletePermanently(it.id) } }
            },
            onDismiss = { dialog = null },
        )
        is LibraryDialog.Colour -> ColourDialog(d.folder.color ?: DEFAULT_FOLDER_COLOR, { colour -> edit { setFolderColor(d.folder.id, colour) } }, { dialog = null })
        is LibraryDialog.DeleteForever -> ConfirmDialog(
            title = stringResource(R.string.delete_forever_title, d.item.displayName),
            confirm = deleteLabel,
            onConfirm = { edit { deletePermanently(d.item.id) } },
            onDismiss = { dialog = null },
        )
        LibraryDialog.EmptyTrash -> ConfirmDialog(
            title = stringResource(R.string.empty_trash_title),
            confirm = deleteLabel,
            onConfirm = { edit { emptyTrash() } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun TitleBar(
    title: String,
    onMenu: (() -> Unit)?,
    leading: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        Row(Modifier.align(Alignment.CenterStart).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onMenu != null) {
                IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, stringResource(R.string.open_menu)) }
            }
            leading()
        }
        SectionTitle(title, Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
private fun BarIcon(painter: Painter, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(painter, label, tint = tint, modifier = Modifier.size(22.dp)) }
}

/** All / PDFs / Notes in the middle (when [filter] is shown), sort and grid/list on the right. */
@Composable
private fun FilterRow(filter: KindFilter?, onFilter: (KindFilter) -> Unit, prefs: LibraryPrefs) {
    var sortOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(44.dp)) {
        if (filter != null) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                KindFilter.entries.forEach { kind ->
                    val selected = kind == filter
                    Text(
                        text = stringResource(
                            when (kind) {
                                KindFilter.ALL -> R.string.filter_all
                                KindFilter.PDFS -> R.string.filter_pdfs
                                KindFilter.NOTES -> R.string.filter_notes
                            },
                        ),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clickable { onFilter(kind) }.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
            Box {
                IconButton(onClick = { sortOpen = true }) { Icon(painterResource(R.drawable.ic_sort), stringResource(R.string.sort)) }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(sortLabel(order)),
                                    color = if (order == prefs.sort) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                )
                            },
                            onClick = {
                                sortOpen = false
                                prefs.updateSort(order)
                            },
                        )
                    }
                }
            }
            IconButton(onClick = prefs::toggleListView) {
                if (prefs.listView) {
                    Icon(painterResource(R.drawable.ic_grid_view), stringResource(R.string.view_grid))
                } else {
                    Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.view_list))
                }
            }
        }
    }
}

private fun sortLabel(order: SortOrder) = when (order) {
    SortOrder.NAME_ASC -> R.string.sort_name_asc
    SortOrder.NAME_DESC -> R.string.sort_name_desc
    SortOrder.MODIFIED_NEWEST -> R.string.sort_modified_newest
    SortOrder.MODIFIED_OLDEST -> R.string.sort_modified_oldest
    SortOrder.CREATED_NEWEST -> R.string.sort_created_newest
    SortOrder.CREATED_OLDEST -> R.string.sort_created_oldest
}

/** The round add button; it expands into labelled actions over a dimmed library. */
@Composable
private fun AddMenu(onNewFolder: () -> Unit, onNewNote: () -> Unit, onImport: () -> Unit, onImportFlexcil: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        if (open) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = false },
            )
        }
        Column(modifier.align(Alignment.BottomEnd).padding(24.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (open) {
                AddMenuItem(stringResource(R.string.new_folder), painterResource(R.drawable.ic_folder)) {
                    open = false
                    onNewFolder()
                }
                AddMenuItem(stringResource(R.string.new_note), painterResource(R.drawable.ic_note)) {
                    open = false
                    onNewNote()
                }
                AddMenuItem(stringResource(R.string.import_pdf), painterResource(R.drawable.ic_document)) {
                    open = false
                    onImport()
                }
                AddMenuItem(stringResource(R.string.import_flexcil), painterResource(R.drawable.ic_move_to)) {
                    open = false
                    onImportFlexcil()
                }
            }
            FloatingActionButton(
                onClick = { open = !open },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(50),
            ) {
                Icon(if (open) Icons.Default.Close else Icons.Default.Add, stringResource(R.string.add))
            }
        }
    }
}

@Composable
private fun AddMenuItem(label: String, icon: Painter, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(8.dp), shadowElevation = 2.dp, modifier = Modifier.clickable(onClick = onClick)) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        SmallFloatingActionButton(onClick = onClick, shape = RoundedCornerShape(50), containerColor = MaterialTheme.colorScheme.surface) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private const val SEARCH_DELAY_MS = 200L

/** Runs a store change off the main thread; returns its result, or shows a toast and returns null if it fails. */
suspend fun <T> runLibraryAction(context: Context, action: LibraryStore.() -> T): T? {
    val result = withContext(Dispatchers.IO) { runCatching { context.nxtes.library.action() } }
    result.exceptionOrNull()?.let { error ->
        Log.w("Library", "Action failed", error)
        Toast.makeText(context, R.string.action_failed, Toast.LENGTH_SHORT).show()
    }
    return result.getOrNull()
}

@Composable
fun ConfirmDialog(title: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(stringResource(R.string.cannot_undo)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
