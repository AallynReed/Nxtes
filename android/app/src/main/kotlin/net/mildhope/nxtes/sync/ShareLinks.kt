package net.mildhope.nxtes.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.mildhope.nxtes.R
import net.mildhope.nxtes.nxtes
import java.io.File

private enum class Expiry(val days: Int?, @param:StringRes val label: Int) {
    NEVER(null, R.string.expires_never),
    DAY(1, R.string.expires_day),
    WEEK(7, R.string.expires_week),
    MONTH(30, R.string.expires_month),
}

fun copyLink(context: Context, title: String, url: String, copied: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, url))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
}

fun shareLink(context: Context, title: String, url: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, url)
    context.startActivity(Intent.createChooser(intent, null))
}

/** Uploads an exported PDF for a web link, after asking how long the link should last, then offers the link. */
@Composable
fun ShareLinkDialog(file: File, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sync = context.nxtes.sync
    val scope = rememberCoroutineScope()
    var expiry by rememberSaveable { mutableStateOf(Expiry.NEVER) }
    var busy by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf<ShareInfo?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    val copied = stringResource(R.string.link_copied)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.share_link_title)) },
        text = {
            Column(Modifier.width(400.dp)) {
                val ready = share
                if (ready == null) {
                    Text(stringResource(R.string.share_link_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.share_link_expires), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Expiry.entries.forEach { option ->
                            FilterChip(selected = expiry == option, enabled = !busy, onClick = { expiry = option }, label = { Text(stringResource(option.label)) })
                        }
                    }
                } else {
                    Text(stringResource(R.string.share_link_ready), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(ready.url, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 12.dp)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { copyLink(context, ready.title, ready.url, copied) }) { Text(stringResource(R.string.copy_link)) }
                        TextButton(onClick = { shareLink(context, ready.title, ready.url) }) { Text(stringResource(R.string.share)) }
                    }
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = {
            if (share == null) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            share = sync.client.createShare(file, title, expiry.days)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = errorMessage(e)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.share_link_create)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
        },
        dismissButton = { if (share == null) TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The account's web links, for copying, sharing again or stopping. */
@Composable
fun SharedLinks(shares: List<ShareInfo>, onRemove: (ShareInfo) -> Unit) {
    val context = LocalContext.current
    val copied = stringResource(R.string.link_copied)
    Text(stringResource(R.string.shared_links), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    shares.forEach { share ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).clickable { copyLink(context, share.title, share.url, copied) }.padding(vertical = 6.dp)) {
                Text(share.title, style = MaterialTheme.typography.bodyMedium)
                val views = pluralStringResource(R.plurals.shared_link_views, share.views.toInt(), share.views.toInt())
                val expires = share.expires?.let {
                    stringResource(R.string.shared_link_expires, DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH))
                }
                Text(
                    listOfNotNull(views, expires).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { shareLink(context, share.title, share.url) }) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
            }
            IconButton(onClick = { onRemove(share) }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.stop_sharing, share.title))
            }
        }
    }
}
