package net.mildhope.nxtes.export

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.nxtes
import net.mildhope.nxtes.sync.ShareLinkDialog
import java.io.File

private enum class PageChoice { ALL, CURRENT, RANGE }

/** Offers a file in the share sheet. */
fun share(context: Context, files: List<File>, type: String) {
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    context.startActivity(Intent.createChooser(intent, null))
}

/** Copies PNG pictures into Pictures/Nxtes. Blocks. */
fun saveToGallery(context: Context, files: List<File>) {
    val resolver = context.contentResolver
    for (file in files) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Nxtes")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "No place for ${file.name}" }
        requireNotNull(resolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }
}

/**
 * Exports a document: as a PDF (keeping its original PDF pages, or flattened into pictures) or as
 * pictures of its pages, with a choice of what to include and which pages, then shares or saves it.
 */
@Composable
fun ExportDialog(doc: Document, currentPage: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.nxtes
    val scope = rememberCoroutineScope()
    var images by rememberSaveable { mutableStateOf(false) }
    var flatten by rememberSaveable { mutableStateOf(false) }
    var background by rememberSaveable { mutableStateOf(true) }
    var handwriting by rememberSaveable { mutableStateOf(true) }
    var pictures by rememberSaveable { mutableStateOf(true) }
    var textBoxes by rememberSaveable { mutableStateOf(true) }
    var notes by rememberSaveable { mutableStateOf(true) }
    var masks by rememberSaveable { mutableStateOf(false) }
    var choice by rememberSaveable { mutableStateOf(PageChoice.ALL) }
    var range by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var linkFile by remember { mutableStateOf<File?>(null) }
    val signedIn = app.sync.account.state.collectAsState().value.session != null
    val options = ExportOptions(flatten, background, handwriting, pictures, textBoxes, notes, masks)
    val pages = when (choice) {
        PageChoice.ALL -> doc.pages.indices.toList()
        PageChoice.CURRENT -> listOf(currentPage.coerceIn(doc.pages.indices))
        PageChoice.RANGE -> parsePages(range, doc.pages.size)
    }
    val pdfName = app.exporter.fileName(doc.title, "pdf")
    val pdfSaved = stringResource(R.string.pdf_saved)
    val failed = stringResource(R.string.action_failed)

    fun run(work: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { work() }.onFailure {
                Log.w("Export", "Export failed", it)
                Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
            }
            busy = false
        }
    }

    suspend fun exportPdf(): File {
        app.exporter.folder.listFiles()?.forEach { it.delete() }
        return File(app.exporter.folder, pdfName).also { app.exporter.pdf(doc, pages.orEmpty(), options, it) }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        run {
            val file = exportPdf()
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } } }
            Toast.makeText(context, pdfSaved, Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }
    val imagesSaved = pluralStringResource(R.plurals.images_saved, pages?.size ?: 0, pages?.size ?: 0)

    linkFile?.let { file ->
        ShareLinkDialog(file, doc.title, onDismiss = onDismiss)
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.export)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).width(420.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !images, onClick = { images = false }, label = { Text(stringResource(R.string.export_pdf)) })
                    FilterChip(selected = images, onClick = { images = true }, label = { Text(stringResource(R.string.export_images)) })
                }
                if (!images) {
                    Choice(stringResource(R.string.export_original), !flatten) { flatten = false }
                    Choice(stringResource(R.string.export_flatten), flatten) { flatten = true }
                }
                Heading(stringResource(R.string.export_include))
                Toggle(stringResource(R.string.include_background), background) { background = it }
                Toggle(stringResource(R.string.include_handwriting), handwriting) { handwriting = it }
                Toggle(stringResource(R.string.lasso_type_image), pictures) { pictures = it }
                Toggle(stringResource(R.string.lasso_type_text), textBoxes) { textBoxes = it }
                Toggle(stringResource(R.string.lasso_type_note), notes) { notes = it }
                Toggle(stringResource(R.string.lasso_type_mask), masks) { masks = it }
                Heading(stringResource(R.string.pages))
                Choice(stringResource(R.string.pages_all), choice == PageChoice.ALL) { choice = PageChoice.ALL }
                Choice(stringResource(R.string.pages_current, currentPage + 1), choice == PageChoice.CURRENT) { choice = PageChoice.CURRENT }
                Choice(stringResource(R.string.pages_range), choice == PageChoice.RANGE) { choice = PageChoice.RANGE }
                if (choice == PageChoice.RANGE) {
                    OutlinedTextField(
                        value = range,
                        onValueChange = { range = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.pages_range_hint)) },
                        isError = range.isNotBlank() && pages == null,
                        supportingText = if (range.isNotBlank() && pages == null) {
                            { Text(stringResource(R.string.pages_invalid, doc.pages.size)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
                    )
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = !busy && pages != null, onClick = {
                    if (images) {
                        run {
                            app.exporter.folder.listFiles()?.forEach { it.delete() }
                            val files = app.exporter.images(doc, pages.orEmpty(), options)
                            withContext(Dispatchers.IO) { saveToGallery(context, files) }
                            Toast.makeText(context, imagesSaved, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    } else {
                        saveFile.launch(pdfName)
                    }
                }) { Text(stringResource(R.string.save)) }
                TextButton(enabled = !busy && pages != null, onClick = {
                    run {
                        if (images) {
                            app.exporter.folder.listFiles()?.forEach { it.delete() }
                            share(context, app.exporter.images(doc, pages.orEmpty(), options), "image/png")
                        } else {
                            share(context, listOf(exportPdf()), "application/pdf")
                        }
                        onDismiss()
                    }
                }) { Text(stringResource(R.string.share)) }
                if (signedIn && !images) {
                    TextButton(enabled = !busy && pages != null, onClick = { run { linkFile = exportPdf() } }) {
                        Text(stringResource(R.string.share_link))
                    }
                }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(label)
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Text(label)
    }
}
