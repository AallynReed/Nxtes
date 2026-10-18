package net.mildhope.nxtes.flexcil

import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.data.MarkKind
import net.mildhope.nxtes.data.MaskKind
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.data.Picture
import net.mildhope.nxtes.data.Shape
import net.mildhope.nxtes.data.ShapeKind
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.ink.ToolState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FlexcilImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LibraryStore
    private lateinit var importer: FlexcilImporter

    @Before
    fun setUp() {
        store = LibraryStore(tmp.newFolder("library"), { error("no PDF inspection") }).apply { load() }
        importer = FlexcilImporter(store, File(tmp.root, "work"), clock = { 5_000L })
    }

    private fun file(bytes: ByteArray) = tmp.newFile().apply { writeBytes(bytes) }

    @Test
    fun backupBecomesFoldersAndDocuments() {
        val result = importer.import(file(backup()), folderId = null)

        assertEquals(FlexcilImporter.Result(documents = 2, failed = 1), result)
        val library = store.state.value
        val school = library.folders.single { it.name == "School/Work" }
        assertNull(school.parentId)
        assertTrue(school.favorite)
        assertEquals(0xFF30D158.toInt(), school.color)
        assertEquals(1_600_000_000_000L, school.created)
        val math = library.folders.single { it.name == "Math" }
        assertEquals(school.id, math.parentId)

        val lecture = library.documents.single { it.title == "Lecture <1>" }
        assertEquals(math.id, lecture.folderId)
        assertTrue(lecture.note)
        assertTrue(lecture.favorite)
        assertEquals(1_700_000_000_000L, lecture.created)
        val pdf = sha(PDF)
        assertEquals(listOf(600f to 800f, 800f to 600f, 600f to 800f), lecture.pages.map { it.width to it.height })
        assertEquals(listOf(pdf, pdf, null), lecture.pages.map { it.pdf?.blob })
        assertEquals(listOf(0, 1), lecture.pages.mapNotNull { it.pdf?.index })
        assertEquals(listOf(0, 1, 0), lecture.pages.map { it.turns })
        assertEquals(listOf(false, true, true), lecture.pages.map { it.pdfAnnotations })
        assertTrue(store.blobFile(lecture.id, pdf).exists())
        assertEquals(listOf("Start", "Second"), lecture.bookmarks.map { it.title })
        assertEquals(lecture.pages[1].id, lecture.bookmarks[1].pageId)
        assertEquals(listOf("Intro", "Part"), lecture.outline.map { it.title })

        val recording = lecture.recordings.single()
        assertEquals("Lecture audio", recording.title)
        assertEquals(1_700_000_000_000L, recording.started)
        assertEquals(12_500L, recording.duration)
        assertTrue(store.blobFile(lecture.id, recording.blob).exists())

        val loose = library.documents.single { it.title == "Paper" }
        assertNull(loose.folderId)
        assertTrue(loose.outline.isEmpty())
    }

    @Test
    fun pageObjectsAreScaledToPagePoints() {
        importer.import(file(backup()), folderId = null)
        val lecture = store.state.value.documents.single { it.title == "Lecture <1>" }
        val page = store.readPage(lecture.id, lecture.pages[0].id)

        val ink = page.strokes.single { it.pen == Pen.BALLPOINT }
        assertEquals(150f, ink.x[0], 0.01f)
        assertEquals(240f, ink.y[0], 0.01f)
        assertEquals(156f, ink.x[1], 0.01f)
        assertEquals(1.8f, ink.size, 0.01f)
        assertEquals(0xFF000000.toInt(), ink.color)
        assertEquals(1_700_000_005_000L, ink.created)
        assertNull(ink.pressure)
        assertArrayEquals(intArrayOf(0, 10), ink.t)
        val fountain = page.strokes.single { it.pen == Pen.FOUNTAIN }
        assertEquals(2.4f, fountain.size, 0.01f)
        assertArrayEquals(floatArrayOf(0.5f, 1f), fountain.pressure, 0.01f)
        val straightHighlight = page.strokes.single { it.pen == Pen.HIGHLIGHTER }
        assertArrayEquals(floatArrayOf(60f, 180f), straightHighlight.x, 0.01f)
        assertEquals(0x77FFED2A, straightHighlight.color)

        val objects = page.objects
        assertTrue("the .objects order comes first", objects[0] is Picture && objects[1] is TextBox)
        val picture = objects[0] as Picture
        assertEquals(360f, picture.cx, 0.01f)
        assertEquals(330f, picture.cy, 0.01f)
        assertEquals(120f, picture.width, 0.01f)
        assertEquals(90f, picture.rotation, 0.01f)
        assertEquals(0.1f, picture.cropLeft, 0.001f)
        assertEquals(0.9f, picture.cropRight, 0.001f)
        assertTrue(store.blobFile(lecture.id, picture.blob).exists())
        val text = objects[1] as TextBox
        assertEquals("Hello", text.text)
        assertEquals(listOf(60f, 60f, 300f, 60f), listOf(text.x, text.y, text.width, text.height))
        assertEquals("serif", text.font)
        assertEquals(30f, text.size, 0.01f)
        assertEquals(0xFFFF0000.toInt(), text.color)
        assertTrue(text.bold && text.italic && text.underline)

        val shapes = objects.filterIsInstance<Shape>()
        assertEquals(listOf(ShapeKind.RECTANGLE, ShapeKind.ARROW, ShapeKind.CURVE), shapes.map { it.kind })
        assertArrayEquals(floatArrayOf(60f, 180f), shapes[1].xs, 0.01f)
        assertEquals(360f, shapes[2].ys[1], 0.01f)

        val mark = page.marks.single()
        assertEquals(MarkKind.HIGHLIGHT, mark.kind)
        assertArrayEquals(floatArrayOf(60f, 174f, 180f, 12f), mark.rects, 0.01f)
        assertEquals("word", mark.text)
        val note = page.notes.single()
        assertEquals(540f to 30f, note.x to note.y)
        assertEquals(ToolState.NOTE_COLORS[2], note.color)
        val mask = page.masks.single()
        assertEquals(MaskKind.TAPE, mask.kind)
        assertEquals(3, mask.tries)

        val turned = store.readPage(lecture.id, lecture.pages[1].id).strokes.single()
        assertEquals(400f, turned.x[0], 0.01f)
        val fromBackup: InkStroke = store.readPage(lecture.id, lecture.pages[2].id).strokes.single()
        assertEquals(60f, fromBackup.x[0], 0.01f)
    }

    @Test
    fun hyperlinksBecomeLinksToTheImportedPages() {
        importer.import(file(backup()), folderId = null)
        val lecture = store.state.value.documents.single { it.title == "Lecture <1>" }
        val links = store.readPage(lecture.id, lecture.pages[0].id).links

        assertEquals("only page and URL links whose targets exist", 2, links.size)
        val (web, jump) = links
        assertEquals("http://www.example.org", web.url)
        assertNull(web.pageId)
        assertArrayEquals(floatArrayOf(60f, 120f, 180f, 30f), floatArrayOf(web.x, web.y, web.width, web.height), 0.01f)
        assertEquals(lecture.pages[2].id, jump.pageId)
        assertEquals(lecture.pages[0].id, store.readPage(lecture.id, lecture.pages[2].id).links.single().pageId)
    }

    @Test
    fun singleDocumentImports() {
        val result = importer.import(file(lecture()), folderId = null)

        assertEquals(1, result.documents)
        assertNotNull(store.state.value.documents.single { it.title == "Lecture <1>" })
    }

    @Test(expected = FlexcilImporter.NotFlexcil::class)
    fun otherFilesAreRefused() {
        importer.import(file("not a zip".toByteArray()), folderId = null)
    }

    @Test
    fun arraysWrittenTwiceAreMerged() {
        val text = """[{"key":"A","type":1}][{"key":"A","type":1},{"key":"B","type":2}]"""
        assertEquals(listOf("A", "B"), decodeArray(text, ObjectRef.serializer()).map { it.key })
    }

    private fun lecture(slashed: Boolean = false): ByteArray = zip(
        slashed,
        "info" to """{"key":"DOC1","name":"Lecture <1>","type":4,"createDate":1.7E9,"modifiedDate":1.7E9,"attachments":{"PDF1":""},"version":"0.0.5"}""",
        ".itemInfo" to envelope("""{"originKey":"F1","favorite":true}"""),
        "pages.index" to """[
            {"key":"P1","frame":{"x":0.0,"y":0.0,"width":600.0,"height":800.0},"rotate":0.0,"attachmentPage":{"file":"PDF1","index":0},"annotationSubtypes":[1,2,3,9,15]},
            {"key":"P2","frame":{"x":0.0,"y":0.0,"width":600.0,"height":800.0},"rotate":90.0,"attachmentPage":{"file":"PDF1","index":1},"annotationSubtypes":[2]},
            {"key":"P3","frame":{"x":0.0,"y":0.0,"width":600.0,"height":800.0},"rotate":0.0}]""",
        "attachment/PDF/PDF1" to PDF,
        "attachment/image/IMG1" to "pixels".toByteArray(),
        "objects/P1.objects" to """[{"type":20,"key":"IMG1"},{"type":30,"key":"TXT1"},{"type":1,"key":"D1"}]""",
        "objects/P1.drawings" to """[
            {"key":"D1","type":1,"mode":1,"figure":0,"strokeColor":4278190080,"points":"${strokes(0f, 0f, 0.003f, 0.01f, 0.005f, 0.0032f)}","start":{"x":0.25,"y":0.4},"scale":{"x":1.0,"y":1.0},"rotate":0.0},
            {"key":"D2","type":1,"mode":5,"figure":0,"strokeColor":4278190335,"points":"${strokes(0f, 0f, 0.002f, 0.1f, 0f, 0.004f)}","start":{"x":0.1,"y":0.1}},
            {"key":"D3","type":1,"mode":1,"figure":2,"strokeColor":4278190080,"points":"${strokes(0f, 0f, 0.002f, 0.1f, 0.1f, 0.002f)}","start":{"x":0.5,"y":0.8}},
            {"key":"D6","type":2,"mode":2,"figure":1,"strokeColor":2013261098,"points":"${strokes(0f, 0f, 0.005f, 0.2f, 0f, 0.005f)}","start":{"x":0.1,"y":0.3}}]""",
        "objects/P1.texts" to """[{"key":"TXT1","type":30,"frame":{"x":0.1,"y":0.1,"width":0.5,"height":0.1},"rotate":0.0,"text":"Hello",
            "columns":[{"p":{"span":[{"text":"Hello","style":{"font-size":0.05,"font-family":"Times New Roman","font-weight":"bold","font-style":"italic underline","color":"#FF0000"}}],"reflow":[1],"offset":1}}]}]""",
        "objects/P1.images" to """[{"key":"IMG1","type":20,"frame":{"x":0.5,"y":0.5,"width":0.2,"height":0.1},"cropBox":{"x":0.1,"y":0.0,"width":0.8,"height":1.0},"rotate":1.5707963267948966}]""",
        "objects/P1.shapes" to """[
            {"key":"S1","type":32,"shapeType":7,"points":"${strokes(0f, 0f, 0.002f, 0.2f, 0.1f, 0.002f)}","start":{"x":0.1,"y":0.6},"scale":{"x":1.0,"y":1.0},"controlPoints":[],"rotate":0.0},
            {"key":"S2","type":32,"shapeType":6,"points":"${strokes(0f, 0f, 0.002f, 0.2f, 0f, 0.002f)}","start":{"x":0.1,"y":0.7},"controlPoints":[{"x":0.2,"y":0.5}],"rotate":0.0}]""",
        "objects/P1.annotations" to """[{"key":"A1","type":11,"mode":2,"strokeColor":2164260608,"text":"word","paths":[{"points":"${lines(0.1f, 0.3f, 0.4f, 0.3f)}","width":0.02}]}]""",
        "objects/P1.stickynotes" to """[{"key":"N1","type":60,"frame":{"x":0.9,"y":0.05,"width":0.04,"height":0.04},"text":"Remember","color":2}]""",
        "objects/P1.maskings" to """[{"key":"M1","type":50,"mode":7,"figure":2,"patternType":0,"points":"${strokes(0f, 0f, 0.01f, 0.2f, 0.05f, 0.01f)}","start":{"x":0.1,"y":0.9},"strokeColor":4294951936,"correct":2,"wrong":1}]""",
        "objects/P1.hyperlinks" to """[
            {"key":"H1","version":"0.0.5","type":40,"frame":{"x":0.1,"y":0.2,"width":0.3,"height":0.05},"actionType":3,"link":"www.example.org"},
            {"key":"H2","type":40,"frame":{"x":0.5,"y":0.2,"width":0.1,"height":0.05},"actionType":1,"link":"P3"},
            {"key":"H3","type":40,"frame":{"x":0.5,"y":0.3,"width":0.1,"height":0.05},"actionType":1,"link":"GONE"},
            {"key":"H4","type":40,"frame":{"x":0.5,"y":0.4,"width":0.1,"height":0.05},"actionType":4,"link":"tool.exe"}]""",
        "objects/P3.hyperlinks" to """[{"key":"H5","type":40,"frame":{"x":0.0,"y":0.0,"width":0.1,"height":0.1},"actionType":1,"link":"P1"}]""",
        "objects/P2.drawings" to """[{"key":"D4","type":1,"mode":1,"points":"${strokes(0f, 0f, 0.002f)}","start":{"x":0.5,"y":0.5}}]""",
        "objects/P3.drawings" to "",
        "objects/P3.drawings_back" to """[{"key":"D5","type":1,"mode":1,"points":"${strokes(0f, 0f, 0.002f)}","start":{"x":0.1,"y":0.1}}]""",
        "bookmark" to """[{"name":"Start","type":0,"pageKey":"P1"},{"name":"Group","type":1,"children":[{"name":"Second","type":0,"pageKey":"P2"}]}]""",
        "outlines" to """[{"name":"Intro","link":"P1","actionType":1,"children":[{"name":"Part","link":"P2","actionType":1}]}]""",
        "audiorecord.refs" to """{"refs":["AUD1"]}""",
    )

    private fun paper(): ByteArray = zip(
        "info" to """{"key":"DOC2","name":"Paper","type":0,"createDate":1.6E9,"modifiedDate":1.6E9}""",
        "pages.index" to """[{"key":"Q1","frame":{"width":612.0,"height":792.0},"rotate":0.0,"attachmentPage":{"file":"PDF2","index":0}}]""",
        "attachment/PDF/PDF2" to PDF,
        "outlines" to """[{"name":"From the PDF","link":"Q1"}]""",
    )

    private fun recording(): ByteArray = zip(
        "AUD1.flxa" to "aac audio".toByteArray(),
        "AUD1.rinfo" to """{"key":"AUD1","name":"Lecture audio","startdoc":"DOC1","start":1.7E9,"duration":12.5,"includeDocuments":[{"dockey":"DOC1","pages":["P1"]}]}""",
        "AUD1.rsync" to """{"items":[{"addtime":1.700000005E9,"dockey":"DOC1","pagekey":"P1","obj":"D1"}]}""",
    )

    private fun backup(): ByteArray = zip(
        true,
        "flexcilbackup/info" to """{"appName":"FlexcilNote_Android","backupDate":"2026-10-18 10.00.00","appVersion":"1.5.0.13","version":"4.0.0"}""",
        "flexcilbackup/Documents/School_Work/.itemInfo" to envelope("""{"originKey":"F1","name":"School/Work","color":"Green","createDate":1.6E9,"modifiedDate":1.6E9,"favorite":true}"""),
        "flexcilbackup/Documents/School_Work/Math/.itemInfo" to envelope("""{"originKey":"F2","name":"Math"}"""),
        "flexcilbackup/Documents/School_Work/Math/Lecture _1_.flx" to lecture(slashed = true),
        "flexcilbackup/Documents/Paper.flx" to paper(),
        "flexcilbackup/Documents/Broken.flx" to "not a document".toByteArray(),
        "flexcilbackup/Recordings/AUD1.fab" to recording(),
        "flexcilbackup/.Reference/" to ByteArray(0),
    )

    private companion object {
        val PDF = "%PDF-1.4 pretend".toByteArray()

        fun zip(vararg entries: Pair<String, Any>): ByteArray = zip(false, *entries)

        /** A ZIP; [slashed] starts file names with a slash, as some Flexcil versions write them. */
        fun zip(slashed: Boolean, vararg entries: Pair<String, Any>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.setLevel(0)
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(if (slashed && !name.endsWith("/")) "/$name" else name))
                    zip.write(if (content is ByteArray) content else (content as String).toByteArray())
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }

        fun envelope(json: String): ByteArray {
            val raw = json.toByteArray()
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true).apply { setInput(raw); finish() }
            val packed = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) packed.write(buffer, 0, deflater.deflate(buffer))
            return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(raw.size.toLong()).array() + packed.toByteArray()
        }

        fun blob(perPoint: Int, values: FloatArray): String {
            val buffer = ByteBuffer.allocate(4 + values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(values.size / perPoint)
            values.forEach { buffer.putFloat(it) }
            return Base64.getEncoder().encodeToString(buffer.array())
        }

        fun strokes(vararg xyw: Float) = blob(3, xyw)

        fun lines(vararg xy: Float) = blob(2, xy)

        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
