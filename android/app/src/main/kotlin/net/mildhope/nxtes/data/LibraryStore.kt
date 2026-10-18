package net.mildhope.nxtes.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * The library on disk: one package directory per document plus `folders.json`.
 * Calls block on file IO, so run them off the main thread.
 */
class LibraryStore(
    private val root: File,
    private val inspectPdf: (File) -> List<PageSize>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val documentsDir = File(root, "documents")
    private val foldersFile = File(root, "folders.json")
    private val _state = MutableStateFlow(Library())

    val state: StateFlow<Library> = _state.asStateFlow()

    fun documentDir(id: String) = File(documentsDir, id)

    fun blobFile(documentId: String, sha256: String) = File(documentDir(documentId), "blobs/$sha256")

    /** Bytes the document uses on disk. */
    fun documentSize(documentId: String): Long = documentDir(documentId).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun readPageInk(documentId: String, pageId: String): List<InkStroke> {
        val file = pageInkFile(documentId, pageId)
        return if (file.exists()) InkCodec.decode(file.readBytes()) else emptyList()
    }

    /** Saves ink for the given pages and marks the document as modified. */
    @Synchronized
    fun writePageInk(documentId: String, pages: Map<String, List<InkStroke>>) {
        pages.forEach { (pageId, strokes) ->
            val file = pageInkFile(documentId, pageId)
            if (strokes.isEmpty()) file.delete() else writeAtomically(file, InkCodec.encode(strokes))
        }
        val now = clock()
        updateItem(documentId, { it }, { it.copy(modified = now) })
    }

    private fun pageInkFile(documentId: String, pageId: String) = File(documentDir(documentId), "pages/$pageId.pb")

    @Synchronized
    fun load() {
        val folders = if (foldersFile.exists()) json.decodeFromString<List<Folder>>(foldersFile.readText()) else emptyList()
        val documents = documentsDir.listFiles().orEmpty().mapNotNull { dir ->
            val file = File(dir, DOCUMENT_JSON)
            if (file.exists()) runCatching { json.decodeFromString<Document>(file.readText()) }.getOrNull() else null
        }
        _state.value = Library(folders, documents, loaded = true)
    }

    @Synchronized
    fun importPdf(input: InputStream, title: String, folderId: String?): Document {
        val id = newId()
        val dir = documentDir(id)
        try {
            val blobs = File(dir, "blobs").apply { mkdirs() }
            val tmp = File(blobs, "import.tmp")
            val sha256 = tmp.outputStream().use { out -> copyHashing(input, out) }
            val blob = File(blobs, sha256)
            check(tmp.renameTo(blob)) { "Could not store $blob" }
            val pages = inspectPdf(blob).mapIndexed { i, size ->
                Page(newId(), size.width, size.height, PdfSource(sha256, i))
            }
            require(pages.isNotEmpty()) { "The PDF has no pages" }
            val now = clock()
            val doc = Document(id = id, title = title, folderId = folderId, created = now, modified = now, pages = pages)
            writeDocument(doc)
            _state.value = _state.value.let { it.copy(documents = it.documents + doc) }
            return doc
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    @Synchronized
    fun createFolder(name: String, parentId: String?): Folder {
        val now = clock()
        val folder = Folder(id = newId(), name = name, parentId = parentId, created = now, modified = now)
        writeFolders(_state.value.folders + folder)
        return folder
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val now = clock()
        updateItem(id, { it.copy(name = name, modified = now) }, { it.copy(title = name, modified = now) })
    }

    @Synchronized
    fun move(id: String, folderId: String?) {
        val library = _state.value
        if (library.folder(id) != null) {
            require(folderId == null || folderId !in library.subtree(id)) { "Can't move a folder into itself" }
        }
        updateItem(id, { it.copy(parentId = folderId) }, { it.copy(folderId = folderId) })
    }

    @Synchronized
    fun setFavorite(id: String, favorite: Boolean) {
        updateItem(id, { it.copy(favorite = favorite) }, { it.copy(favorite = favorite) })
    }

    @Synchronized
    fun setFolderColor(id: String, color: Int?) {
        updateItem(id, { it.copy(color = color) }, { it })
    }

    /** Records that the document was opened, without changing its modified time. */
    @Synchronized
    fun markOpened(id: String) {
        val now = clock()
        updateItem(id, { it }, { it.copy(opened = now) })
    }

    @Synchronized
    fun trash(id: String) {
        val now = clock()
        updateItem(id, { it.copy(trashedAt = now) }, { it.copy(trashedAt = now) })
    }

    /** Restores to the original folder, or to the top level if that folder is gone or trashed. */
    @Synchronized
    fun restore(id: String) {
        val library = _state.value
        fun home(folderId: String?) =
            folderId?.takeIf { library.folder(it) != null && !library.isInTrash(it) }
        updateItem(
            id,
            { it.copy(trashedAt = null, parentId = home(it.parentId)) },
            { it.copy(trashedAt = null, folderId = home(it.folderId)) },
        )
    }

    @Synchronized
    fun deletePermanently(id: String) {
        val library = _state.value
        if (library.folder(id) != null) {
            val doomed = library.subtree(id)
            library.documents.filter { it.folderId in doomed }.forEach { documentDir(it.id).deleteRecursively() }
            writeFolders(library.folders.filter { it.id !in doomed })
            _state.value = _state.value.let { lib -> lib.copy(documents = lib.documents.filter { it.folderId !in doomed }) }
        } else if (library.document(id) != null) {
            documentDir(id).deleteRecursively()
            _state.value = library.copy(documents = library.documents.filter { it.id != id })
        }
    }

    @Synchronized
    fun emptyTrash() {
        _state.value.trash().forEach { deletePermanently(it.id) }
    }

    private fun updateItem(id: String, folder: (Folder) -> Folder, document: (Document) -> Document) {
        val library = _state.value
        if (library.folder(id) != null) {
            writeFolders(library.folders.map { if (it.id == id) folder(it) else it })
        } else {
            val updated = document(requireNotNull(library.document(id)) { "No item $id" })
            writeDocument(updated)
            _state.value = library.copy(documents = library.documents.map { if (it.id == id) updated else it })
        }
    }

    private fun writeFolders(folders: List<Folder>) {
        writeAtomically(foldersFile, json.encodeToString(folders))
        _state.value = _state.value.copy(folders = folders)
    }

    private fun writeDocument(doc: Document) {
        writeAtomically(File(documentDir(doc.id), DOCUMENT_JSON), json.encodeToString(doc))
    }

    private fun writeAtomically(file: File, text: String) = writeAtomically(file, text.toByteArray())

    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun copyHashing(input: InputStream, out: java.io.OutputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            out.write(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun newId() = UUID.randomUUID().toString()

    private companion object {
        const val DOCUMENT_JSON = "document.json"
    }
}
