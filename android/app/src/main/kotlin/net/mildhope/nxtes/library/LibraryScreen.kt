package net.mildhope.nxtes.library

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Folder
import net.mildhope.nxtes.data.Library
import net.mildhope.nxtes.data.LibraryItem
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.nxtes

private sealed interface LibraryDialog {
    data object NewFolder : LibraryDialog
    data class Rename(val item: LibraryItem) : LibraryDialog
    data class Move(val item: LibraryItem) : LibraryDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    library: Library,
    folderId: String?,
    onOpenFolder: (String?) -> Unit,
    onOpenDocument: (String) -> Unit,
    onShowTrash: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folder = library.folder(folderId)
    var importing by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }

    fun edit(action: LibraryStore.() -> Unit) {
        dialog = null
        scope.launch { runLibraryAction(context, action) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importing = true
                importPdfs(context, uris, folderId)
                importing = false
            }
        }
    }

    BackHandler(enabled = folder != null) { onOpenFolder(folder?.parentId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folder?.name ?: stringResource(R.string.library_title)) },
                navigationIcon = {
                    if (folder != null) {
                        IconButton(onClick = { onOpenFolder(folder.parentId) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onShowTrash) {
                        Icon(Icons.Default.Delete, stringResource(R.string.trash_title))
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { addMenuOpen = true }) {
                    Icon(Icons.Default.Add, stringResource(R.string.add))
                }
                DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_pdf)) },
                        onClick = {
                            addMenuOpen = false
                            picker.launch(arrayOf("application/pdf"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_folder)) },
                        onClick = {
                            addMenuOpen = false
                            dialog = LibraryDialog.NewFolder
                        },
                    )
                }
            }
        },
    ) { padding ->
        val folders = library.childFolders(folderId)
        val documents = library.childDocuments(folderId)
        Column(Modifier.padding(padding)) {
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (library.loaded && folders.isEmpty() && documents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(folders + documents, key = { it.id }) { item ->
                        LibraryCell(
                            item = item,
                            onClick = { if (item is Folder) onOpenFolder(item.id) else onOpenDocument(item.id) },
                            onRename = { dialog = LibraryDialog.Rename(item) },
                            onMove = { dialog = LibraryDialog.Move(item) },
                            onTrash = { edit { trash(item.id) } },
                        )
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        LibraryDialog.NewFolder -> NameDialog(
            title = stringResource(R.string.new_folder),
            initial = "",
            onConfirm = { name -> edit { createFolder(name, folderId) } },
            onDismiss = { dialog = null },
        )
        is LibraryDialog.Rename -> NameDialog(
            title = stringResource(R.string.rename),
            initial = d.item.displayName,
            onConfirm = { name -> edit { rename(d.item.id, name) } },
            onDismiss = { dialog = null },
        )
        is LibraryDialog.Move -> MoveDialog(
            library = library,
            item = d.item,
            onMove = { target -> edit { move(d.item.id, target) } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** Runs a store change off the main thread and shows a toast if it fails. */
suspend fun runLibraryAction(context: Context, action: LibraryStore.() -> Unit) {
    val error = withContext(Dispatchers.IO) { runCatching { context.nxtes.library.action() }.exceptionOrNull() }
    if (error != null) {
        Log.w("Library", "Action failed", error)
        Toast.makeText(context, R.string.action_failed, Toast.LENGTH_SHORT).show()
    }
}
