package net.mildhope.nxtes.edit

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.InkStroke
import java.util.Locale

/** Reads [strokes] as text, shows it for correcting, then copies it or puts it in place of the writing. */
@Composable
fun ConvertTextDialog(
    reader: HandwritingReader,
    strokes: List<InkStroke>,
    onCopy: (String) -> Unit,
    onConvert: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var language by remember { mutableStateOf(reader.language) }
    var attempt by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(language, attempt) {
        text = null
        failed = false
        downloading = !runCatching { reader.isDownloaded(language) }.getOrDefault(false)
        try {
            text = reader.read(strokes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Handwriting", "Reading failed", e)
            failed = true
        }
        downloading = false
    }
    val read = text
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.convert_to_text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LanguagePicker(language, reader.languages) {
                    reader.language = it
                    language = it
                }
                when {
                    failed -> Text(stringResource(R.string.handwriting_failed), color = MaterialTheme.colorScheme.error)
                    read == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(if (downloading) stringResource(R.string.handwriting_downloading, languageName(language)) else stringResource(R.string.handwriting_reading))
                    }
                    read.isEmpty() -> Text(stringResource(R.string.handwriting_none))
                    else -> OutlinedTextField(value = read, onValueChange = { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (failed) {
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.try_again)) }
            } else {
                TextButton(onClick = { onConvert(read.orEmpty()) }, enabled = !read.isNullOrBlank()) { Text(stringResource(R.string.convert)) }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { onCopy(read.orEmpty()) }, enabled = !read.isNullOrBlank()) { Text(stringResource(R.string.copy)) }
            }
        },
    )
}

@Composable
private fun LanguagePicker(current: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(languageName(current))
            Icon(Icons.Default.ArrowDropDown, null, Modifier.padding(start = 4.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { tag ->
                DropdownMenuItem(text = { Text(languageName(tag)) }, onClick = {
                    open = false
                    if (tag != current) onPick(tag)
                })
            }
        }
    }
}

/** [tag] as a language name in that language, like "Português (Portugal)". */
fun languageName(tag: String): String =
    Locale.forLanguageTag(tag).let { it.getDisplayName(it) }.replaceFirstChar { it.titlecase() }
