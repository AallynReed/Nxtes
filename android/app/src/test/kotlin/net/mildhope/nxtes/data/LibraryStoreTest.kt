package net.mildhope.nxtes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class LibraryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: LibraryStore
    private var now = 1_000L

    @Before
    fun setUp() {
        root = tmp.newFolder("library")
        store = newStore()
    }

    private fun newStore() = LibraryStore(root, { listOf(PageSize(612f, 792f), PageSize(792f, 612f)) }, { now++ })

    private fun import(title: String = "Doc", folderId: String? = null) =
        store.importPdf(ByteArrayInputStream(PDF_BYTES), title, folderId)

    @Test
    fun importStoresBlobByHashAndSurvivesReload() {
        val doc = import("Lecture")

        assertEquals(listOf(612f, 792f), doc.pages.map { it.width })
        assertEquals(listOf(0, 1), doc.pages.map { it.pdf!!.index })
        assertTrue(store.blobFile(doc.id, sha256(PDF_BYTES)).exists())

        val reloaded = newStore().apply { load() }
        assertEquals(doc, reloaded.state.value.document(doc.id))
    }

    @Test
    fun failedImportLeavesNothingBehind() {
        val failing = LibraryStore(root, { error("not a PDF") })
        runCatching { failing.importPdf(ByteArrayInputStream(PDF_BYTES), "Bad", null) }

        assertTrue(failing.state.value.documents.isEmpty())
        assertTrue(File(root, "documents").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun trashingFolderHidesItsContentsAndRestoreBringsThemBack() {
        val folder = store.createFolder("Math", null)
        val doc = import(folderId = folder.id)

        store.trash(folder.id)
        val trashed = store.state.value
        assertTrue(trashed.childFolders(null).isEmpty())
        assertEquals(listOf(folder.id), trashed.trash().map { it.id })

        store.restore(folder.id)
        assertEquals(listOf(doc.id), store.state.value.childDocuments(folder.id).map { it.id })
    }

    @Test
    fun restoringDocumentWhoseFolderIsTrashedMovesItToTop() {
        val folder = store.createFolder("Old", null)
        val doc = import(folderId = folder.id)
        store.trash(doc.id)
        store.trash(folder.id)

        store.restore(doc.id)

        val restored = store.state.value.document(doc.id)!!
        assertNull(restored.trashedAt)
        assertNull(restored.folderId)
    }

    @Test
    fun deletingFolderRemovesNestedDocumentsFromDisk() {
        val outer = store.createFolder("Outer", null)
        val inner = store.createFolder("Inner", outer.id)
        val doc = import(folderId = inner.id)
        store.trash(outer.id)

        store.emptyTrash()

        assertTrue(store.state.value.folders.isEmpty())
        assertTrue(store.state.value.documents.isEmpty())
        assertFalse(store.documentDir(doc.id).exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun folderCannotMoveIntoItsOwnSubfolder() {
        val outer = store.createFolder("Outer", null)
        val inner = store.createFolder("Inner", outer.id)

        store.move(outer.id, inner.id)
    }

    @Test
    fun renameUpdatesNameAndModifiedTime() {
        val doc = import("Draft")

        store.rename(doc.id, "Final")

        val renamed = store.state.value.document(doc.id)!!
        assertEquals("Final", renamed.title)
        assertTrue(renamed.modified > doc.modified)
    }

    @Test
    fun pageInkSurvivesReloadAndEmptyPagesLeaveNoFile() {
        val doc = import()
        val page = doc.pages.first().id
        val stroke = InkStroke(
            id = "s1", pen = Pen.BALLPOINT, color = -16777216, size = 1f, tool = InputTool.STYLUS,
            noiseSeed = 0, created = 0, x = floatArrayOf(1f, 2f), y = floatArrayOf(3f, 4f), t = intArrayOf(0, 5),
        )

        store.writePageInk(doc.id, mapOf(page to listOf(stroke)))
        assertEquals(listOf("s1"), newStore().readPageInk(doc.id, page).map { it.id })
        assertTrue(store.state.value.document(doc.id)!!.modified > doc.modified)

        store.writePageInk(doc.id, mapOf(page to emptyList()))
        assertFalse(File(store.documentDir(doc.id), "pages/$page.pb").exists())
    }

    @Test
    fun recentsFollowOpenOrderAndSkipTrash() {
        val a = import("A")
        val b = import("B")
        val c = import("C")
        store.markOpened(a.id)
        store.markOpened(c.id)
        store.markOpened(b.id)
        store.trash(c.id)

        assertEquals(listOf("B", "A"), store.state.value.recents().map { it.title })
        assertEquals(a.modified, store.state.value.document(a.id)!!.modified)
    }

    @Test
    fun favoritesAndFolderColourPersist() {
        val folder = store.createFolder("Ideas", null)
        val doc = import("Draft")
        store.setFavorite(folder.id, true)
        store.setFavorite(doc.id, true)
        store.setFolderColor(folder.id, 0xFFE57373.toInt())

        val reloaded = newStore().apply { load() }.state.value
        assertEquals(listOf("Draft", "Ideas"), reloaded.favorites().map { it.displayName })
        assertEquals(0xFFE57373.toInt(), reloaded.folder(folder.id)!!.color)
    }

    @Test
    fun searchMatchesNamesAcrossFolders() {
        val folder = store.createFolder("Physics", null)
        import("Physics notes", folder.id)
        import("Chemistry")

        assertEquals(setOf("Physics", "Physics notes"), store.state.value.search("phys").map { it.displayName }.toSet())
    }

    @Test
    fun newNoteHasOneTemplatePageAndSurvivesReload() {
        val choice = TemplateChoice(Background(Pattern.DOTTED, Paper.YELLOW), PaperSize.LETTER, landscape = true)

        val note = store.createNote("Ideas", null, choice, cover = 3)

        val page = note.pages.single()
        assertEquals(792f to 612f, page.width to page.height)
        assertEquals(Background(Pattern.DOTTED, Paper.YELLOW), page.background)
        assertTrue(note.note)
        assertEquals(note, newStore().apply { load() }.state.value.document(note.id))
        assertEquals(choice, TemplateChoice.of(note.template, TemplateChoice()))
    }

    @Test
    fun templateChangeReachesExistingPagesOnlyWhenAsked() {
        val note = store.createNote("Ideas", null, TemplateChoice(), cover = null)
        val graph = TemplateChoice(Background(Pattern.GRAPH))

        store.setTemplate(note.id, graph, allPages = false)
        assertEquals(Pattern.PLAIN, store.state.value.document(note.id)!!.pages.single().background!!.pattern)
        assertEquals(Pattern.GRAPH, store.state.value.document(note.id)!!.template!!.background!!.pattern)

        store.setTemplate(note.id, graph, allPages = true)
        assertEquals(Pattern.GRAPH, store.state.value.document(note.id)!!.pages.single().background!!.pattern)
    }

    @Test
    fun insertedPagesCopyTheTemplateOrTheNeighbourSize() {
        val note = store.createNote("Ideas", null, TemplateChoice(Background(Pattern.GRAPH)), cover = null)
        val pdf = import()

        val notePage = store.insertPage(note.id, 1)
        val pdfPage = store.insertPage(pdf.id, 2)

        assertEquals(Pattern.GRAPH, notePage.background!!.pattern)
        assertEquals(listOf(note.pages[0].id, notePage.id), pages(note.id))
        assertEquals(792f to 612f, pdfPage.width to pdfPage.height)
        assertNull(pdfPage.pdf)
        assertEquals(pdfPage.id, pages(pdf.id).last())
    }

    @Test
    fun duplicatedPagesFollowTheirOriginalsWithTheirInk() {
        val doc = import()
        val (first, second) = doc.pages.map { it.id }
        store.writePageInk(doc.id, mapOf(first to listOf(stroke())))

        val copy = store.duplicatePages(doc.id, listOf(first)).single()

        assertEquals(listOf(first, copy.id, second), pages(doc.id))
        assertEquals(listOf("s1"), store.readPageInk(doc.id, copy.id).map { it.id })
        assertEquals(setOf(first, copy.id), store.annotatedPages(doc.id))
    }

    @Test
    fun deletingPagesRemovesInkAndDeletingAllTrashesTheDocument() {
        val doc = import()
        val (first, second) = doc.pages.map { it.id }
        store.writePageInk(doc.id, mapOf(first to listOf(stroke())))

        store.deletePages(doc.id, listOf(first))
        assertEquals(listOf(second), pages(doc.id))
        assertTrue(store.annotatedPages(doc.id).isEmpty())

        store.deletePages(doc.id, listOf(second))
        assertEquals(listOf(second), pages(doc.id))
        assertTrue(store.state.value.document(doc.id)!!.trashedAt != null)
    }

    @Test
    fun reorderingMovesSelectedPagesTogether() {
        val doc = import()
        val c = store.insertPage(doc.id, 2).id
        val d = store.insertPage(doc.id, 3).id
        val (a, b) = doc.pages.map { it.id }

        store.reorderPages(doc.id, listOf(a, c), 4)
        assertEquals(listOf(b, d, a, c), pages(doc.id))

        store.reorderPages(doc.id, listOf(c), 0)
        assertEquals(listOf(c, b, d, a), pages(doc.id))
    }

    @Test
    fun turningAPageSwapsItsSizeAndTurnsItsInk() {
        val doc = import()
        val page = doc.pages.first()
        store.writePageInk(doc.id, mapOf(page.id to listOf(stroke())))

        store.turnPages(doc.id, listOf(page.id), clockwise = true)

        val turned = store.state.value.document(doc.id)!!.pages.first()
        assertEquals(Triple(792f, 612f, 1), Triple(turned.width, turned.height, turned.turns))
        val ink = store.readPageInk(doc.id, page.id).single()
        assertEquals(792f - 3f, ink.x[0], 0.01f)
        assertEquals(1f, ink.y[0], 0.01f)

        store.turnPages(doc.id, listOf(page.id), clockwise = false)
        val back = store.readPageInk(doc.id, page.id).single()
        assertEquals(1f, back.x[0], 0.01f)
        assertEquals(3f, back.y[0], 0.01f)
        assertEquals(0, store.state.value.document(doc.id)!!.pages.first().turns)
    }

    @Test
    fun copiedPagesBringTheirPdfAndInk() {
        val pdf = import()
        val note = store.createNote("Ideas", null, TemplateChoice(), cover = null)
        val second = pdf.pages[1]
        store.writePageInk(pdf.id, mapOf(second.id to listOf(stroke())))

        val copy = store.copyPages(pdf.id, listOf(second.id), note.id, index = 0).single()

        assertEquals(listOf(copy.id, note.pages[0].id), pages(note.id))
        assertEquals(second.pdf, copy.pdf)
        assertTrue(store.blobFile(note.id, sha256(PDF_BYTES)).exists())
        assertEquals(listOf("s1"), store.readPageInk(note.id, copy.id).map { it.id })
    }

    @Test
    fun movingEveryPageTrashesTheSourceAndPrunesItsBlobs() {
        val pdf = import()
        val note = store.createNote("Ideas", null, TemplateChoice(), cover = null)

        store.movePages(pdf.id, listOf(pdf.pages[0].id), note.id)
        assertEquals(1, store.state.value.document(pdf.id)!!.pages.size)
        assertEquals(2, store.state.value.document(note.id)!!.pages.size)
        assertTrue(store.blobFile(pdf.id, sha256(PDF_BYTES)).exists())

        store.movePages(pdf.id, listOf(pdf.pages[1].id), note.id)
        assertTrue(store.state.value.document(pdf.id)!!.trashedAt != null)
        assertEquals(3, store.state.value.document(note.id)!!.pages.size)
    }

    @Test
    fun customTemplatePagesKeepTheirCopyAfterTheTemplateIsDeleted() {
        val template = store.addCustomTemplate(ByteArrayInputStream(PDF_BYTES), "Planner")
        assertEquals(listOf(template), newStore().apply { load() }.templates.value)

        val note = store.createNote("Week", null, TemplateChoice(customId = template.id), cover = null)
        store.deleteCustomTemplate(template.id)

        val page = note.pages.single()
        assertEquals(PdfSource(sha256(PDF_BYTES), 0), page.pdf)
        assertEquals(612f to 792f, page.width to page.height)
        assertTrue(store.blobFile(note.id, page.pdf!!.blob).exists())
        assertFalse(store.templateFile(template).exists())
        assertTrue(store.templates.value.isEmpty())
    }

    @Test
    fun pagesTakeAPickedTemplateWithoutChangingTheDefault() {
        val note = store.createNote("Ideas", null, TemplateChoice(), cover = null)
        val dotted = TemplateChoice(Background(Pattern.DOTTED), PaperSize.A5)

        val added = store.insertPage(note.id, 1, dotted)
        store.setPagesTemplate(note.id, listOf(note.pages[0].id), dotted)

        val doc = store.state.value.document(note.id)!!
        assertEquals(listOf(Pattern.DOTTED, Pattern.DOTTED), doc.pages.map { it.background!!.pattern })
        assertEquals(PaperSize.A5.width, added.width)
        assertEquals(Pattern.PLAIN, doc.template!!.background!!.pattern)
    }

    @Test
    fun insertedPdfPagesLandWhereAsked() {
        val note = store.createNote("Ideas", null, TemplateChoice(), cover = null)

        val inserted = store.insertPdfPages(note.id, 0, ByteArrayInputStream(PDF_BYTES))

        assertEquals(inserted.map { it.id } + note.pages[0].id, pages(note.id))
        assertEquals(listOf(0, 1), inserted.map { it.pdf!!.index })
    }

    @Test
    fun bookmarksAndOutlineFollowTheirPages() {
        val doc = import()
        val (first, second) = doc.pages.map { it.id }
        val mark = store.addBookmark(doc.id, second, "Results")
        val entry = store.addOutline(doc.id, first, "Intro")
        store.renameBookmark(doc.id, mark.id, "Findings")
        store.updateOutline(doc.id, entry.id, second, "Method")

        val reloaded = newStore().apply { load() }.state.value.document(doc.id)!!
        assertEquals(listOf(Bookmark(mark.id, second, "Findings")), reloaded.bookmarks)
        assertEquals(listOf(OutlineItem(entry.id, second, "Method")), reloaded.outline)

        store.deletePages(doc.id, listOf(second))
        val trimmed = store.state.value.document(doc.id)!!
        assertTrue(trimmed.bookmarks.isEmpty())
        assertTrue(trimmed.outline.isEmpty())
    }

    @Test
    fun duplicatingAFolderCopiesItsContents() {
        val outer = store.createFolder("Term", null)
        val inner = store.createFolder("Week 1", outer.id)
        val doc = import("Lecture", inner.id)
        store.writePageInk(doc.id, mapOf(doc.pages[0].id to listOf(stroke())))

        val copyId = store.duplicate(outer.id) { "$it copy" }

        val library = newStore().apply { load() }.state.value
        assertEquals("Term copy", library.folder(copyId)!!.name)
        val innerCopy = library.childFolders(copyId).single()
        assertEquals("Week 1", innerCopy.name)
        val docCopy = library.childDocuments(innerCopy.id).single()
        assertEquals("Lecture", docCopy.title)
        assertEquals(listOf("s1"), store.readPageInk(docCopy.id, doc.pages[0].id).map { it.id })
        assertEquals(1, library.childDocuments(inner.id).size)
    }

    @Test
    fun mergingKeepsPageOrderBookmarksAndTheOriginals() {
        val folder = store.createFolder("Course", null)
        val a = import("A", folder.id)
        val b = store.createNote("B", null, TemplateChoice(), cover = null)
        store.addBookmark(b.id, b.pages[0].id, "Notes")
        store.writePageInk(b.id, mapOf(b.pages[0].id to listOf(stroke())))

        val merged = store.merge(listOf(a.id, b.id), "A and B")

        assertEquals(folder.id, merged.folderId)
        assertEquals(listOf(a.pages[0].pdf, a.pages[1].pdf, null), merged.pages.map { it.pdf })
        assertEquals(Pattern.PLAIN, merged.pages[2].background!!.pattern)
        assertEquals(merged.pages[2].id, merged.bookmarks.single().pageId)
        assertEquals(listOf("s1"), store.readPageInk(merged.id, merged.pages[2].id).map { it.id })
        assertTrue(store.blobFile(merged.id, sha256(PDF_BYTES)).exists())
        assertEquals(3, store.state.value.documents.size)
    }

    private fun pages(documentId: String) = store.state.value.document(documentId)!!.pages.map { it.id }

    private fun stroke() = InkStroke(
        id = "s1", pen = Pen.BALLPOINT, color = -16777216, size = 1f, tool = InputTool.STYLUS,
        noiseSeed = 0, created = 0, x = floatArrayOf(1f, 2f), y = floatArrayOf(3f, 4f), t = intArrayOf(0, 5),
    )

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val PDF_BYTES = "%PDF-1.7 fake".toByteArray()
    }
}
