package net.mildhope.nxtes.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mildhope.nxtes.R
import net.mildhope.nxtes.nxtes

/** Asks which version to keep, one conflicting document at a time, whenever sync finds one. */
@Composable
fun SyncConflicts() {
    val sync = LocalContext.current.nxtes.sync
    val conflicts by sync.engine.conflicts.collectAsState()
    val conflict = conflicts.firstOrNull() ?: return
    val scope = rememberCoroutineScope()
    var busy by remember(conflict.documentId) { mutableStateOf(false) }
    val resources = LocalResources.current

    fun resolve(resolution: Resolution) {
        busy = true
        scope.launch {
            runCatching { sync.engine.resolve(conflict.documentId, resolution) { resources.getString(R.string.conflict_copy, it) } }
            busy = false
            sync.requestSync()
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.conflict_title, conflict.title)) },
        text = {
            Column(Modifier.width(400.dp)) {
                Text(stringResource(if (conflict.deletedThere) R.string.conflict_deleted_text else R.string.conflict_text))
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.End) {
                    TextButton(enabled = !busy, onClick = { resolve(Resolution.KEEP_MINE) }) { Text(stringResource(R.string.conflict_keep_mine)) }
                    TextButton(enabled = !busy, onClick = { resolve(Resolution.KEEP_THEIRS) }) {
                        Text(stringResource(if (conflict.deletedThere) R.string.conflict_delete else R.string.conflict_keep_theirs))
                    }
                    if (!conflict.deletedThere) {
                        TextButton(enabled = !busy, onClick = { resolve(Resolution.KEEP_BOTH) }) { Text(stringResource(R.string.conflict_keep_both)) }
                    }
                }
            }
        },
        confirmButton = {},
    )
}
