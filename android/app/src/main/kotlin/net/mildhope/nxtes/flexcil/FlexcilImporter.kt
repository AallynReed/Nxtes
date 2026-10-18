package net.mildhope.nxtes.flexcil

import kotlinx.serialization.KSerializer
import net.mildhope.nxtes.data.AudioSpan
import net.mildhope.nxtes.data.Bookmark
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.Folder
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.InputTool
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.data.LineStyle
import net.mildhope.nxtes.data.Link
import net.mildhope.nxtes.data.MarkKind
import net.mildhope.nxtes.data.Mask
import net.mildhope.nxtes.data.MaskKind
import net.mildhope.nxtes.data.OutlineItem
import net.mildhope.nxtes.data.Page
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.PageObject
import net.mildhope.nxtes.data.PdfSource
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.data.Picture
import net.mildhope.nxtes.data.Recording
import net.mildhope.nxtes.data.Shape
import net.mildhope.nxtes.data.ShapeKind
import net.mildhope.nxtes.data.StickyNote
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.data.TextMark
import net.mildhope.nxtes.ink.ToolState
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns a Flexcil backup (`.flex`) or a single Flexcil document (`.flx`) into Nxtes folders and
 * documents under [folderId]. Calls block on file IO.
 */
class FlexcilImporter(
    private val store: LibraryStore,
    private val workDir: File,
    private val recordingTitle: String = "Recording",
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val onFailure: (entry: String, error: Throwable) -> Unit = { _, _ -> },
) {
    data class Result(val documents: Int, val failed: Int)

    class NotFlexcil : IllegalArgumentException("Not a Flexcil backup")

    private class Audio(val title: String?, val file: File, val start: Long, val length: Long)

    fun import(file: File, folderId: String?): Result {
        workDir.deleteRecursively()
        workDir.mkdirs()
        try {
            val zip = runCatching { Archive(ZipFile(file)) }.getOrElse { throw NotFlexcil() }
            return zip.use { importFrom(it, file.nameWithoutExtension, folderId) }
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun importFrom(zip: Archive, name: String, folderId: String?): Result {
        val names = zip.names.toList()
        if ("pages.index" in zip.names || "pages.index_back" in zip.names) {
            importDocument(zip, name, folderId, emptyMap(), emptyMap())
            return Result(1, 0)
        }
        val root = names.firstOrNull { (it == "info" || it.endsWith("/info")) && names.any { n -> n.startsWith(it.removeSuffix("info") + "Documents/") } }
            ?.removeSuffix("info") ?: throw NotFlexcil()
        val documentsPrefix = "${root}Documents/"

        val recordings = mutableMapOf<String, Audio>()
        val times = mutableMapOf<String, Long>()
        names.filter { it.startsWith("${root}Recordings/") && it.endsWith(".fab") }.forEach { entry ->
            runCatching { readRecording(zip, entry, recordings, times) }
        }

        val folders = mutableMapOf("" to folderId)
        fun folderFor(dir: String): String? {
            folders[dir]?.let { return it }
            if (dir in folders) return null
            val parent = folderFor(dir.removeSuffix("/").substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" })
            val info = zip.bytes("$documentsPrefix$dir.itemInfo")?.let { runCatching { flexJson.decodeFromString(FolderInfo.serializer(), inflateEnvelope(it)) }.getOrNull() }
            val modified = info?.modifiedDate.ms() ?: clock()
            val folder = Folder(
                id = newId(),
                name = info?.name?.takeIf { it.isNotBlank() } ?: dir.removeSuffix("/").substringAfterLast('/'),
                parentId = parent,
                created = info?.createDate.ms() ?: modified,
                modified = modified,
                favorite = info?.favorite ?: false,
                color = info?.color?.lowercase()?.takeIf { it != "blue" }?.let(FOLDER_COLORS::get),
            )
            store.addFolder(folder)
            folders[dir] = folder.id
            return folder.id
        }

        var imported = 0
        var failed = 0
        names.filter { it.startsWith(documentsPrefix) && it.endsWith(".flx") }.sorted().forEach { entry ->
            val relative = entry.removePrefix(documentsPrefix)
            val dir = if ('/' in relative) relative.substringBeforeLast('/') + "/" else ""
            runCatching {
                val flx = extract(zip, entry)
                Archive(ZipFile(flx)).use { importDocument(it, relative.substringAfterLast('/').removeSuffix(".flx"), folderFor(dir), recordings, times) }
                flx.delete()
            }.onSuccess { imported++ }.onFailure {
                failed++
                onFailure(entry, it)
            }
        }
        return Result(imported, failed)
    }

    private fun readRecording(zip: Archive, entry: String, into: MutableMap<String, Audio>, times: MutableMap<String, Long>) {
        val key = entry.substringAfterLast('/').removeSuffix(".fab")
        val fab = extract(zip, entry)
        Archive(ZipFile(fab)).use { inner ->
            val names = inner.names.toList()
            val audio = names.firstOrNull { it.endsWith(".flxa") } ?: names.firstOrNull { it.endsWith(".flxa_back") } ?: return
            val info = names.firstOrNull { it.endsWith(".rinfo") }?.let { inner.decode(it, RecordingInfo.serializer()) } ?: return
            names.firstOrNull { it.endsWith(".rsync") }?.let { inner.decode(it, SyncList.serializer()) }?.items?.forEach {
                if (it.obj.isNotEmpty()) times[it.obj] = (it.addtime * 1000).toLong()
            }
            into[key] = Audio(info.name, extract(inner, audio), (info.start * 1000).toLong(), (info.duration * 1000).toLong())
        }
    }

    private fun importDocument(zip: Archive, fallbackTitle: String, folderId: String?, recordings: Map<String, Audio>, times: Map<String, Long>) {
        val info = zip.decode("info", DocumentInfo.serializer()) ?: DocumentInfo()
        val favorite = zip.bytes(".itemInfo")?.let { runCatching { flexJson.decodeFromString(DocumentItemInfo.serializer(), inflateEnvelope(it)).favorite }.getOrNull() } ?: false
        val entries = zip.list("pages.index", PageEntry.serializer())
        require(entries.isNotEmpty()) { "The document has no pages" }
        val names = zip.names.toList()
        val blobs = mutableMapOf<String, File>()
        fun keep(file: File) = sha256(file).also { blobs[it] = file }
        fun attachments(prefix: String) = names.filter { it.startsWith(prefix) && !it.endsWith("/") && !it.endsWith("_back") }
            .associate { it.removePrefix(prefix) to keep(extract(zip, it)) }
        val pdfs = attachments("attachment/PDF/")
        val images = attachments("attachment/image/")
        val modified = info.modifiedDate.ms() ?: clock()

        val pageIds = mutableMapOf<String, String>()
        val pages = mutableListOf<Page>()
        val contents = mutableMapOf<String, PageContent>()
        val links = mutableMapOf<String, List<Link>>()
        for (entry in entries) {
            val turns = ((entry.rotate / 90f).roundToInt() % 4 + 4) % 4
            val frameWidth = entry.frame.width.takeIf { it > 0 } ?: DEFAULT_WIDTH
            val frameHeight = entry.frame.height.takeIf { it > 0 } ?: DEFAULT_HEIGHT
            val (width, height) = if (turns % 2 == 1) frameHeight to frameWidth else frameWidth to frameHeight
            val pdf = entry.attachmentPage?.let { source -> pdfs[source.file]?.let { PdfSource(it, source.index) } }
            val hidden = entry.annotationSubtypes.ifEmpty { listOf(PDF_LINK) }
            val page = Page(newId(), width, height, pdf, turns = if (pdf != null) turns else 0, pdfAnnotations = hidden.all { it == PDF_LINK })
            pageIds[entry.key] = page.id
            pages += page
            val content = PageConverter(width, images, times, modified).convert(zip, entry.key)
            if (!content.isEmpty) contents[page.id] = content
            links[page.id] = zip.list("objects/${entry.key}.hyperlinks", HyperlinkEntry.serializer()).mapNotNull { link(it, width) }
        }
        links.forEach { (pageId, list) ->
            val resolved = list.mapNotNull { if (it.pageId == null) it else pageIds[it.pageId]?.let { id -> it.copy(pageId = id) } }
            if (resolved.isNotEmpty()) contents[pageId] = (contents[pageId] ?: PageContent.EMPTY).copy(links = resolved)
        }

        val audio = zip.decode("audiorecord.refs", AudioRefs.serializer())?.refs.orEmpty().mapNotNull { recordings[it] }
        val bookmarks = mutableListOf<Bookmark>()
        fun addBookmarks(list: List<BookmarkEntry>) {
            list.forEach { mark ->
                if (mark.type == 1) addBookmarks(mark.children) else mark.pageKey?.let(pageIds::get)?.let { bookmarks += Bookmark(newId(), it, mark.name) }
            }
        }
        addBookmarks(zip.list("bookmark", BookmarkEntry.serializer()))
        val outline = mutableListOf<OutlineItem>()
        fun addOutline(list: List<OutlineEntry>) {
            list.forEach { item ->
                item.link?.let(pageIds::get)?.let { outline += OutlineItem(newId(), it, item.name) }
                addOutline(item.children)
            }
        }
        if (info.type != TYPE_PDF) addOutline(zip.list("outlines", OutlineEntry.serializer()))

        val doc = Document(
            id = newId(),
            title = info.name?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            folderId = folderId,
            created = info.createDate.ms() ?: modified,
            modified = modified,
            favorite = favorite,
            note = info.type == TYPE_NOTE || info.type == TYPE_PLANNER,
            bookmarks = bookmarks,
            outline = outline,
            pages = pages,
            recordings = audio.map { Recording(newId(), it.title?.takeIf { t -> t.isNotBlank() } ?: recordingTitle, keep(it.file), listOf(AudioSpan(it.start, it.length))) },
        )
        store.addDocument(doc, blobs, contents)
    }

    /** A page or URL link, its page target still a Flexcil page key; null for other kinds. */
    private fun link(h: HyperlinkEntry, width: Float): Link? {
        val target = h.link?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val f = h.frame
        if (f.width <= 0 || f.height <= 0 || (h.actionType != LINK_PAGE && h.actionType != LINK_URL)) return null
        return Link(
            newId(), f.x * width, f.y * width, f.width * width, f.height * width,
            pageId = target.takeIf { h.actionType == LINK_PAGE },
            url = target.takeIf { h.actionType == LINK_URL }?.let { if (it.startsWith("www.", ignoreCase = true)) "http://$it" else it },
        )
    }

    /** One page's objects, from fractions of the page width to page points [width] wide. */
    private inner class PageConverter(
        private val width: Float,
        private val images: Map<String, String>,
        private val times: Map<String, Long>,
        private val fallbackTime: Long,
    ) {
        private var order = 0

        private fun timeOf(key: String) = times[key] ?: (fallbackTime + order++)

        fun convert(zip: Archive, pageKey: String): PageContent {
            val base = "objects/$pageKey"
            val items = LinkedHashMap<String, Any>()
            fun put(key: String, item: Any?) {
                if (item != null) items[key.ifEmpty { newId() }] = item
            }
            zip.list("$base.drawings", DrawingEntry.serializer()).forEach { put(it.key, drawing(it)) }
            zip.list("$base.shapes", ShapeEntry.serializer()).forEach { put(it.key, shape(it)) }
            zip.list("$base.annotations", AnnotationEntry.serializer()).forEach { put(it.key, mark(it)) }
            zip.list("$base.maskings", MaskingEntry.serializer()).forEach { put(it.key, masking(it)) }
            zip.list("$base.maskingAnnotations", MaskingAnnotationEntry.serializer()).forEach { put(it.key, textMasks(it)) }
            zip.list("$base.images", ImageEntry.serializer()).forEach { put(it.key, picture(it)) }
            zip.list("$base.texts", TextEntry.serializer()).forEach { put(it.key, textBox(it)) }
            zip.list("$base.stickynotes", StickyEntry.serializer()).forEach { put(it.key, note(it)) }
            val listed = zip.list("$base.objects", ObjectRef.serializer()).map { it.key }
            val ordered = listed.mapNotNull { items[it] } + items.filterKeys { it !in listed.toSet() }.values
            val all = ordered.flatMap { if (it is List<*>) it.filterNotNull() else listOf(it) }
            return PageContent(
                strokes = all.filterIsInstance<InkStroke>(),
                objects = all.filterIsInstance<PageObject>(),
                notes = all.filterIsInstance<StickyNote>(),
                masks = all.filterIsInstance<Mask>(),
                marks = all.filterIsInstance<TextMark>(),
            )
        }

        private fun points(blob: String, start: FPoint, scale: FPoint): Triple<FloatArray, FloatArray, FloatArray>? {
            val p = decodeStrokePoints(blob)
            val n = p.size / 3
            if (n == 0) return null
            return Triple(
                FloatArray(n) { (p[3 * it] * scale.x + start.x) * width },
                FloatArray(n) { (p[3 * it + 1] * scale.y + start.y) * width },
                FloatArray(n) { p[3 * it + 2] * width },
            )
        }

        private fun drawing(d: DrawingEntry): Any? {
            val (xs, ys, ws) = points(d.points, d.start, d.scale) ?: return null
            val color = d.strokeColor.toInt()
            val line = LineStyle.fromCode(d.dashtype)
            val stroke = ws[0].coerceAtLeast(MIN_WIDTH)
            val fill = d.fillColor.toInt()
            return when (d.figure) {
                FIGURE_LINE -> Shape(newId(), ShapeKind.LINE, floatArrayOf(xs.first(), xs.last()), floatArrayOf(ys.first(), ys.last()), color, stroke, 0, line)
                FIGURE_RECTANGLE -> corners(xs, ys).let { (x, y) -> Shape(newId(), ShapeKind.RECTANGLE, x, y, color, stroke, fill, line) }
                FIGURE_ELLIPSE -> corners(xs, ys).let { (x, y) -> Shape(newId(), ShapeKind.ELLIPSE, x, y, color, stroke, fill, line) }
                FIGURE_RHOMBUS, FIGURE_TRIANGLE, FIGURE_POLYGON -> {
                    val (x, y) = open(xs, ys)
                    val kind = if (x.size == 3) ShapeKind.TRIANGLE else ShapeKind.POLYGON
                    if (x.size >= 3) Shape(newId(), kind, x, y, color, stroke, fill, line) else ink(penOf(d), color, xs, ys, ws, timeOf(d.key))
                }
                else -> ink(penOf(d), color, xs, ys, ws, timeOf(d.key))
            }
        }

        private fun penOf(d: DrawingEntry) = when (d.mode) {
            MODE_HIGHLIGHTER, MODE_SQUARE_HIGHLIGHTER -> Pen.HIGHLIGHTER
            MODE_FOUNTAIN, MODE_SLANTED_FOUNTAIN, MODE_ROUND_FOUNTAIN -> Pen.FOUNTAIN
            MODE_PENCIL, MODE_TEXTURE_PENCIL -> Pen.PENCIL
            else -> if (d.type == TYPE_HIGHLIGHTER_INK) Pen.HIGHLIGHTER else Pen.BALLPOINT
        }

        private fun ink(pen: Pen, color: Int, xs: FloatArray, ys: FloatArray, ws: FloatArray, created: Long): InkStroke {
            val pressured = pen == Pen.FOUNTAIN || pen == Pen.PENCIL
            val widest = ws.max().coerceAtLeast(MIN_WIDTH)
            return InkStroke(
                id = newId(),
                pen = pen,
                color = color,
                size = if (pressured) widest else ws[0].coerceAtLeast(MIN_WIDTH),
                tool = InputTool.STYLUS,
                noiseSeed = created.hashCode(),
                created = created,
                x = xs,
                y = ys,
                t = IntArray(xs.size) { it * POINT_MS },
                pressure = if (pressured) FloatArray(ws.size) { (ws[it] / widest).coerceIn(0.05f, 1f) } else null,
            )
        }

        private fun shape(s: ShapeEntry): Any? {
            val (xs, ys, ws) = points(s.points, s.start, s.scale) ?: return null
            val controls = s.controlPoints.map { it.x * width to it.y * width }
            val stroke = ws[0].coerceAtLeast(MIN_WIDTH)
            val color = s.strokeColor.toInt()
            val line = LineStyle.fromCode(s.dashtype)
            val cx = ((xs + controls.map { it.first }).let { it.min() + it.max() }) / 2
            val cy = ((ys + controls.map { it.second }).let { it.min() + it.max() }) / 2
            fun turned(x: FloatArray, y: FloatArray): Pair<FloatArray, FloatArray> {
                if (s.rotate == 0.0) return x to y
                val c = cos(s.rotate).toFloat()
                val n = sin(s.rotate).toFloat()
                return FloatArray(x.size) { cx + (x[it] - cx) * c - (y[it] - cy) * n } to FloatArray(y.size) { cy + (x[it] - cx) * n + (y[it] - cy) * c }
            }
            val ends = floatArrayOf(xs.first(), xs.last()) to floatArrayOf(ys.first(), ys.last())
            val (kind, outline) = when (s.shapeType) {
                SHAPE_ELLIPSE -> ShapeKind.ELLIPSE to corners(xs, ys)
                SHAPE_TRIANGLE -> ShapeKind.TRIANGLE to if (xs.size == 3) xs to ys else box(xs, ys).let { (l, t, r, b) -> floatArrayOf((l + r) / 2, r, l) to floatArrayOf(t, b, b) }
                SHAPE_RECTANGLE -> ShapeKind.RECTANGLE to corners(xs, ys)
                SHAPE_LINE -> ShapeKind.LINE to ends
                SHAPE_ARROW -> ShapeKind.ARROW to ends
                SHAPE_CURVE -> controls.firstOrNull()?.let { (qx, qy) ->
                    ShapeKind.CURVE to (
                        floatArrayOf(xs.first(), 0.25f * xs.first() + 0.5f * qx + 0.25f * xs.last(), xs.last()) to
                            floatArrayOf(ys.first(), 0.25f * ys.first() + 0.5f * qy + 0.25f * ys.last(), ys.last())
                        )
                } ?: (ShapeKind.LINE to ends)
                SHAPE_POLYLINE, SHAPE_POLYLINE_CURVE -> {
                    val (x, y) = turned(xs, ys)
                    return ink(Pen.BALLPOINT, color, x, y, FloatArray(x.size) { stroke }, timeOf(s.key))
                }
                SHAPE_HEART -> ShapeKind.POLYGON to heart(xs, ys)
                else -> open(xs, ys).let { if (it.first.size >= 3) ShapeKind.POLYGON to it else ShapeKind.RECTANGLE to corners(xs, ys) }
            }
            val (x, y) = turned(outline.first, outline.second)
            return Shape(newId(), kind, x, y, color, stroke, if (kind.closed) s.fillColor.toInt() else 0, line)
        }

        /** Each line of a text mark as x, y, width, height: a band for highlights, a line-high box for underlines. */
        private fun lineRects(paths: List<MarkPath>, underline: Boolean): FloatArray {
            val rects = mutableListOf<Float>()
            paths.forEach { path ->
                val p = decodeLinePoints(path.points)
                if (p.size < 4) return@forEach
                val x0 = p[0] * width
                val x1 = p[p.size - 2] * width
                val mid = (p[1] + p[p.size - 1]) / 2 * width
                val band = (path.width * width).toFloat().coerceAtLeast(MIN_WIDTH)
                if (underline) {
                    val height = band / UNDERLINE_RATIO
                    rects += listOf(min(x0, x1), mid - height + max(0.8f, band) / 2, abs(x1 - x0), height)
                } else {
                    rects += listOf(min(x0, x1), mid - band / 2, abs(x1 - x0), band)
                }
            }
            return rects.toFloatArray()
        }

        private fun mark(a: AnnotationEntry): TextMark? {
            val underline = a.type == TYPE_UNDERLINE
            val rects = lineRects(a.paths, underline)
            if (rects.isEmpty()) return null
            return TextMark(newId(), if (underline) MarkKind.UNDERLINE else MarkKind.HIGHLIGHT, a.strokeColor.toInt(), rects, a.text)
        }

        private fun maskColor(stroke: Long, fill: Long) = (if (stroke != 0L) stroke else fill).toInt() or OPAQUE

        private fun masking(m: MaskingEntry): Mask? {
            val (xs, ys, ws) = points(m.points, m.start, m.scale) ?: return null
            val color = maskColor(m.strokeColor, m.fillColor)
            val tries = m.correct + m.wrong
            if (m.figure in CLOSED_FIGURES) {
                val (x, y) = corners(xs, ys)
                return Mask(newId(), MaskKind.TAPE, x, y, ws[0], color, tries, m.correct)
            }
            return Mask(newId(), MaskKind.PEN, xs, ys, ws[0].coerceAtLeast(MIN_WIDTH), color, tries, m.correct)
        }

        private fun textMasks(m: MaskingAnnotationEntry): List<Mask> {
            val rects = lineRects(m.paths, underline = false)
            val color = maskColor(m.strokeColor, m.fillColor)
            return (rects.indices step 4).map { i ->
                val (l, t, w, h) = listOf(rects[i], rects[i + 1], rects[i + 2], rects[i + 3])
                Mask(newId(), MaskKind.TAPE, floatArrayOf(l, l + w, l + w, l), floatArrayOf(t, t, t + h, t + h), h, color, m.correct + m.wrong, m.correct)
            }
        }

        private fun picture(i: ImageEntry): Picture? {
            val blob = images[i.key] ?: return null
            val f = i.frame
            if (f.width <= 0 || f.height <= 0) return null
            val crops = i.freeCrops.filter { it.x != FREE_CROP_GAP && it.y != FREE_CROP_GAP }
            var crop = i.cropBox?.let { floatArrayOf(it.x, it.y, it.x + it.width, it.y + it.height) } ?: floatArrayOf(0f, 0f, 1f, 1f)
            var outline = emptyList<Float>()
            if (crops.size >= 3) {
                val left = crops.minOf { it.x }
                val top = crops.minOf { it.y }
                val right = crops.maxOf { it.x }
                val bottom = crops.maxOf { it.y }
                if (right > left && bottom > top) {
                    crop = floatArrayOf(left, top, right, bottom)
                    outline = crops.flatMap { listOf((it.x - left) / (right - left), (it.y - top) / (bottom - top)) }
                }
            }
            return Picture(
                id = newId(),
                blob = blob,
                cx = (f.x + f.width / 2) * width,
                cy = (f.y + f.height / 2) * width,
                width = f.width * width,
                height = f.height * width,
                rotation = Math.toDegrees(i.rotate).toFloat(),
                cropLeft = crop[0].coerceIn(0f, 1f),
                cropTop = crop[1].coerceIn(0f, 1f),
                cropRight = crop[2].coerceIn(0f, 1f),
                cropBottom = crop[3].coerceIn(0f, 1f),
                outline = outline,
            )
        }

        private fun textBox(t: TextEntry): TextBox {
            val spans = t.columns.flatMap { it.p?.span.orEmpty() }
            val style = spans.firstNotNullOfOrNull { it.style }
            val tokens = style?.fontStyle.orEmpty().lowercase().split(' ')
            val hex = style?.color?.removePrefix("#")
            return TextBox(
                id = newId(),
                text = t.text.ifEmpty { t.columns.joinToString("\n") { column -> column.p?.span.orEmpty().joinToString("") { it.text } } },
                x = t.frame.x * width,
                y = t.frame.y * width,
                width = t.frame.width * width,
                height = t.frame.height * width,
                rotation = Math.toDegrees(t.rotate).toFloat(),
                font = family(style?.fontFamily),
                size = ((style?.fontSize ?: DEFAULT_FONT_SIZE) * width).toFloat(),
                color = hex?.toLongOrNull(16)?.let { if (hex.length <= 6) it or 0xFF000000 else it }?.toInt() ?: OPAQUE,
                bold = style?.fontWeight.equals("bold", ignoreCase = true) || "bold" in tokens,
                italic = "italic" in tokens,
                underline = "underline" in tokens,
            )
        }

        private fun note(s: StickyEntry): StickyNote {
            val palette = ToolState.NOTE_COLORS
            val color = s.color.let { c ->
                when {
                    c == null -> palette[0]
                    c ushr 24 != 0L -> c.toInt()
                    c in palette.indices.map { it.toLong() } -> palette[c.toInt()]
                    else -> palette[0]
                }
            }
            return StickyNote(newId(), s.frame.x * width, s.frame.y * width, s.text, color, timeOf(s.key))
        }
    }

    /** A ZIP read by entry names without a leading slash, since Flexcil writes names both ways. */
    private class Archive(private val zip: ZipFile) : Closeable {
        private val entries = zip.entries().asSequence().filter { !it.isDirectory }.associateBy { it.name.trimStart('/') }

        val names: Set<String> get() = entries.keys

        fun bytes(name: String): ByteArray? = entries[name]?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }

        fun open(name: String): InputStream = zip.getInputStream(entries.getValue(name))

        override fun close() = zip.close()
    }

    private fun extract(zip: Archive, name: String): File {
        val target = File(workDir, newId())
        zip.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private companion object {
        const val DEFAULT_WIDTH = 595f
        const val DEFAULT_HEIGHT = 842f
        const val DEFAULT_FONT_SIZE = 23.0 / 768
        const val MIN_WIDTH = 0.1f

        /** Flexcil keeps no point times; ink needs them rising, so points get one every 10 ms. */
        const val POINT_MS = 10
        const val UNDERLINE_RATIO = 0.08f
        const val FREE_CROP_GAP = -9f
        const val OPAQUE = 0xFF000000.toInt()
        const val TYPE_PDF = 0
        const val PDF_LINK = 2
        const val LINK_PAGE = 1
        const val LINK_URL = 3
        const val TYPE_NOTE = 4
        const val TYPE_PLANNER = 5
        const val TYPE_HIGHLIGHTER_INK = 2
        const val TYPE_UNDERLINE = 10
        const val MODE_HIGHLIGHTER = 2
        const val MODE_FOUNTAIN = 5
        const val MODE_PENCIL = 6
        const val MODE_SQUARE_HIGHLIGHTER = 20
        const val MODE_SLANTED_FOUNTAIN = 21
        const val MODE_ROUND_FOUNTAIN = 22
        const val MODE_TEXTURE_PENCIL = 23
        const val FIGURE_LINE = 1
        const val FIGURE_RECTANGLE = 2
        const val FIGURE_RHOMBUS = 3
        const val FIGURE_ELLIPSE = 4
        const val FIGURE_TRIANGLE = 5
        const val FIGURE_POLYGON = 6
        val CLOSED_FIGURES = setOf(FIGURE_RECTANGLE, FIGURE_RHOMBUS, FIGURE_ELLIPSE, FIGURE_TRIANGLE, FIGURE_POLYGON)
        const val SHAPE_ELLIPSE = 1
        const val SHAPE_TRIANGLE = 2
        const val SHAPE_RECTANGLE = 3
        const val SHAPE_LINE = 5
        const val SHAPE_CURVE = 6
        const val SHAPE_ARROW = 7
        const val SHAPE_POLYLINE = 8
        const val SHAPE_POLYLINE_CURVE = 10
        const val SHAPE_HEART = 11

        fun Double?.ms(): Long? = this?.takeIf { it > 0 }?.let { (it * 1000).toLong() }

        fun box(xs: FloatArray, ys: FloatArray) = listOf(xs.min(), ys.min(), xs.max(), ys.max())

        /** The bounding box's corners, clockwise from top-left. */
        fun corners(xs: FloatArray, ys: FloatArray): Pair<FloatArray, FloatArray> {
            val (l, t, r, b) = box(xs, ys)
            return floatArrayOf(l, r, r, l) to floatArrayOf(t, t, b, b)
        }

        /** The points without a closing point that repeats the first. */
        fun open(xs: FloatArray, ys: FloatArray): Pair<FloatArray, FloatArray> {
            val closed = xs.size > 1 && abs(xs.first() - xs.last()) < 1e-3f && abs(ys.first() - ys.last()) < 1e-3f
            return if (closed) xs.copyOf(xs.size - 1) to ys.copyOf(ys.size - 1) else xs to ys
        }

        /** A heart outline filling the points' bounding box. */
        fun heart(xs: FloatArray, ys: FloatArray): Pair<FloatArray, FloatArray> {
            val (l, t, r, b) = box(xs, ys)
            val steps = 48
            val hx = FloatArray(steps)
            val hy = FloatArray(steps)
            for (i in 0 until steps) {
                val a = 2 * PI * i / steps
                hx[i] = (16 * sin(a) * sin(a) * sin(a)).toFloat()
                hy[i] = -(13 * cos(a) - 5 * cos(2 * a) - 2 * cos(3 * a) - cos(4 * a)).toFloat()
            }
            val minX = hx.min()
            val minY = hy.min()
            val spanX = hx.max() - minX
            val spanY = hy.max() - minY
            return FloatArray(steps) { l + (hx[it] - minX) / spanX * (r - l) } to FloatArray(steps) { t + (hy[it] - minY) / spanY * (b - t) }
        }

        fun family(name: String?): String {
            val n = name.orEmpty().lowercase()
            return when {
                "courier" in n || "mono" in n -> "monospace"
                "times" in n || ("serif" in n && "sans" !in n) -> "serif"
                else -> "sans-serif"
            }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** A file's text, or its `_back` copy when the file is missing or empty. */
        fun Archive.text(name: String): String? =
            sequenceOf(name, "${name}_back").mapNotNull { bytes(it)?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank) }.firstOrNull()

        fun <T> Archive.decode(name: String, serializer: KSerializer<T>): T? =
            sequenceOf(name, "${name}_back").mapNotNull { file ->
                bytes(file)?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)?.let { runCatching { flexJson.decodeFromString(serializer, it) }.getOrNull() }
            }.firstOrNull()

        fun <T> Archive.list(name: String, serializer: KSerializer<T>): List<T> = text(name)?.let { decodeArray(it, serializer) }.orEmpty()
    }
}
