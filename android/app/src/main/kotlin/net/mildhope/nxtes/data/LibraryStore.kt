package net.mildhope.nxtes.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
    private val templatesDir = File(root, "templates")
    private val templatesFile = File(templatesDir, "templates.json")
    private val stickersDir = File(root, "stickers")
    private val packsFile = File(stickersDir, "packs.json")
    private val _packs = MutableStateFlow<List<StickerPack>>(emptyList())

    /** The user's sticker packs. */
    val stickerPacks: StateFlow<List<StickerPack>> = _packs.asStateFlow()

    private val fontsDir = File(root, "fonts")
    private val fontsFile = File(fontsDir, "fonts.json")
    private val _fonts = MutableStateFlow<List<FontFile>>(emptyList())

    /** Font files the user added, by name. */
    val fonts: StateFlow<List<FontFile>> = _fonts.asStateFlow()
    private val _state = MutableStateFlow(Library())
    private val _templates = MutableStateFlow<List<CustomTemplate>>(emptyList())

    val state: StateFlow<Library> = _state.asStateFlow()

    /** Templates made from the user's images and PDFs, newest first. */
    val templates: StateFlow<List<CustomTemplate>> = _templates.asStateFlow()

    fun templateFile(template: CustomTemplate) = File(templatesDir, "blobs/${template.blob}")

    fun documentDir(id: String) = File(documentsDir, id)

    fun blobFile(documentId: String, sha256: String) = File(documentDir(documentId), "blobs/$sha256")

    /** Bytes the document uses on disk. */
    fun documentSize(documentId: String): Long = documentDir(documentId).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun readPage(documentId: String, pageId: String): PageContent {
        val file = pageFile(documentId, pageId)
        return if (file.exists()) PageCodec.decode(file.readBytes()) else PageContent.EMPTY
    }

    /** Saves the given pages and marks the document as modified. */
    @Synchronized
    fun writePages(documentId: String, pages: Map<String, PageContent>) {
        pages.forEach { (pageId, content) -> writePage(documentId, pageId, content) }
        val now = clock()
        updateItem(documentId, { it }, { it.copy(modified = now) })
    }

    /** Stores a file, such as a picture or font, in the document and returns its SHA-256. */
    @Synchronized
    fun addBlob(documentId: String, input: InputStream): String = storeBlob(input, File(documentDir(documentId), "blobs"))

    private fun writePage(documentId: String, pageId: String, content: PageContent) {
        val file = pageFile(documentId, pageId)
        if (content.isEmpty) file.delete() else writeAtomically(file, PageCodec.encode(content))
    }

    private fun pageFile(documentId: String, pageId: String) = File(documentDir(documentId), "pages/$pageId.pb")

    @Synchronized
    fun load() {
        val folders = if (foldersFile.exists()) json.decodeFromString<List<Folder>>(foldersFile.readText()) else emptyList()
        val documents = documentsDir.listFiles().orEmpty().mapNotNull { dir ->
            val file = File(dir, DOCUMENT_JSON)
            if (file.exists()) runCatching { json.decodeFromString<Document>(file.readText()) }.getOrNull() else null
        }
        _state.value = Library(folders, documents, loaded = true)
        _templates.value = if (templatesFile.exists()) json.decodeFromString(templatesFile.readText()) else emptyList()
        _fonts.value = if (fontsFile.exists()) json.decodeFromString(fontsFile.readText()) else emptyList()
        _packs.value = if (packsFile.exists()) json.decodeFromString(packsFile.readText()) else emptyList()
    }

    @Synchronized
    fun importPdf(input: InputStream, title: String, folderId: String?): Document {
        val id = newId()
        val dir = documentDir(id)
        try {
            val sha256 = storeBlob(input, File(dir, "blobs"))
            val pages = inspectPdf(blobFile(id, sha256)).mapIndexed { i, size ->
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
    fun createNote(title: String, folderId: String?, choice: TemplateChoice, cover: Int?): Document {
        val id = newId()
        try {
            val template = pageTemplate(id, choice)
            val now = clock()
            val doc = Document(
                id = id, title = title, folderId = folderId, created = now, modified = now,
                note = true, cover = cover, template = template, pages = listOf(newPage(template)),
            )
            writeDocument(doc)
            _state.value = _state.value.let { it.copy(documents = it.documents + doc) }
            return doc
        } catch (e: Exception) {
            documentDir(id).deleteRecursively()
            throw e
        }
    }

    @Synchronized
    fun setCover(id: String, cover: Int?) {
        val now = clock()
        updateItem(id, { it }, { it.copy(cover = cover, modified = now) })
    }

    /** Makes [choice] the template for new pages and, if [allPages], gives every page its background and size. */
    @Synchronized
    fun setTemplate(id: String, choice: TemplateChoice, allPages: Boolean) {
        val template = pageTemplate(id, choice)
        val now = clock()
        updateItem(id, { it }) { doc ->
            val pages = if (allPages) doc.pages.map { it.withTemplate(template) } else doc.pages
            doc.copy(template = template, pages = pages, modified = now)
        }
    }

    /** Gives the pages [choice]'s background and size, keeping their ink. */
    @Synchronized
    fun setPagesTemplate(documentId: String, pageIds: Collection<String>, choice: TemplateChoice) {
        val template = pageTemplate(documentId, choice)
        updateDocument(documentId) { doc -> doc.copy(pages = doc.pages.map { if (it.id in pageIds) it.withTemplate(template) else it }) }
        pruneBlobs(documentId)
    }

    /** Saves the first page of a PDF as a custom template. */
    @Synchronized
    fun addCustomTemplate(input: InputStream, name: String): CustomTemplate {
        val sha256 = storeBlob(input, File(templatesDir, "blobs"))
        val size = inspectPdf(File(templatesDir, "blobs/$sha256")).firstOrNull()
        requireNotNull(size) { "The PDF has no pages" }
        val template = CustomTemplate(newId(), name, sha256, size.width, size.height, clock())
        writeTemplates(listOf(template) + _templates.value)
        return template
    }

    fun stickerFile(blob: String) = File(stickersDir, "blobs/$blob")

    @Synchronized
    fun addStickerPack(name: String): StickerPack {
        val pack = StickerPack(newId(), name)
        writePacks(_packs.value + pack)
        return pack
    }

    @Synchronized
    fun renameStickerPack(id: String, name: String) = writePacks(_packs.value.map { if (it.id == id) it.copy(name = name) else it })

    @Synchronized
    fun deleteStickerPack(id: String) {
        val doomed = _packs.value.find { it.id == id } ?: return
        writePacks(_packs.value - doomed)
        doomed.stickers.forEach(::deleteStickerBlobIfUnused)
    }

    /** Adds an image file (PNG or JPEG, [width]x[height] pixels) to a pack. */
    @Synchronized
    fun addSticker(packId: String, input: InputStream, width: Int, height: Int): Sticker {
        val sticker = Sticker(newId(), storeBlob(input, File(stickersDir, "blobs")), width, height)
        writePacks(_packs.value.map { if (it.id == packId) it.copy(stickers = it.stickers + sticker) else it })
        return sticker
    }

    @Synchronized
    fun deleteSticker(packId: String, stickerId: String) {
        val pack = _packs.value.find { it.id == packId } ?: return
        val doomed = pack.stickers.find { it.id == stickerId } ?: return
        writePacks(_packs.value.map { if (it.id == packId) it.copy(stickers = it.stickers - doomed) else it })
        deleteStickerBlobIfUnused(doomed)
    }

    /** Copies a sticker's image into the document so it doesn't depend on the sticker library. */
    @Synchronized
    fun useSticker(documentId: String, blob: String) {
        val target = blobFile(documentId, blob)
        if (!target.exists()) writeAtomically(target, stickerFile(blob).readBytes())
    }

    private fun deleteStickerBlobIfUnused(sticker: Sticker) {
        if (_packs.value.none { pack -> pack.stickers.any { it.blob == sticker.blob } }) stickerFile(sticker.blob).delete()
    }

    private fun writePacks(packs: List<StickerPack>) {
        writeAtomically(packsFile, json.encodeToString(packs))
        _packs.value = packs
    }

    /** Adds a .ttf or .otf font; adding the same file twice keeps one. */
    @Synchronized
    fun addFont(input: InputStream, name: String): FontFile {
        val sha256 = storeBlob(input, fontsDir)
        _fonts.value.find { it.blob == sha256 }?.let { return it }
        val font = FontFile(sha256, name)
        writeFonts((_fonts.value + font).sortedBy { it.name.lowercase() })
        return font
    }

    @Synchronized
    fun deleteFont(blob: String) {
        writeFonts(_fonts.value.filter { it.blob != blob })
        File(fontsDir, blob).delete()
    }

    fun fontFile(blob: String) = File(fontsDir, blob)

    /** Copies an added font into the document so its text boxes keep working anywhere; returns its font key. */
    @Synchronized
    fun useFont(documentId: String, blob: String): String {
        val target = blobFile(documentId, blob)
        if (!target.exists()) writeAtomically(target, File(fontsDir, blob).readBytes())
        return PageContent.FONT_BLOB + blob
    }

    private fun writeFonts(fonts: List<FontFile>) {
        writeAtomically(fontsFile, json.encodeToString(fonts))
        _fonts.value = fonts
    }

    /** Removes a custom template. Documents that used it keep their own copy of its page. */
    @Synchronized
    fun deleteCustomTemplate(id: String) {
        val doomed = _templates.value.find { it.id == id } ?: return
        val remaining = _templates.value - doomed
        writeTemplates(remaining)
        if (remaining.none { it.blob == doomed.blob }) templateFile(doomed).delete()
    }

    /** Inserts every page of a PDF at [index]. */
    @Synchronized
    fun insertPdfPages(documentId: String, index: Int, input: InputStream): List<Page> {
        val sha256 = storeBlob(input, File(documentDir(documentId), "blobs"))
        val pages = inspectPdf(blobFile(documentId, sha256)).mapIndexed { i, size -> Page(newId(), size.width, size.height, PdfSource(sha256, i)) }
        require(pages.isNotEmpty()) { "The PDF has no pages" }
        updateDocument(documentId) { doc ->
            val at = index.coerceIn(0, doc.pages.size)
            doc.copy(pages = doc.pages.take(at) + pages + doc.pages.drop(at))
        }
        return pages
    }

    /** Moves a finished recording's audio file into the document and lists it there. */
    @Synchronized
    fun addRecording(documentId: String, audio: File, title: String, spans: List<AudioSpan>): Recording {
        val blob = audio.inputStream().use { addBlob(documentId, it) }
        val recording = Recording(newId(), title, blob, spans)
        updateDocument(documentId) { it.copy(recordings = it.recordings + recording) }
        audio.delete()
        return recording
    }

    @Synchronized
    fun renameRecording(documentId: String, id: String, title: String) {
        updateDocument(documentId) { doc -> doc.copy(recordings = doc.recordings.map { if (it.id == id) it.copy(title = title) else it }) }
    }

    @Synchronized
    fun deleteRecording(documentId: String, id: String) {
        updateDocument(documentId) { doc -> doc.copy(recordings = doc.recordings.filter { it.id != id }) }
        pruneBlobs(documentId)
    }

    @Synchronized
    fun addBookmark(documentId: String, pageId: String, title: String): Bookmark {
        val bookmark = Bookmark(newId(), pageId, title)
        updateDocument(documentId) { it.copy(bookmarks = it.bookmarks + bookmark) }
        return bookmark
    }

    @Synchronized
    fun renameBookmark(documentId: String, id: String, title: String) {
        updateDocument(documentId) { doc -> doc.copy(bookmarks = doc.bookmarks.map { if (it.id == id) it.copy(title = title) else it }) }
    }

    @Synchronized
    fun deleteBookmark(documentId: String, id: String) {
        updateDocument(documentId) { doc -> doc.copy(bookmarks = doc.bookmarks.filter { it.id != id }) }
    }

    @Synchronized
    fun addOutline(documentId: String, pageId: String, title: String): OutlineItem {
        val item = OutlineItem(newId(), pageId, title)
        updateDocument(documentId) { it.copy(outline = it.outline + item) }
        return item
    }

    @Synchronized
    fun updateOutline(documentId: String, id: String, pageId: String, title: String) {
        updateDocument(documentId) { doc -> doc.copy(outline = doc.outline.map { if (it.id == id) it.copy(pageId = pageId, title = title) else it }) }
    }

    @Synchronized
    fun deleteOutline(documentId: String, id: String) {
        updateDocument(documentId) { doc -> doc.copy(outline = doc.outline.filter { it.id != id }) }
    }

    /** Pages that have content, judged by their page files. */
    fun annotatedPages(documentId: String): Set<String> =
        File(documentDir(documentId), "pages").listFiles().orEmpty()
            .filter { it.name.endsWith(".pb") }
            .map { it.name.removeSuffix(".pb") }
            .toSet()

    /**
     * Inserts a blank page at [index] from [choice], or else from the document's template, or else
     * as a white page sized like its neighbour.
     */
    @Synchronized
    fun insertPage(documentId: String, index: Int, choice: TemplateChoice? = null): Page {
        lateinit var page: Page
        val picked = choice?.let { pageTemplate(documentId, it) }
        updateDocument(documentId) { doc ->
            val at = index.coerceIn(0, doc.pages.size)
            val neighbour = doc.pages.getOrNull(at - 1) ?: doc.pages[at]
            page = newPage(picked ?: doc.template ?: PageTemplate(neighbour.width, neighbour.height))
            doc.copy(pages = doc.pages.toMutableList().apply { add(at, page) })
        }
        return page
    }

    /** Puts a copy of each page, with its ink, right after it. */
    @Synchronized
    fun duplicatePages(documentId: String, pageIds: Collection<String>): List<Page> {
        val copies = mutableListOf<Page>()
        updateDocument(documentId) { doc ->
            doc.copy(
                pages = doc.pages.flatMap { page ->
                    if (page.id !in pageIds) return@flatMap listOf(page)
                    val copy = page.copy(id = newId())
                    copyContent(documentId, page.id, documentId, copy.id)
                    copies += copy
                    listOf(page, copy)
                },
            )
        }
        return copies
    }

    /** Deletes pages and their ink. Deleting every page moves the document to the trash instead. */
    @Synchronized
    fun deletePages(documentId: String, pageIds: Collection<String>) {
        if (requireDocument(documentId).pages.all { it.id in pageIds }) {
            trash(documentId)
            return
        }
        updateDocument(documentId) { doc ->
            doc.copy(
                pages = doc.pages.filter { it.id !in pageIds },
                bookmarks = doc.bookmarks.filter { it.pageId !in pageIds },
                outline = doc.outline.filter { it.pageId !in pageIds },
            )
        }
        pageIds.forEach { pageFile(documentId, it).delete() }
        pruneBlobs(documentId)
    }

    /** Moves the pages, keeping their order, to just before the page that was at [toIndex] (or to the end). */
    @Synchronized
    fun reorderPages(documentId: String, pageIds: Collection<String>, toIndex: Int) {
        updateDocument(documentId) { doc ->
            val moving = doc.pages.filter { it.id in pageIds }
            val staying = doc.pages.filter { it.id !in pageIds }
            val at = doc.pages.take(toIndex.coerceIn(0, doc.pages.size)).count { it.id !in pageIds }
            doc.copy(pages = staying.take(at) + moving + staying.drop(at))
        }
    }

    /** Turns pages a quarter turn, with everything on them. */
    @Synchronized
    fun turnPages(documentId: String, pageIds: Collection<String>, clockwise: Boolean) {
        updateDocument(documentId) { doc ->
            doc.copy(
                pages = doc.pages.map { page ->
                    if (page.id !in pageIds) return@map page
                    val content = readPage(documentId, page.id)
                    if (!content.isEmpty) {
                        writePage(documentId, page.id, content.transformed(Affine.quarterTurn(clockwise, page.width, page.height)))
                    }
                    page.copy(width = page.height, height = page.width, turns = (page.turns + if (clockwise) 1 else 3) % 4)
                },
            )
        }
    }

    /** Copies pages, with their content and PDF pages, into another document at [index], or at the end. */
    @Synchronized
    fun copyPages(fromId: String, pageIds: Collection<String>, toId: String, index: Int? = null): List<Page> {
        val copies = requireDocument(fromId).pages.filter { it.id in pageIds }.map { page ->
            page.pdf?.let { copyBlob(fromId, it.blob, toId) }
            page.copy(id = newId()).also { copyContent(fromId, page.id, toId, it.id) }
        }
        updateDocument(toId) { doc ->
            val at = (index ?: doc.pages.size).coerceIn(0, doc.pages.size)
            doc.copy(pages = doc.pages.take(at) + copies + doc.pages.drop(at))
        }
        return copies
    }

    /** Moves pages to the end of another document. Moving every page trashes the source document. */
    @Synchronized
    fun movePages(fromId: String, pageIds: Collection<String>, toId: String) {
        if (fromId == toId) return
        copyPages(fromId, pageIds, toId)
        deletePages(fromId, pageIds)
    }

    /** Copies blobs between documents, for content pasted from one into another. */
    @Synchronized
    fun copyBlobs(fromId: String, toId: String, blobs: Collection<String>) {
        if (fromId != toId) blobs.forEach { copyBlob(fromId, it, toId) }
    }

    private fun copyBlob(fromDocument: String, sha256: String, toDocument: String) {
        val target = blobFile(toDocument, sha256)
        if (target.exists()) return
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$sha256.tmp")
        blobFile(fromDocument, sha256).copyTo(tmp, overwrite = true)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Copies a page's content and the blobs it uses. */
    private fun copyContent(fromDocument: String, fromPage: String, toDocument: String, toPage: String) {
        val source = pageFile(fromDocument, fromPage)
        if (!source.exists()) return
        val bytes = source.readBytes()
        PageCodec.decode(bytes).blobs.forEach { copyBlob(fromDocument, it, toDocument) }
        writeAtomically(pageFile(toDocument, toPage), bytes)
    }

    /** Deletes blobs that no page, page content, template or recording of the document refers to any more. */
    private fun pruneBlobs(documentId: String) {
        val doc = requireDocument(documentId)
        val used = (doc.pages.mapNotNull { it.pdf?.blob } + listOfNotNull(doc.template?.pdf?.blob) + doc.recordings.map { it.blob }).toMutableSet()
        doc.pages.forEach { used += readPage(documentId, it.id).blobs }
        File(documentDir(documentId), "blobs").listFiles().orEmpty().filter { it.name !in used }.forEach { it.delete() }
    }

    private fun requireDocument(id: String) = requireNotNull(_state.value.document(id)) { "No document $id" }

    private fun updateDocument(id: String, change: (Document) -> Document) {
        val now = clock()
        updateItem(id, { it }) { change(it).copy(modified = now) }
    }

    /** Resolves a picked template into one this document can use, copying a custom template's page into it. */
    private fun pageTemplate(documentId: String, choice: TemplateChoice): PageTemplate {
        val custom = choice.customId?.let { id -> _templates.value.find { it.id == id } }
            ?: return PageTemplate(choice.width, choice.height, background = choice.background)
        val target = blobFile(documentId, custom.blob)
        if (!target.exists()) writeAtomically(target, templateFile(custom).readBytes())
        return PageTemplate(custom.width, custom.height, pdf = PdfSource(custom.blob, 0))
    }

    private fun newPage(template: PageTemplate) =
        Page(newId(), template.width, template.height, template.pdf, template.background)

    private fun Page.withTemplate(template: PageTemplate) =
        copy(width = template.width, height = template.height, pdf = template.pdf, background = template.background, turns = 0)

    /**
     * Copies a document, or a folder with everything in it, next to the original. The copy's name
     * comes from [name]; what's inside a copied folder keeps its names. Returns the copy's id.
     */
    @Synchronized
    fun duplicate(id: String, name: (String) -> String): String {
        val library = _state.value
        val folder = library.folder(id)
        if (folder == null) {
            val doc = requireDocument(id)
            return copyDocument(doc, doc.folderId, name(doc.title)).id
        }

        val now = clock()
        val folders = mutableListOf<Folder>()
        val documents = mutableListOf<Pair<Document, String>>()
        fun copyFolder(source: Folder, parentId: String?, newName: String): String {
            val copy = source.copy(id = newId(), name = newName, parentId = parentId, created = now, modified = now, favorite = false)
            folders += copy
            library.childFolders(source.id).forEach { copyFolder(it, copy.id, it.name) }
            library.childDocuments(source.id).forEach { documents += it to copy.id }
            return copy.id
        }
        val copyId = copyFolder(folder, folder.parentId, name(folder.name))
        writeFolders(library.folders + folders)
        documents.forEach { (doc, folderId) -> copyDocument(doc, folderId, doc.title) }
        return copyId
    }

    private fun copyDocument(source: Document, folderId: String?, title: String): Document {
        val id = newId()
        try {
            documentDir(source.id).copyRecursively(documentDir(id))
            val now = clock()
            val copy = source.copy(id = id, title = title, folderId = folderId, created = now, modified = now, opened = null, favorite = false)
            writeDocument(copy)
            _state.value = _state.value.let { it.copy(documents = it.documents + copy) }
            return copy
        } catch (e: Exception) {
            documentDir(id).deleteRecursively()
            throw e
        }
    }

    /** Makes a new document from the pages of [ids] in order, next to the first one. The originals stay. */
    @Synchronized
    fun merge(ids: List<String>, title: String): Document {
        val sources = ids.map(::requireDocument)
        require(sources.size >= 2) { "Merging needs at least two documents" }
        val first = sources.first()
        val id = newId()
        try {
            val pages = mutableListOf<Page>()
            val bookmarks = mutableListOf<Bookmark>()
            val outline = mutableListOf<OutlineItem>()
            val recordings = mutableListOf<Recording>()
            sources.forEach { source ->
                source.recordings.forEach { copyBlob(source.id, it.blob, id) }
                recordings += source.recordings
                val newIds = source.pages.associate { it.id to newId() }
                source.pages.forEach { page ->
                    page.pdf?.let { copyBlob(source.id, it.blob, id) }
                    copyContent(source.id, page.id, id, newIds.getValue(page.id))
                    pages += page.copy(id = newIds.getValue(page.id))
                }
                bookmarks += source.bookmarks.mapNotNull { mark -> newIds[mark.pageId]?.let { mark.copy(id = newId(), pageId = it) } }
                outline += source.outline.mapNotNull { item -> newIds[item.pageId]?.let { item.copy(id = newId(), pageId = it) } }
            }
            first.template?.pdf?.let { copyBlob(first.id, it.blob, id) }
            val now = clock()
            val doc = Document(
                id = id, title = title, folderId = first.folderId, created = now, modified = now,
                note = first.note, cover = first.cover, template = first.template,
                bookmarks = bookmarks, outline = outline, pages = pages, recordings = recordings,
            )
            writeDocument(doc)
            _state.value = _state.value.let { it.copy(documents = it.documents + doc) }
            return doc
        } catch (e: Exception) {
            documentDir(id).deleteRecursively()
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

    /**
     * Adds a document built elsewhere, such as by an importer, with its [blobs] (files by SHA-256,
     * copied in) and the content of its [pages].
     */
    @Synchronized
    fun addDocument(doc: Document, blobs: Map<String, File>, pages: Map<String, PageContent>): Document {
        try {
            blobs.forEach { (sha256, file) ->
                val target = blobFile(doc.id, sha256)
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "$sha256.tmp")
                file.copyTo(tmp, overwrite = true)
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            pages.forEach { (pageId, content) -> writePage(doc.id, pageId, content) }
            writeDocument(doc)
            _state.value = _state.value.let { it.copy(documents = it.documents + doc) }
            return doc
        } catch (e: Exception) {
            documentDir(doc.id).deleteRecursively()
            throw e
        }
    }

    /** Adds a folder built elsewhere, such as by an importer. */
    @Synchronized
    fun addFolder(folder: Folder) = writeFolders(_state.value.folders + folder)

    /** A document's page files by page id and its blob files by SHA-256, for sync. */
    @Synchronized
    fun documentFiles(id: String): DocumentFiles {
        val dir = documentDir(id)
        val pages = File(dir, "pages").listFiles().orEmpty().filter { it.name.endsWith(".pb") }.associateBy { it.name.removeSuffix(".pb") }
        val blobs = File(dir, "blobs").listFiles().orEmpty().filter { SHA256.matches(it.name) }.associateBy { it.name }
        return DocumentFiles(pages, blobs)
    }

    /**
     * Puts a document that came from sync in place, if [unchanged] still holds once the library is
     * locked. [pages] and [blobs] list all it has: a file moves in, null keeps the one here, and
     * anything unlisted goes. When it was last opened here is kept.
     */
    @Synchronized
    fun putSyncedDocument(doc: Document, pages: Map<String, File?>, blobs: Map<String, File?>, unchanged: () -> Boolean): Boolean {
        if (!unchanged()) return false
        val dir = documentDir(doc.id)
        pages.forEach { (pageId, file) -> file?.let { moveInto(it, pageFile(doc.id, pageId)) } }
        blobs.forEach { (sha256, file) -> file?.let { moveInto(it, blobFile(doc.id, sha256)) } }
        File(dir, "pages").listFiles().orEmpty().filter { it.name.endsWith(".pb") && it.name.removeSuffix(".pb") !in pages }.forEach { it.delete() }
        File(dir, "blobs").listFiles().orEmpty().filter { it.name !in blobs }.forEach { it.delete() }
        val placed = doc.copy(opened = _state.value.document(doc.id)?.opened)
        writeDocument(placed)
        _state.value = _state.value.let { lib -> lib.copy(documents = lib.documents.filter { it.id != doc.id } + placed) }
        return true
    }

    /** Deletes a document that was deleted elsewhere, if [unchanged] still holds once the library is locked. */
    @Synchronized
    fun removeSyncedDocument(id: String, unchanged: () -> Boolean): Boolean {
        if (!unchanged()) return false
        deletePermanently(id)
        return true
    }

    /** Adds or replaces a folder that came from sync. */
    @Synchronized
    fun putSyncedFolder(folder: Folder) {
        val folders = _state.value.folders
        writeFolders(if (folders.any { it.id == folder.id }) folders.map { if (it.id == folder.id) folder else it } else folders + folder)
    }

    /** Removes a folder that was deleted elsewhere; whatever is still in it moves to the top level. */
    @Synchronized
    fun removeSyncedFolder(id: String) {
        val library = _state.value
        if (library.folder(id) == null) return
        writeFolders(library.folders.filter { it.id != id }.map { if (it.parentId == id) it.copy(parentId = null) else it })
        library.documents.filter { it.folderId == id }.forEach { doc -> updateItem(doc.id, { it }, { it.copy(folderId = null) }) }
    }

    private fun moveInto(source: File, target: File) {
        target.parentFile?.mkdirs()
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
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

    private fun writeTemplates(templates: List<CustomTemplate>) {
        writeAtomically(templatesFile, json.encodeToString(templates))
        _templates.value = templates
    }

    /** Copies [input] into [dir] named by its SHA-256, which it returns. */
    private fun storeBlob(input: InputStream, dir: File): String {
        dir.mkdirs()
        val tmp = File(dir, "${newId()}.tmp")
        try {
            val sha256 = tmp.outputStream().use { out -> copyHashing(input, out) }
            Files.move(tmp.toPath(), File(dir, sha256).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return sha256
        } finally {
            tmp.delete()
        }
    }

    /** Writes the whole library as a zip: every document with its blobs, the folders, templates, fonts and stickers. */
    @Synchronized
    fun backup(out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_MANIFEST))
            zip.write(json.encodeToString(BackupManifest(BACKUP_FORMAT, clock())).toByteArray())
            zip.closeEntry()
            root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Adds what's in a backup to the library: documents, folders, templates, fonts and stickers it
     * doesn't have yet. Nothing already here is replaced. Returns how many documents were added.
     */
    @Synchronized
    fun restore(input: InputStream): Int {
        val staging = File(root, ".restore").apply { deleteRecursively(); mkdirs() }
        try {
            var manifest = false
            ZipInputStream(input.buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    val target = File(staging, entry.name).canonicalFile
                    require(target.path.startsWith(staging.canonicalPath + File.separator)) { "Bad entry ${entry.name}" }
                    if (entry.name == BACKUP_MANIFEST) manifest = true
                    if (!entry.isDirectory) {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            require(manifest) { "Not a Nxtes backup" }
            val have = _state.value
            var added = 0
            File(staging, "documents").listFiles().orEmpty().forEach { dir ->
                val doc = runCatching { json.decodeFromString<Document>(File(dir, DOCUMENT_JSON).readText()) }.getOrNull() ?: return@forEach
                if (have.document(doc.id) != null || documentDir(doc.id).exists()) return@forEach
                dir.copyRecursively(documentDir(doc.id))
                added++
            }
            File(staging, "folders.json").takeIf { it.exists() }?.let { file ->
                val folders = json.decodeFromString<List<Folder>>(file.readText())
                val known = have.folders.map { it.id }.toSet()
                writeFolders(have.folders + folders.filter { it.id !in known })
            }
            mergeList<CustomTemplate>(File(staging, "templates"), templatesDir, templatesFile.name) { it.id }
            mergeList<FontFile>(File(staging, "fonts"), fontsDir, fontsFile.name) { it.blob }
            mergeList<StickerPack>(File(staging, "stickers"), stickersDir, packsFile.name) { it.id }
            load()
            return added
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Copies a backed-up folder's files that are missing here, and adds its list entries this library lacks. */
    private inline fun <reified T> mergeList(from: File, to: File, listName: String, key: (T) -> String) {
        if (!from.exists()) return
        from.walkTopDown().filter { it.isFile && it.name != listName }.forEach { file ->
            val target = File(to, file.relativeTo(from).path)
            if (!target.exists()) file.copyTo(target)
        }
        val theirs = File(from, listName).takeIf { it.exists() }?.let { json.decodeFromString<List<T>>(it.readText()) } ?: return
        val mineFile = File(to, listName)
        val mine = if (mineFile.exists()) json.decodeFromString<List<T>>(mineFile.readText()) else emptyList()
        val known = mine.map(key).toSet()
        writeAtomically(mineFile, json.encodeToString(mine + theirs.filter { key(it) !in known }))
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
        const val BACKUP_MANIFEST = "nxtes-backup.json"
        const val BACKUP_FORMAT = 1
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/** A document's page files by page id and its blob files by SHA-256. */
class DocumentFiles(val pages: Map<String, File>, val blobs: Map<String, File>)

/** Marks a zip as a Nxtes backup, with its format and when it was made. */
@kotlinx.serialization.Serializable
private class BackupManifest(val format: Int, val created: Long)

