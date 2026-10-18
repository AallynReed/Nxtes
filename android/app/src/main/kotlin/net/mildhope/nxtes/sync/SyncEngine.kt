package net.mildhope.nxtes.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.Folder
import net.mildhope.nxtes.data.LibraryStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Documents open on screen, which have to save before sync reads them and reload after it changes them. */
interface OpenDocuments {
    suspend fun flush() {}

    suspend fun reload(documentId: String) {}
}

/** A document changed both here and on the server since they last agreed. */
data class Conflict(val documentId: String, val title: String, val deletedThere: Boolean)

enum class Resolution { KEEP_MINE, KEEP_THEIRS, KEEP_BOTH }

/**
 * Keeps the library in step with the server: pulls what changed there, then pushes what changed
 * here. Each item remembers the revision and body hash both sides last agreed on, so a change
 * on one side applies quietly and a change on both asks the user (folders just keep this device's).
 */
class SyncEngine(
    private val store: LibraryStore,
    private val service: SyncService,
    private val dir: File,
    private val open: OpenDocuments = object : OpenDocuments {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private val stateFile = File(dir, "state.json")
    private val staging = File(dir, "staging")
    private var loaded: SyncState? = null
    private var state: SyncState
        get() = loaded ?: read().also { loaded = it }
        set(value) {
            loaded = value
        }
    private val _conflicts = MutableStateFlow(emptyList<Conflict>())

    val conflicts: StateFlow<List<Conflict>> = _conflicts.asStateFlow()

    /** The change sequence this device has caught up to. */
    val cursor get() = state.cursor

    /** Reads what was synced before and lists its conflicts, once the library has loaded. */
    suspend fun prepare() = mutex.withLock { withContext(Dispatchers.IO) { publishConflicts() } }

    /** Pulls then pushes. Throws when the server can't be reached or refuses. */
    suspend fun sync() = mutex.withLock {
        withContext(Dispatchers.IO) {
            open.flush()
            try {
                pull()
                push()
            } finally {
                save()
            }
        }
    }

    /** Settles a conflict; [copyTitle] names the server's copy when both are kept. */
    suspend fun resolve(documentId: String, resolution: Resolution, copyTitle: (String) -> String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val key = key(KIND_DOCUMENT, documentId)
            val item = state.conflicts[key] ?: return@withContext
            open.flush()
            val theirs = if (item.deleted) null else syncJson.decodeFromJsonElement<DocumentBody>(item.body)
            when {
                resolution == Resolution.KEEP_THEIRS && theirs == null -> {
                    store.removeSyncedDocument(documentId) { true }
                    state.items.remove(key)
                }
                resolution == Resolution.KEEP_THEIRS && theirs != null -> {
                    install(documentId, theirs, donor = null) { true }
                    state.items[key] = Synced(item.revision, localHash(documentId))
                    open.reload(documentId)
                }
                else -> {
                    if (resolution == Resolution.KEEP_BOTH && theirs != null) {
                        val copyId = newId()
                        val copy = theirs.copy(document = theirs.document.copy(id = copyId, title = copyTitle(theirs.document.title)))
                        install(copyId, copy, donor = documentId) { store.state.value.document(copyId) == null }
                    }
                    state.items[key] = Synced(item.revision, "")
                }
            }
            state.conflicts.remove(key)
            publishConflicts()
            save()
        }
    }

    /** Forgets everything synced, after signing out or into another account. */
    suspend fun reset(userId: String?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            state = SyncState(userId = userId)
            save()
            publishConflicts()
        }
    }

    /** The account this device last synced with. */
    val userId get() = state.userId

    private suspend fun pull() {
        while (true) {
            val changes = service.changes(state.cursor)
            val (folders, documents) = changes.items.partition { it.kind == KIND_FOLDER }
            folders.filter { !it.deleted }.forEach { receive(it) }
            documents.filter { it.kind == KIND_DOCUMENT }.forEach { receive(it) }
            folders.filter { it.deleted }.forEach { receive(it) }
            state.cursor = changes.cursor
            if (!changes.more) break
        }
        publishConflicts()
    }

    /** Takes in the server's copy of an item, unless this device changed it too. */
    private suspend fun receive(item: RemoteItem) {
        val key = key(item.kind, item.id)
        val known = state.items[key]
        if (known != null && known.revision >= item.revision) return
        if (key in state.conflicts) {
            state.conflicts[key] = item
            return
        }
        if (item.kind == KIND_FOLDER) receiveFolder(key, known, item) else receiveDocument(key, known, item)
    }

    private fun receiveFolder(key: String, known: Synced?, item: RemoteItem) {
        val local = store.state.value.folder(item.id)
        val localHash = local?.let { hash(folderBody(it)) }
        val changedHere = local != null && localHash != known?.hash
        if (item.deleted) {
            if (local != null && !changedHere) store.removeSyncedFolder(item.id)
            if (changedHere) state.items[key] = Synced(item.revision, "") else state.items.remove(key)
            return
        }
        val theirs = syncJson.decodeFromJsonElement<FolderBody>(item.body)
        val theirHash = hash(theirs)
        if (changedHere && localHash != theirHash) {
            state.items[key] = Synced(item.revision, theirHash)
            return
        }
        if (localHash != theirHash) store.putSyncedFolder(theirs.folder)
        state.items[key] = Synced(item.revision, theirHash)
    }

    private suspend fun receiveDocument(key: String, known: Synced?, item: RemoteItem) {
        val id = item.id
        val local = store.state.value.document(id)
        val localHash = local?.let { localHash(id) }
        val changedHere = local != null && localHash != known?.hash
        if (item.deleted) {
            when {
                local == null -> state.items.remove(key)
                changedHere -> state.items[key] = Synced(item.revision, "")
                store.removeSyncedDocument(id) { localHash(id) == localHash } -> {
                    state.items.remove(key)
                    open.reload(id)
                }
                else -> state.items[key] = Synced(item.revision, "")
            }
            return
        }
        val theirs = syncJson.decodeFromJsonElement<DocumentBody>(item.body)
        val theirHash = hash(theirs)
        when {
            local == null -> {
                install(id, theirs, donor = null) { store.state.value.document(id) == null }
                state.items[key] = Synced(item.revision, localHash(id))
            }
            localHash == theirHash -> state.items[key] = Synced(item.revision, theirHash)
            !changedHere && install(id, theirs, donor = null) { localHash(id) == localHash } -> {
                state.items[key] = Synced(item.revision, localHash(id))
                open.reload(id)
            }
            else -> state.conflicts[key] = item
        }
    }

    private suspend fun push() {
        val library = store.state.value
        library.folders.forEach { pushFolder(it) }
        library.documents.forEach { pushDocument(it) }
        val gone = state.items.keys.filter { key ->
            val (kind, id) = split(key)
            key !in state.conflicts && if (kind == KIND_DOCUMENT) library.document(id) == null else library.folder(id) == null
        }
        gone.sortedBy { split(it).first == KIND_FOLDER }.forEach { pushDeletion(it) }
        publishConflicts()
    }

    private suspend fun pushFolder(folder: Folder, retry: Boolean = true) {
        val key = key(KIND_FOLDER, folder.id)
        val body = folderBody(folder)
        val hash = hash(body)
        val known = state.items[key]
        if (known?.hash == hash) return
        try {
            val revision = service.putItem(KIND_FOLDER, folder.id, known?.revision ?: 0, syncJson.encodeToJsonElement(body))
            state.items[key] = Synced(revision, hash)
        } catch (e: ApiException) {
            val item = e.item ?: throw e
            receive(item)
            store.state.value.folder(folder.id)?.takeIf { retry }?.let { pushFolder(it, retry = false) }
        }
    }

    private suspend fun pushDocument(doc: Document, retry: Boolean = true) {
        val key = key(KIND_DOCUMENT, doc.id)
        if (key in state.conflicts) return
        val body = documentBody(doc)
        val hash = hash(body)
        val known = state.items[key]
        if (known?.hash == hash) return
        try {
            upload(doc.id, body)
            val revision = service.putItem(KIND_DOCUMENT, doc.id, known?.revision ?: 0, syncJson.encodeToJsonElement(body))
            state.items[key] = Synced(revision, hash)
        } catch (e: ApiException) {
            val item = e.item
            when {
                item != null -> {
                    receive(item)
                    if (retry && key !in state.conflicts) store.state.value.document(doc.id)?.let { pushDocument(it, retry = false) }
                }
                e.code == "hash_mismatch" || e.code == "missing_blobs" -> if (retry) store.state.value.document(doc.id)?.let { pushDocument(it, retry = false) }
                else -> throw e
            }
        }
    }

    private suspend fun pushDeletion(key: String) {
        val (kind, id) = split(key)
        val known = state.items[key] ?: return
        try {
            service.deleteItem(kind, id, known.revision)
            state.items.remove(key)
        } catch (e: ApiException) {
            val item = e.item
            when {
                e.status == 404 -> state.items.remove(key)
                item != null -> receive(item)
                else -> throw e
            }
        }
    }

    /** Sends the server the files of [body] it doesn't have, a few at a time so round trips overlap. */
    private suspend fun upload(documentId: String, body: DocumentBody) {
        val missing = service.missingBlobs(body.blobs)
        if (missing.isEmpty()) return
        val files = store.documentFiles(documentId)
        val byHash = files.blobs + body.pages.entries.mapNotNull { (pageId, sha) -> files.pages[pageId]?.let { sha to it } }
        val gate = Semaphore(PARALLEL_UPLOADS)
        coroutineScope {
            missing.mapNotNull { sha -> byHash[sha]?.let { sha to it } }
                .map { (sha, file) -> async { gate.withPermit { service.putBlob(sha, file) } } }
                .awaitAll()
        }
    }

    /**
     * Downloads what [body] needs that the document's package lacks, then puts it in place if
     * [unchanged] still holds. Files the [donor] document has are copied instead of downloaded.
     */
    private suspend fun install(targetId: String, body: DocumentBody, donor: String?, unchanged: () -> Boolean): Boolean {
        val dir = File(staging, targetId).apply { deleteRecursively(); mkdirs() }
        try {
            val have = store.documentFiles(targetId)
            val spare = donor?.let { it to store.documentFiles(it) }
            val pages = body.pages.mapValues { (pageId, sha) ->
                val mine = have.pages[pageId]
                if (mine != null && pageHash(targetId, pageId, mine) == sha) return@mapValues null
                val copy = spare?.let { (id, files) -> files.pages[pageId]?.takeIf { pageHash(id, pageId, it) == sha } }
                fetch(sha, copy, dir)
            }
            val files = body.files.associateWith { sha ->
                if (have.blobs[sha] != null) null else fetch(sha, spare?.second?.blobs?.get(sha), dir)
            }
            val placed = store.putSyncedDocument(body.document.copy(id = targetId), pages, files, unchanged)
            if (placed) {
                val now = store.documentFiles(targetId)
                body.pages.forEach { (pageId, sha) -> now.pages[pageId]?.let { remember(targetId, pageId, it, sha) } }
            }
            return placed
        } finally {
            dir.deleteRecursively()
        }
    }

    private suspend fun fetch(sha: String, copy: File?, dir: File): File {
        val target = File(dir, sha)
        if (target.exists()) {
            val again = File(dir, "${newId()}-$sha")
            Files.copy(target.toPath(), again.toPath())
            return again
        }
        if (copy != null) Files.copy(copy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) else service.getBlob(sha, target)
        return target
    }

    private fun documentBody(doc: Document): DocumentBody {
        val files = store.documentFiles(doc.id)
        val pages = sortedMapOf<String, String>()
        doc.pages.forEach { page -> files.pages[page.id]?.let { pages[page.id] = pageHash(doc.id, page.id, it) } }
        val blobs = files.blobs.keys.sorted()
        return DocumentBody(
            document = doc.copy(opened = null),
            pages = pages,
            files = blobs,
            blobs = (pages.values + blobs).distinct().sorted(),
        )
    }

    private fun folderBody(folder: Folder) = FolderBody(folder = folder)

    /** The body hash of the document as it is here. */
    private fun localHash(id: String): String = store.state.value.document(id)?.let { hash(documentBody(it)) } ?: ""

    private fun hash(body: DocumentBody) = sha256(syncJson.encodeToString(DocumentBody.serializer(), body).toByteArray())

    private fun hash(body: FolderBody) = sha256(syncJson.encodeToString(FolderBody.serializer(), body).toByteArray())

    /** A page file's hash, from the cache while its size and time haven't changed. */
    private fun pageHash(documentId: String, pageId: String, file: File): String {
        val cacheKey = "$documentId/$pageId"
        val cached = state.pageHashes[cacheKey]
        val size = file.length()
        val modified = file.lastModified()
        if (cached != null && cached.size == size && cached.modified == modified) return cached.sha
        return sha256(file.readBytes()).also { state.pageHashes[cacheKey] = PageHash(size, modified, it) }
    }

    private fun remember(documentId: String, pageId: String, file: File, sha: String) {
        state.pageHashes["$documentId/$pageId"] = PageHash(file.length(), file.lastModified(), sha)
    }

    private fun publishConflicts() {
        _conflicts.value = conflictList()
    }

    private fun conflictList(): List<Conflict> = state.conflicts.values.map { item ->
        val title = store.state.value.document(item.id)?.title
            ?: runCatching { syncJson.decodeFromJsonElement<DocumentBody>(item.body).document.title }.getOrNull()
            ?: ""
        Conflict(item.id, title, item.deleted)
    }

    private fun read(): SyncState = runCatching { syncJson.decodeFromString<SyncState>(stateFile.readText()) }.getOrNull() ?: SyncState()

    private fun save() {
        val known = store.state.value.documents.map { it.id }.toSet()
        state.pageHashes.keys.removeAll { it.substringBefore('/') !in known }
        dir.mkdirs()
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(syncJson.encodeToString(SyncState.serializer(), state))
        Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        /** Stays under the server's request rate limit even with quick round trips. */
        const val PARALLEL_UPLOADS = 6

        fun key(kind: String, id: String) = "$kind:$id"

        fun split(key: String): Pair<String, String> = key.substringBefore(':') to key.substringAfter(':')

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** The revision and body hash this device and the server last agreed on; an empty hash means push regardless. */
@Serializable
data class Synced(val revision: Long, val hash: String)

@Serializable
data class PageHash(val size: Long, val modified: Long, val sha: String)

@Serializable
class SyncState(
    val userId: String? = null,
    var cursor: Long = 0,
    val items: MutableMap<String, Synced> = mutableMapOf(),
    val conflicts: MutableMap<String, RemoteItem> = mutableMapOf(),
    val pageHashes: MutableMap<String, PageHash> = mutableMapOf(),
)
