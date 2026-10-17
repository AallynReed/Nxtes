package net.mildhope.nxtes.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Folder
import net.mildhope.nxtes.data.Library
import net.mildhope.nxtes.data.LibraryItem
import net.mildhope.nxtes.data.LibraryStore
import java.text.DateFormat
import java.util.Date

private sealed interface TrashConfirm {
    data object EmptyAll : TrashConfirm
    data class Delete(val item: LibraryItem) : TrashConfirm
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(library: Library, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<TrashConfirm?>(null) }
    val items = library.trash()
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    fun edit(action: LibraryStore.() -> Unit) {
        confirm = null
        scope.launch { runLibraryAction(context, action) }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = { confirm = TrashConfirm.EmptyAll }) {
                            Text(stringResource(R.string.empty_trash))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.trash_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(contentPadding = padding) {
                items(items, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.displayName) },
                        supportingContent = {
                            Text(stringResource(R.string.trashed_on, dateFormat.format(Date(item.trashedAt ?: 0))))
                        },
                        leadingContent = {
                            Icon(
                                painterResource(if (item is Folder) R.drawable.ic_folder else R.drawable.ic_document),
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { edit { restore(item.id) } }) {
                                    Text(stringResource(R.string.restore))
                                }
                                TextButton(onClick = { confirm = TrashConfirm.Delete(item) }) {
                                    Text(stringResource(R.string.delete))
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    when (c) {
                        TrashConfirm.EmptyAll -> stringResource(R.string.empty_trash_title)
                        is TrashConfirm.Delete -> stringResource(R.string.delete_forever_title, c.item.displayName)
                    },
                )
            },
            text = { Text(stringResource(R.string.cannot_undo)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (c) {
                            TrashConfirm.EmptyAll -> edit { emptyTrash() }
                            is TrashConfirm.Delete -> edit { deletePermanently(c.item.id) }
                        }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
