package net.mildhope.nxtes.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Library

/** Picks a document to move pages into, listed with its folder path. */
@Composable
fun DocumentPickerDialog(library: Library, excludeId: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val top = stringResource(R.string.documents)
    fun path(folderId: String?): String {
        val names = generateSequence(library.folder(folderId)) { library.folder(it.parentId) }.map { it.name }.toList()
        return (listOf(top) + names.reversed()).joinToString(" / ")
    }
    val documents = library.documents
        .filter { it.id != excludeId && it.trashedAt == null && !library.isInTrash(it.folderId) }
        .map { it to path(it.folderId) }
        .sortedWith(compareBy({ it.second.lowercase() }, { it.first.title.lowercase() }))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_pages_to)) },
        text = {
            if (documents.isEmpty()) {
                Text(stringResource(R.string.no_other_documents))
            } else {
                LazyColumn {
                    items(documents, key = { it.first.id }) { (doc, folder) ->
                        ListItem(
                            headlineContent = { Text(doc.title) },
                            supportingContent = { Text(folder, style = MaterialTheme.typography.labelSmall) },
                            leadingContent = {
                                Box(Modifier.size(width = 32.dp, height = 40.dp), contentAlignment = Alignment.Center) {
                                    PageThumbnail(doc, 0, Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant), widthPx = 96)
                                }
                            },
                            modifier = Modifier.clickable { onPick(doc.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
