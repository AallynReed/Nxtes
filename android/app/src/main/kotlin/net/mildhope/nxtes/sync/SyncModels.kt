package net.mildhope.nxtes.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.Folder
import java.io.File
import java.io.IOException

internal val syncJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
}

/** A folder or document as the server has it. A deleted one keeps its revision so later writes can follow it. */
@Serializable
data class RemoteItem(
    val kind: String,
    val id: String,
    val revision: Long,
    val seq: Long = 0,
    val deleted: Boolean = false,
    val body: JsonElement = JsonNull,
)

@Serializable
data class Changes(val items: List<RemoteItem>, val cursor: Long, val more: Boolean = false)

/**
 * A document as it's synced: its data without when it was last opened, the hash of each page file
 * by page id, the hashes of the files in its package, and every hash it uses, for the server.
 */
@Serializable
data class DocumentBody(
    val format: Int = 1,
    val document: Document,
    val pages: Map<String, String> = emptyMap(),
    val files: List<String> = emptyList(),
    val blobs: List<String> = emptyList(),
)

@Serializable
data class FolderBody(val format: Int = 1, val folder: Folder, val blobs: List<String> = emptyList())

/** An error the server answered with, by its status and error code. */
class ApiException(val status: Int, val code: String, val details: JsonObject = JsonObject(emptyMap())) : IOException("$status $code") {
    /** The server's copy, sent with a conflict. */
    val item: RemoteItem?
        get() = details["item"]?.let { runCatching { syncJson.decodeFromJsonElement<RemoteItem>(it) }.getOrNull() }
}

/** What sync needs from the server. */
interface SyncService {
    suspend fun changes(since: Long): Changes

    /** The hashes the server doesn't have yet. */
    suspend fun missingBlobs(hashes: Collection<String>): List<String>

    suspend fun putBlob(sha256: String, file: File)

    /** Downloads a blob, checking it hashes to [sha256]. */
    suspend fun getBlob(sha256: String, into: File)

    /** Writes an item over [baseRevision] and returns its new revision; a stale base throws an [ApiException] carrying the server's copy. */
    suspend fun putItem(kind: String, id: String, baseRevision: Long, body: JsonElement): Long

    suspend fun deleteItem(kind: String, id: String, baseRevision: Long): Long
}

const val KIND_DOCUMENT = "document"
const val KIND_FOLDER = "folder"
