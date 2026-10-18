package net.mildhope.nxtes.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
fun ColourDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_colour)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FOLDER_COLORS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { colour ->
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(colour))
                                    .border(if (colour == current) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    .clickable { onPick(colour) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Picks a destination folder for [items]; folders can't move into themselves. */
@Composable
fun MoveDialog(library: Library, items: List<LibraryItem>, onMove: (String?) -> Unit, onDismiss: () -> Unit) {
    val excluded = items.filterIsInstance<Folder>().flatMap { library.subtree(it.id) }.toSet()
    val parents = items.map {
        when (it) {
            is Folder -> it.parentId
            is Document -> it.folderId
        }
    }.toSet()
    val current = parents.singleOrNull()
    val shared = parents.size == 1
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
                    val isCurrent = shared && folder?.id == current
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
