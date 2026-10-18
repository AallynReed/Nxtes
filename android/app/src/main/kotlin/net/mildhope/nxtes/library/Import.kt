package net.mildhope.nxtes.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.flexcil.FlexcilImporter
import net.mildhope.nxtes.nxtes
import net.mildhope.nxtes.page.openAsPdf
import java.io.File

private const val TAG = "Import"
private val EXTENSION = Regex("\\.[A-Za-z0-9]{1,5}$")

/** Copies each PDF, or image as a one-page PDF, into the library. Returns the documents that imported; reports failures. */
suspend fun importFiles(context: Context, uris: List<Uri>, folderId: String?): List<Document> {
    val results = withContext(Dispatchers.IO) {
        uris.map { uri ->
            runCatching {
                val name = displayName(context, uri)?.replace(EXTENSION, "")?.ifBlank { null }
                    ?: context.getString(R.string.untitled)
                val image = context.contentResolver.getType(uri)?.startsWith("image/") == true
                openAsPdf(context, uri, image) { context.nxtes.library.importPdf(it, name, folderId) }
            }.onFailure { Log.w(TAG, "Import failed for $uri", it) }
        }
    }
    val failed = results.count { it.isFailure }
    if (failed > 0) {
        val message = context.resources.getQuantityString(R.plurals.import_failed, failed, failed)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    return results.mapNotNull { it.getOrNull() }
}

/** Whether a shared or opened file is a Flexcil backup or document. */
fun isFlexcil(context: Context, uri: Uri): Boolean =
    context.contentResolver.getType(uri) == FLEXCIL_TYPE ||
        displayName(context, uri)?.lowercase()?.let { it.endsWith(".flex") || it.endsWith(".flx") } == true

/** Imports a Flexcil backup or document into the library and says how it went. Returns how many documents came in. */
suspend fun importFlexcil(context: Context, uri: Uri, folderId: String?): Int {
    val result = withContext(Dispatchers.IO) {
        runCatching {
            val copy = File(context.cacheDir, "flexcil-import")
            try {
                requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> copy.outputStream().use { input.copyTo(it) } }
                FlexcilImporter(
                    store = context.nxtes.library,
                    workDir = File(context.cacheDir, "flexcil"),
                    recordingTitle = context.getString(R.string.recording_now),
                    onFailure = { entry, error -> Log.w(TAG, "Couldn't import $entry from Flexcil", error) },
                ).import(copy, folderId)
            } finally {
                copy.delete()
            }
        }.onFailure { Log.w(TAG, "Flexcil import failed for $uri", it) }
    }
    val message = result.fold(
        onSuccess = { done ->
            val imported = context.resources.getQuantityString(R.plurals.flexcil_imported, done.documents, done.documents)
            if (done.failed == 0) imported else imported + "\n" + context.resources.getQuantityString(R.plurals.import_failed, done.failed, done.failed)
        },
        onFailure = { context.getString(if (it is FlexcilImporter.NotFlexcil) R.string.flexcil_not_backup else R.string.action_failed) },
    )
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    return result.getOrNull()?.documents ?: 0
}

const val FLEXCIL_TYPE = "application/flex"

fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
