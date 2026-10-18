package net.mildhope.nxtes.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.data.Affine
import net.mildhope.nxtes.data.PageSize
import net.mildhope.nxtes.edit.PageText
import java.io.File

data class OutlineEntry(val title: String, val pdfPage: Int, val depth: Int)

/** PDFium access. Calls are serialised; recently used documents stay open. */
class PdfEngine(context: Context) {
    private val core = PdfiumCore(context)
    private val open = object : LinkedHashMap<String, PdfDocument>(MAX_OPEN, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PdfDocument>): Boolean =
            (size > MAX_OPEN).also { if (it) eldest.value.close() }
    }

    fun pageSizes(file: File): List<PageSize> =
        openFile(file).use { doc ->
            doc.getPageSizes(POINTS_DPI).map { PageSize(it.width.toFloat(), it.height.toFloat()) }
        }

    /** The PDF's table of contents, flattened in reading order. */
    suspend fun outline(file: File): List<OutlineEntry> = withContext(Dispatchers.IO) {
        synchronized(open) {
            val doc = open.getOrPut(file.path) { openFile(file) }
            val entries = mutableListOf<OutlineEntry>()
            fun walk(marks: List<io.legere.pdfiumandroid.api.Bookmark>, depth: Int) {
                marks.forEach {
                    entries += OutlineEntry(it.title.orEmpty(), it.pageIdx.toInt(), depth)
                    walk(it.children, depth + 1)
                }
            }
            walk(doc.getTableOfContents(), 0)
            entries
        }
    }

    /** The page's characters and their boxes in points from its top-left corner, as it's shown unturned. */
    suspend fun text(file: File, pageIndex: Int): PageText = withContext(Dispatchers.IO) {
        synchronized(open) {
            val doc = open.getOrPut(file.path) { openFile(file) }
            doc.openPage(pageIndex)?.use { page ->
                val shown = shownPoints(page)
                page.openTextPage().use { textPage ->
                    val count = textPage.textPageCountChars().coerceAtLeast(0)
                    val chars = StringBuilder(count)
                    val boxes = FloatArray(count * 4)
                    for (i in 0 until count) {
                        chars.append(textPage.textPageGetUnicode(i))
                        val r: RectF = textPage.textPageGetCharBox(i) ?: continue
                        val (ax, ay) = shown.map(r.left, r.top)
                        val (bx, by) = shown.map(r.right, r.bottom)
                        boxes[i * 4] = minOf(ax, bx)
                        boxes[i * 4 + 1] = minOf(ay, by)
                        boxes[i * 4 + 2] = maxOf(ax, bx)
                        boxes[i * 4 + 3] = maxOf(ay, by)
                    }
                    PageText(chars.toString(), boxes)
                }
            } ?: PageText("", FloatArray(0))
        }
    }

    /** The page's go-to and URL link annotations. */
    suspend fun links(file: File, pageIndex: Int): List<PdfLink> = withContext(Dispatchers.IO) {
        synchronized(open) {
            val doc = open.getOrPut(file.path) { openFile(file) }
            doc.openPage(pageIndex)?.use { page ->
                val shown = shownPoints(page)
                val w = page.getPageWidthPoint().toFloat()
                val h = page.getPageHeightPoint().toFloat()
                page.getPageLinks().map { link ->
                    val (ax, ay) = shown.map(link.bounds.left, link.bounds.top)
                    val (bx, by) = shown.map(link.bounds.right, link.bounds.bottom)
                    // URL links come with a page index too.
                    val url = link.uri?.takeIf { it.isNotBlank() }
                    val target = link.destPageIdx?.takeIf { url == null && it >= 0 }
                    PdfLink(minOf(ax, bx) / w, minOf(ay, by) / h, maxOf(ax, bx) / w, maxOf(ay, by) / h, target, url)
                }
            }.orEmpty()
        }
    }

    /**
     * Draws the page at [left],[top] with size [width]x[height] in bitmap pixels, turned clockwise
     * by [turns] quarter turns. Offsets may be negative to render only part of a zoomed page.
     */
    suspend fun render(file: File, pageIndex: Int, bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int, turns: Int = 0, annotations: Boolean = true) =
        withContext(Dispatchers.IO) {
            synchronized(open) {
                val doc = open.getOrPut(file.path) { openFile(file) }
                doc.openPage(pageIndex)?.use { page ->
                    if (turns % 4 == 0) {
                        page.renderPageBitmap(
                            bitmap, left, top, width, height,
                            renderAnnot = annotations,
                            canvasColor = 0,
                            pageBackgroundColor = WHITE,
                        )
                    } else {
                        val matrix = turnMatrix(page.getPageWidthPoint(), page.getPageHeightPoint(), turns, left, top, width, height)
                        val clip = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                        page.renderPageBitmap(bitmap, matrix, clip, annotations, false, 0, WHITE)
                    }
                }
            }
        }

    /** Maps PDF user space to points from the top-left of the page as it's shown unturned, after its crop box and rotation. */
    private fun shownPoints(page: PdfPage): Affine {
        val w = page.getPageWidthPoint() * MAP_SCALE
        val h = page.getPageHeightPoint() * MAP_SCALE
        fun map(x: Double, y: Double) = page.mapPageCoordsToDevice(0, 0, w, h, 0, x, y).let { it.x.toFloat() / MAP_SCALE to it.y.toFloat() / MAP_SCALE }
        val (ox, oy) = map(0.0, 0.0)
        val (xx, xy) = map(MAP_SPAN, 0.0)
        val (yx, yy) = map(0.0, MAP_SPAN)
        val span = MAP_SPAN.toFloat()
        return Affine((xx - ox) / span, (xy - oy) / span, (yx - ox) / span, (yy - oy) / span, ox, oy)
    }

    /** Maps page points to bitmap pixels with the page turned clockwise. */
    private fun turnMatrix(pageWidth: Int, pageHeight: Int, turns: Int, left: Int, top: Int, width: Int, height: Int): Matrix {
        val quarter = turns.mod(4)
        val sideways = quarter % 2 == 1
        val sourceWidth = (if (sideways) height else width).toFloat()
        val sourceHeight = (if (sideways) width else height).toFloat()
        return Matrix().apply {
            setScale(sourceWidth / pageWidth, sourceHeight / pageHeight)
            postRotate(90f * quarter)
            when (quarter) {
                1 -> postTranslate(sourceHeight, 0f)
                2 -> postTranslate(sourceWidth, sourceHeight)
                3 -> postTranslate(0f, sourceWidth)
            }
            postTranslate(left.toFloat(), top.toFloat())
            // pdfiumandroid passes the skew terms to PDFium in swapped order, so pre-swap them.
            val values = FloatArray(9).also(::getValues)
            values[Matrix.MSKEW_X] = values[Matrix.MSKEW_Y].also { values[Matrix.MSKEW_Y] = values[Matrix.MSKEW_X] }
            setValues(values)
        }
    }

    private fun openFile(file: File): PdfDocument {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            core.newDocument(fd)
        } catch (e: Exception) {
            fd.close()
            throw e
        }
    }

    private companion object {
        const val MAX_OPEN = 4
        const val POINTS_DPI = 72
        const val WHITE = 0xFFFFFFFF.toInt()

        /** Page-to-screen mapping rounds to whole pixels, so it's done this many times larger. */
        const val MAP_SCALE = 16
        const val MAP_SPAN = 1000.0
    }
}
