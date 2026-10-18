package net.mildhope.nxtes.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.util.Log
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.data.Link
import net.mildhope.nxtes.data.Page
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.Picture
import net.mildhope.nxtes.data.Shape
import net.mildhope.nxtes.data.StickyNote
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.page.PageAssets
import net.mildhope.nxtes.page.PageBackgrounds
import net.mildhope.nxtes.page.PagePainter
import net.mildhope.nxtes.page.PageResources
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import com.tom_roush.pdfbox.util.Matrix as PdfMatrix

/** What an export includes. Flattened PDFs are pictures of the pages; otherwise original PDF pages stay as they are. */
data class ExportOptions(
    val flatten: Boolean = false,
    val background: Boolean = true,
    /** Ink, shapes and marked PDF text. */
    val handwriting: Boolean = true,
    val images: Boolean = true,
    val textBoxes: Boolean = true,
    val notes: Boolean = true,
    val masks: Boolean = false,
)

/** The page's content with only what [options] includes. */
fun PageContent.forExport(options: ExportOptions) = copy(
    strokes = if (options.handwriting) strokes else emptyList(),
    objects = objects.filter {
        when (it) {
            is Shape -> options.handwriting
            is TextBox -> options.textBoxes
            is Picture -> options.images
        }
    },
    notes = if (options.notes) notes else emptyList(),
    masks = if (options.masks) masks else emptyList(),
    marks = if (options.handwriting) marks else emptyList(),
)

/**
 * Page numbers typed as "1-3, 5" as zero-based indices in order, without repeats; null when the
 * text names no page or one past [count].
 */
fun parsePages(text: String, count: Int): List<Int>? {
    val out = LinkedHashSet<Int>()
    for (part in text.replace('–', '-').replace('—', '-').split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
        val bounds = part.split('-').map { it.trim().toIntOrNull() ?: return null }
        val (from, to) = when (bounds.size) {
            1 -> bounds[0] to bounds[0]
            2 -> bounds[0] to bounds[1]
            else -> return null
        }
        if (from < 1 || to > count || from > to) return null
        (from..to).forEach { out += it - 1 }
    }
    return out.toList().ifEmpty { null }
}

/**
 * Maps the overlay page's space (the page as shown, PDF style with y up, [width] by [height]) onto
 * an original PDF page with crop box [crop] shown turned clockwise by [rotation] degrees.
 */
fun overlayToPage(crop: PDRectangle, rotation: Int, width: Float, height: Float): PdfMatrix {
    val cw = crop.width
    val ch = crop.height
    val sideways = rotation % 180 != 0
    val sx = (if (sideways) ch else cw) / width
    val sy = (if (sideways) cw else ch) / height
    val (a, b, c, d) = when (rotation) {
        90 -> listOf(0f, 1f, -1f, 0f)
        180 -> listOf(-1f, 0f, 0f, -1f)
        270 -> listOf(0f, -1f, 1f, 0f)
        else -> listOf(1f, 0f, 0f, 1f)
    }
    val (e, f) = when (rotation) {
        90 -> cw to 0f
        180 -> cw to ch
        270 -> 0f to ch
        else -> 0f to 0f
    }
    return PdfMatrix(a * sx, b * sx, c * sy, d * sy, e + crop.lowerLeftX, f + crop.lowerLeftY)
}

/** Writes documents as PDFs and pages as pictures. */
class Exporter(
    private val context: Context,
    private val library: LibraryStore,
    private val backgrounds: PageBackgrounds,
    private val assets: PageAssets,
) {
    val folder get() = File(context.cacheDir, "export").apply { mkdirs() }

    /** A file name for [title] without characters other systems refuse. */
    fun fileName(title: String, extension: String) = title.replace(Regex("[\\\\/:*?\"<>|\\n]"), "_").trim().ifEmpty { "Nxtes" } + ".$extension"

    /** Writes [pages] of [doc] to [out] as a PDF. */
    suspend fun pdf(doc: Document, pages: List<Int>, options: ExportOptions, out: File) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val resources = assets.blockingResources(doc.id)
        val painter = PagePainter()
        val contents = pages.associateWith { library.readPage(doc.id, doc.pages[it].id) }
        val sources = HashMap<String, PDDocument?>()
        fun original(page: Page): PDPage? {
            if (options.flatten || !options.background) return null
            val source = page.pdf ?: return null
            val pdf = sources.getOrPut(source.blob) {
                runCatching { PDDocument.load(library.blobFile(doc.id, source.blob), MemoryUsageSetting.setupTempFileOnly()) }
                    .onFailure { Log.w("Export", "Can't open the PDF to copy its pages; drawing them instead", it) }
                    .getOrNull()
            }
            return pdf?.takeIf { source.index < it.numberOfPages }?.getPage(source.index)
        }
        val overlayFile = File.createTempFile("overlay", ".pdf", folder)
        val output = PDDocument(MemoryUsageSetting.setupTempFileOnly())
        var overlay: PDDocument? = null
        try {
            if (!options.flatten) {
                writeOverlay(doc, pages, contents, options, resources, painter, overlayFile) { original(it) != null }
                overlay = PDDocument.load(overlayFile, MemoryUsageSetting.setupTempFileOnly())
            }
            val layers = LayerUtility(output)
            val exported = HashMap<String, PDPage>()
            val linking = mutableListOf<() -> Unit>()
            pages.forEachIndexed { n, i ->
                val page = doc.pages[i]
                val content = contents.getValue(i)
                if (overlay == null) {
                    addFlattened(output, doc, page, content.forExport(options), options, resources, painter)
                    return@forEachIndexed
                }
                val drawn = overlay.getPage(n)
                val width = drawn.mediaBox.width
                val height = drawn.mediaBox.height
                val source = original(page)
                val (target, toPage) = if (source != null) {
                    val imported = output.importPage(source)
                    if (!page.pdfAnnotations) imported.annotations = imported.annotations.filter { it.subtype == "Link" }
                    val rotation = (source.rotation + 90 * page.turns).mod(360)
                    imported.rotation = rotation
                    val matrix = overlayToPage(imported.cropBox, rotation, width, height)
                    val form = layers.importPageAsForm(overlay, n)
                    PDPageContentStream(output, imported, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.saveGraphicsState()
                        cs.transform(matrix)
                        cs.drawForm(form)
                        cs.restoreGraphicsState()
                    }
                    imported to matrix
                } else {
                    output.importPage(drawn) to PdfMatrix()
                }
                val placement = Placement(toPage, page, width, height)
                if (options.notes) addNotes(target, content.notes, placement)
                exported[page.id] = target
                if (content.links.isNotEmpty()) linking += { addLinks(target, content.links, placement, exported) }
            }
            linking.forEach { it() }
            output.save(out)
        } finally {
            output.close()
            overlay?.close()
            sources.values.forEach { it?.close() }
            overlayFile.delete()
        }
    }

    /**
     * Draws each page's background and content into a PDF with Android's PDF canvas, so ink, shapes
     * and text stay sharp. Pages [keepsOriginal] says will sit on their original PDF page get only
     * their content; sticky notes become PDF comments later instead.
     */
    private suspend fun writeOverlay(
        doc: Document,
        pages: List<Int>,
        contents: Map<Int, PageContent>,
        options: ExportOptions,
        resources: PageResources,
        painter: PagePainter,
        out: File,
        keepsOriginal: (Page) -> Boolean,
    ) {
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { n, i ->
                val page = doc.pages[i]
                val info = PdfDocument.PageInfo.Builder(page.width.roundToInt().coerceAtLeast(1), page.height.roundToInt().coerceAtLeast(1), n + 1).create()
                val sheet = pdf.startPage(info)
                val canvas = sheet.canvas
                canvas.scale(info.pageWidth / page.width, info.pageHeight / page.height)
                when {
                    !options.background -> canvas.drawColor(Color.WHITE)
                    keepsOriginal(page) -> Unit
                    page.background != null -> PageBackgrounds.drawTemplate(canvas, page, 1f)
                    page.pdf != null -> drawRendered(canvas, doc, page)
                    else -> canvas.drawColor(Color.WHITE)
                }
                val content = contents.getValue(i).forExport(options).copy(notes = emptyList())
                if (!content.isEmpty) painter.draw(canvas, content, Matrix(), RectF(0f, 0f, page.width, page.height), resources)
                pdf.finishPage(sheet)
            }
            out.outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
    }

    /** Draws a PDF page that couldn't be copied as a picture instead. */
    private suspend fun drawRendered(canvas: Canvas, doc: Document, page: Page) {
        val bitmap = render(doc, page, FLAT_DPI / 72f, background = true, content = null, resources = null, painter = null)
        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, page.width, page.height), null)
        bitmap.recycle()
    }

    private suspend fun addFlattened(
        output: PDDocument,
        doc: Document,
        page: Page,
        content: PageContent,
        options: ExportOptions,
        resources: PageResources,
        painter: PagePainter,
    ) {
        val bitmap = render(doc, page, FLAT_DPI / 72f, options.background, content, resources, painter)
        try {
            val image = JPEGFactory.createFromImage(output, bitmap, JPEG_QUALITY)
            val target = PDPage(PDRectangle(page.width, page.height))
            output.addPage(target)
            PDPageContentStream(output, target).use { it.drawImage(image, 0f, 0f, page.width, page.height) }
        } finally {
            bitmap.recycle()
        }
    }

    /** A picture of the page at [scale] pixels per point, with its background if asked and [content] on top. */
    suspend fun render(
        doc: Document,
        page: Page,
        scale: Float,
        background: Boolean,
        content: PageContent?,
        resources: PageResources?,
        painter: PagePainter?,
    ): Bitmap {
        val w = (page.width * scale).roundToInt().coerceAtLeast(1)
        val h = (page.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = createBitmap(w, h)
        if (background) backgrounds.render(doc.id, page, bitmap, 0, 0, w, h) else bitmap.eraseColor(Color.WHITE)
        if (content != null && !content.isEmpty && resources != null && painter != null) {
            painter.draw(Canvas(bitmap), content, Matrix().apply { setScale(scale, scale) }, RectF(0f, 0f, w.toFloat(), h.toFloat()), resources)
        }
        return bitmap
    }

    /** Writes [pages] of [doc] as PNG pictures at [scale] pixels per point. */
    suspend fun images(doc: Document, pages: List<Int>, options: ExportOptions, scale: Float = IMAGE_SCALE): List<File> = withContext(Dispatchers.IO) {
        val resources = assets.blockingResources(doc.id)
        val painter = PagePainter()
        pages.map { i ->
            val page = doc.pages[i]
            val content = library.readPage(doc.id, page.id).forExport(options)
            val bitmap = render(doc, page, scale, options.background, content, resources, painter)
            try {
                File(folder, fileName("${doc.title} ${i + 1}", "png")).also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Where page points land on an exported page that was drawn [width] by [height] and placed by [toPage]. */
    private class Placement(private val toPage: PdfMatrix, private val page: Page, private val width: Float, private val height: Float) {
        /** The PDF rectangle around a box in page points. */
        fun rectangle(x: Float, y: Float, w: Float, h: Float): PDRectangle {
            val kx = width / page.width
            val ky = height / page.height
            val corners = listOf(x to y, x + w to y, x to y + h, x + w to y + h).map { (px, py) -> toPage.transformPoint(px * kx, height - py * ky) }
            val left = corners.minOf { it.x }
            val bottom = corners.minOf { it.y }
            return PDRectangle(left, bottom, corners.maxOf { it.x } - left, corners.maxOf { it.y } - bottom)
        }
    }

    /** Adds sticky notes as PDF comments where their icons were. */
    private fun addNotes(target: PDPage, notes: List<StickyNote>, at: Placement) {
        for (note in notes) {
            val annotation = PDAnnotationText().apply {
                rectangle = at.rectangle(note.x, note.y, PagePainter.NOTE_SIZE, PagePainter.NOTE_SIZE)
                contents = note.text
                name = PDAnnotationText.NAME_NOTE
                color = PDColor(floatArrayOf(Color.red(note.color) / 255f, Color.green(note.color) / 255f, Color.blue(note.color) / 255f), PDDeviceRGB.INSTANCE)
            }
            runCatching { annotation.constructAppearances() }
            target.annotations = target.annotations + annotation
        }
    }

    /**
     * Adds links as borderless PDF links, replacing the page's own PDF links in the same places, whose
     * page targets don't survive the copy. Page links to pages left out of the export are dropped.
     */
    private fun addLinks(target: PDPage, links: List<Link>, at: Placement, exported: Map<String, PDPage>) {
        val added = links.mapNotNull { link ->
            val annotation = PDAnnotationLink().apply {
                rectangle = at.rectangle(link.x, link.y, link.width, link.height)
                borderStyle = PDBorderStyleDictionary().apply { width = 0f }
            }
            when {
                link.pageId != null -> annotation.destination = PDPageFitDestination().apply { page = exported[link.pageId] ?: return@mapNotNull null }
                link.url != null -> annotation.action = PDActionURI().apply { uri = link.url }
                else -> return@mapNotNull null
            }
            annotation
        }
        fun PDRectangle.near(o: PDRectangle) = listOf(lowerLeftX - o.lowerLeftX, lowerLeftY - o.lowerLeftY, upperRightX - o.upperRightX, upperRightY - o.upperRightY)
            .all { abs(it) < LINK_MATCH_PT }
        val kept = target.annotations.filterNot { old -> old is PDAnnotationLink && old.rectangle?.let { r -> added.any { it.rectangle.near(r) } } == true }
        target.annotations = kept + added
    }

    private companion object {
        const val FLAT_DPI = 200f
        const val JPEG_QUALITY = 0.85f
        const val IMAGE_SCALE = 2f
        const val LINK_MATCH_PT = 2f
    }
}
