package net.mildhope.nxtes.sync

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.mildhope.nxtes.R
import net.mildhope.nxtes.nxtes

private fun formatTime(context: android.content.Context, time: Long) =
    DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

/** The versions of a document or folder the server kept, newest first, any of which can become the current one. */
@Composable
fun VersionHistoryDialog(kind: String, id: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sync = context.nxtes.sync
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<ItemHistory?>(null) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var asking by remember { mutableStateOf<ItemVersion?>(null) }
    var busy by remember { mutableStateOf(false) }
    val restored = stringResource(R.string.version_restored)

    LaunchedEffect(kind, id) {
        try {
            history = sync.client.history(kind, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = if ((e as? ApiException)?.status == 404) R.string.version_history_not_synced else errorMessage(e)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.version_history)) },
        text = {
            Column(Modifier.width(400.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                val shown = history
                when {
                    problem != null -> Text(stringResource(problem!!), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    shown == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> {
                        Text(
                            stringResource(R.string.version_current, formatTime(context, shown.current.updated)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        if (shown.versions.isEmpty()) {
                            Text(stringResource(R.string.version_history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        shown.versions.forEach { version ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                    Text(formatTime(context, version.updated), style = MaterialTheme.typography.bodyMedium)
                                    val detail = if (version.deleted) stringResource(R.string.version_deleted) else version.title.orEmpty()
                                    if (detail.isNotEmpty()) {
                                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                TextButton(enabled = !busy, onClick = { asking = version }) { Text(stringResource(R.string.restore)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )

    asking?.let { version ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.restore_version_title)) },
            text = { Text(stringResource(R.string.restore_version_text)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    busy = true
                    scope.launch {
                        try {
                            sync.client.restoreVersion(kind, id, version.revision)
                            sync.requestSync()
                            Toast.makeText(context, restored, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            problem = errorMessage(e)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.restore)) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Documents and folders deleted on any device that the server can still bring back. */
@Composable
fun RecentlyDeleted(items: List<DeletedItem>, onRestore: (DeletedItem) -> Unit) {
    val context = LocalContext.current
    Text(stringResource(R.string.recently_deleted), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    items.forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(item.title ?: stringResource(R.string.untitled), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.deleted_on, formatTime(context, item.deletedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onRestore(item) }) { Text(stringResource(R.string.restore)) }
        }
    }
}
