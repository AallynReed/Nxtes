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

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val PDF_BYTES = "%PDF-1.7 fake".toByteArray()
    }
}
