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
import net.mildhope.nxtes.nxtes

private const val TAG = "Import"
private val PDF_EXTENSION = Regex("\\.pdf$", RegexOption.IGNORE_CASE)

/** Copies each PDF into the library. Returns the documents that imported; reports failures. */
suspend fun importPdfs(context: Context, uris: List<Uri>, folderId: String?): List<Document> {
    val results = withContext(Dispatchers.IO) {
        uris.map { uri ->
            runCatching {
                val name = displayName(context, uri)?.replace(PDF_EXTENSION, "")?.ifBlank { null }
                    ?: context.getString(R.string.untitled)
                context.contentResolver.openInputStream(uri).use { input ->
                    context.nxtes.library.importPdf(requireNotNull(input) { "No data for $uri" }, name, folderId)
                }
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

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
