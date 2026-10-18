package net.mildhope.nxtes.library

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import net.mildhope.nxtes.ui.AppLanguage
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mildhope.nxtes.R
import net.mildhope.nxtes.sync.SyncSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The app's language: the system's, or one of the languages it's translated into. */
@Composable
private fun LanguageSetting() {
    val context = LocalContext.current
    val current = remember { AppLanguage.current(context) }
    Text(stringResource(R.string.language), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    val options = listOf(null to stringResource(R.string.language_system)) +
        AppLanguage.tags.zip(listOf(stringResource(R.string.language_english), stringResource(R.string.language_portuguese)))
    options.forEach { (tag, label) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().selectable(selected = tag == current, role = Role.RadioButton) {
                if (tag != current) AppLanguage.set(context, tag)
            },
        ) {
            RadioButton(selected = tag == current, onClick = null, modifier = Modifier.padding(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** App settings: sync, language, backing up and restoring the library, and the version. */
@Composable
fun SettingsDialog(onDismiss: () -> Unit, extra: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf<Int?>(null) }
    val backedUp = stringResource(R.string.backup_saved)
    val backupName = stringResource(R.string.backup_file_name)
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }

    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val done = runLibraryAction(context) { requireNotNull(context.contentResolver.openOutputStream(uri)).use(::backup) }
            busy = false
            if (done != null) Toast.makeText(context, backedUp, Toast.LENGTH_SHORT).show()
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            restored = runLibraryAction(context) { requireNotNull(context.contentResolver.openInputStream(uri)).use(::restore) }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(Modifier.width(400.dp).verticalScroll(rememberScrollState())) {
                SyncSettings()
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                LanguageSetting()
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                extra()
                Text(stringResource(R.string.library_backup), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Text(
                    stringResource(R.string.library_backup_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                TextButton(enabled = !busy, onClick = {
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                    backup.launch(backupName.format(day))
                }) { Text(stringResource(R.string.backup_library)) }
                TextButton(enabled = !busy, onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                    Text(stringResource(R.string.restore_backup))
                }
                restored?.let { Text(pluralStringResource(R.plurals.backup_restored, it, it), style = MaterialTheme.typography.bodyMedium) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.app_version, version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
