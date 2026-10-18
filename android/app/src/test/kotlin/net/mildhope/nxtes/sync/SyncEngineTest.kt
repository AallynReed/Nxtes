package net.mildhope.nxtes.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.PageSize
import net.mildhope.nxtes.data.StickyNote
import net.mildhope.nxtes.data.TemplateChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = FakeServer()
    private var now = 1_000L

    private inner class Device(name: String) {
        val store = LibraryStore(tmp.newFolder("$name-library"), { listOf(PageSize(612f, 792f)) }, { now++ }).apply { load() }
        val syncDir: File = tmp.newFolder("$name-sync")
        var engine = SyncEngine(store, server, syncDir)
        val library get() = store.state.value

        fun sync() = runBlocking { engine.sync() }
    }

    private val a by lazy { Device("a") }
    private val b by lazy { Device("b") }

    private fun note(device: Device, title: String = "Ideas", text: String = "hello") =
        device.store.createNote(title, null, TemplateChoice(), cover = null).also { doc ->
            device.store.writePages(doc.id, mapOf(doc.pages[0].id to content(text)))
        }

    private fun content(text: String) = PageContent(notes = listOf(StickyNote("n1", 10f, 20f, text, 0, 1)))

    @Test
    fun noteWithItsPagesReachesTheOtherDevice() {
        val doc = note(a)
        a.sync()
        b.sync()

        val copy = b.library.document(doc.id)
        assertEquals(doc.title, copy?.title)
        assertEquals(content("hello"), b.store.readPage(doc.id, doc.pages[0].id))
        assertTrue(b.engine.conflicts.value.isEmpty())
    }

    @Test
    fun importedPdfBlobIsUploadedAndDownloaded() {
        val doc = a.store.importPdf(ByteArrayInputStream(PDF), "Paper", null)
        a.sync()
        b.sync()

        val blob = doc.pages[0].pdf!!.blob
        assertTrue(PDF.contentEquals(b.store.blobFile(doc.id, blob).readBytes()))
    }

    @Test
    fun changeOnOneSideAppliesQuietly() {
        val doc = note(a)
        a.sync()
        b.sync()

        b.store.rename(doc.id, "Better ideas")
        b.store.writePages(doc.id, mapOf(doc.pages[0].id to content("changed")))
        b.sync()
        a.sync()

        assertEquals("Better ideas", a.library.document(doc.id)?.title)
        assertEquals(content("changed"), a.store.readPage(doc.id, doc.pages[0].id))
        assertTrue(a.engine.conflicts.value.isEmpty())
    }

    @Test
    fun syncingAgainWithNothingNewWritesNothing() {
        note(a)
        a.sync()
        b.sync()
        val writes = server.writes

        a.store.markOpened(a.library.documents[0].id)
        a.sync()
        b.sync()
        a.engine = SyncEngine(a.store, server, a.syncDir)
        a.sync()

        assertEquals(writes, server.writes)
    }

    private fun conflicted(): String {
        val doc = note(a)
        a.sync()
        b.sync()
        a.store.rename(doc.id, "From A")
        a.sync()
        b.store.rename(doc.id, "From B")
        b.sync()
        assertEquals(listOf(doc.id), b.engine.conflicts.value.map { it.documentId })
        return doc.id
    }

    @Test
    fun keepingMineOverwritesTheServer() {
        val id = conflicted()
        runBlocking { b.engine.resolve(id, Resolution.KEEP_MINE) { "$it (copy)" } }
        b.sync()
        a.sync()

        assertEquals("From B", a.library.document(id)?.title)
        assertTrue(b.engine.conflicts.value.isEmpty())
    }

    @Test
    fun keepingTheirsTakesTheServerCopy() {
        val id = conflicted()
        runBlocking { b.engine.resolve(id, Resolution.KEEP_THEIRS) { "$it (copy)" } }
        b.sync()

        assertEquals("From A", b.library.document(id)?.title)
        assertEquals(1, b.library.documents.size)
    }

    @Test
    fun keepingBothSavesTheServerCopyAsANewDocument() {
        val id = conflicted()
        runBlocking { b.engine.resolve(id, Resolution.KEEP_BOTH) { "$it (conflicted copy)" } }
        b.sync()
        a.sync()

        val titles = listOf("From A (conflicted copy)", "From B")
        assertEquals(titles, b.library.documents.map { it.title }.sorted())
        assertEquals(titles, a.library.documents.map { it.title }.sorted())
        val copy = a.library.documents.first { it.id != id }
        assertEquals(content("hello"), a.store.readPage(copy.id, copy.pages[0].id))
    }

    @Test
    fun deletionsReachTheOtherDeviceUnlessItChangedTheDocument() {
        val gone = note(a, "Gone")
        val kept = note(a, "Kept")
        a.sync()
        b.sync()

        a.store.deletePermanently(gone.id)
        a.store.deletePermanently(kept.id)
        a.sync()
        b.store.rename(kept.id, "Still wanted")
        b.sync()
        a.sync()

        assertNull(b.library.document(gone.id))
        assertEquals("Still wanted", b.library.document(kept.id)?.title)
        assertEquals("Still wanted", a.library.document(kept.id)?.title)
    }

    @Test
    fun foldersSyncAndDeletingOneTakesItsDocuments() {
        val folder = a.store.createFolder("Math", null)
        val doc = a.store.createNote("Algebra", folder.id, TemplateChoice(), cover = null)
        a.sync()
        b.sync()
        assertEquals("Math", b.library.folder(folder.id)?.name)
        assertEquals(folder.id, b.library.document(doc.id)?.folderId)

        a.store.deletePermanently(folder.id)
        a.sync()
        b.sync()

        assertNull(b.library.folder(folder.id))
        assertNull(b.library.document(doc.id))
    }

    @Test
    fun firstSyncOfAnIdenticalDocumentIsNotAConflict() {
        val doc = a.store.importPdf(ByteArrayInputStream(PDF), "Paper", null)
        a.sync()
        b.sync()
        b.engine = SyncEngine(b.store, server, tmp.newFolder("b-fresh"))
        b.sync()

        assertTrue(b.engine.conflicts.value.isEmpty())
        assertNotNull(b.library.document(doc.id))
    }

    /** The server's item rules, in memory. */
    private class FakeServer : SyncService {
        class Row(val revision: Long, val seq: Long, val deleted: Boolean, val body: JsonElement)

        val blobs = mutableMapOf<String, ByteArray>()
        val items = mutableMapOf<String, Row>()
        var seq = 0L
        var writes = 0

        override suspend fun changes(since: Long): Changes {
            val rows = items.entries.filter { it.value.seq > since }.sortedBy { it.value.seq }
            return Changes(rows.map { item(it.key, it.value) }, rows.lastOrNull()?.value?.seq ?: since)
        }

        override suspend fun missingBlobs(hashes: Collection<String>) = hashes.filter { it !in blobs }.distinct()

        override suspend fun putBlob(sha256: String, file: File) {
            val bytes = file.readBytes()
            if (sha(bytes) != sha256) throw ApiException(400, "hash_mismatch")
            blobs[sha256] = bytes
        }

        override suspend fun getBlob(sha256: String, into: File) = into.writeBytes(blobs[sha256] ?: throw ApiException(404, "not_found"))

        override suspend fun putItem(kind: String, id: String, baseRevision: Long, body: JsonElement) = write(kind, id, baseRevision, body)

        override suspend fun deleteItem(kind: String, id: String, baseRevision: Long) = write(kind, id, baseRevision, null)

        private fun write(kind: String, id: String, base: Long, body: JsonElement?): Long {
            val key = "$kind:$id"
            val current = items[key]
            val revision = current?.revision ?: 0
            if (revision != base) {
                val item = current?.let { item(key, it) } ?: RemoteItem(kind, id, 0, 0, true)
                throw ApiException(409, "conflict", buildJsonObject { put("item", syncJson.encodeToJsonElement(item)) })
            }
            if (body == null && current == null) throw ApiException(404, "not_found")
            val used = (body?.jsonObject?.get("blobs") as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            if (used.any { it !in blobs }) throw ApiException(400, "missing_blobs")
            seq++
            writes++
            items[key] = Row(revision + 1, seq, body == null, body ?: JsonNull)
            return revision + 1
        }

        private fun item(key: String, row: Row) =
            RemoteItem(key.substringBefore(':'), key.substringAfter(':'), row.revision, row.seq, row.deleted, row.body)
    }

    private companion object {
        val PDF = "%PDF-1.4 pretend".toByteArray()

        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
