package net.mildhope.nxtes.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.data.PageSize
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

    /**
     * Draws the page at [left],[top] with size [width]x[height] in bitmap pixels, turned clockwise
     * by [turns] quarter turns. Offsets may be negative to render only part of a zoomed page.
     */
    suspend fun render(file: File, pageIndex: Int, bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int, turns: Int = 0) =
        withContext(Dispatchers.IO) {
            synchronized(open) {
                val doc = open.getOrPut(file.path) { openFile(file) }
                doc.openPage(pageIndex)?.use { page ->
                    if (turns % 4 == 0) {
                        page.renderPageBitmap(
                            bitmap, left, top, width, height,
                            renderAnnot = true,
                            canvasColor = 0,
                            pageBackgroundColor = WHITE,
                        )
                    } else {
                        val matrix = turnMatrix(page.getPageWidthPoint(), page.getPageHeightPoint(), turns, left, top, width, height)
                        val clip = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                        page.renderPageBitmap(bitmap, matrix, clip, true, false, 0, WHITE)
                    }
                }
            }
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
    }
}
