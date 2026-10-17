package net.mildhope.nxtes.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.Folder
import net.mildhope.nxtes.data.Library
import net.mildhope.nxtes.data.LibraryItem

@Composable
fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val focus = remember { FocusRequester() }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.text.trim()) }, enabled = text.text.isNotBlank()) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun MoveDialog(library: Library, item: LibraryItem, onMove: (String?) -> Unit, onDismiss: () -> Unit) {
    val excluded = if (item is Folder) library.subtree(item.id) else emptySet()
    val current = when (item) {
        is Folder -> item.parentId
        is Document -> item.folderId
    }
    val destinations = buildList<Pair<Folder?, Int>> {
        add(null to 0)
        fun walk(parentId: String?, depth: Int) {
            library.childFolders(parentId).filter { it.id !in excluded }.forEach {
                add(it to depth)
                walk(it.id, depth + 1)
            }
        }
        walk(null, 1)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to)) },
        text = {
            LazyColumn {
                items(destinations) { (folder, depth) ->
                    val isCurrent = folder?.id == current
                    ListItem(
                        headlineContent = { Text(folder?.name ?: stringResource(R.string.library_title)) },
                        leadingContent = {
                            Icon(
                                painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                                tint = if (isCurrent) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier
                            .clickable(enabled = !isCurrent) { onMove(folder?.id) }
                            .padding(start = (depth * 16).dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
