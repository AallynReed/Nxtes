package net.mildhope.nxtes.pdf

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.data.PageSize
import java.io.File

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

    /**
     * Draws the page at [left],[top] with size [width]x[height] in bitmap pixels.
     * Offsets may be negative to render only part of a zoomed page.
     */
    suspend fun render(file: File, pageIndex: Int, bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int) =
        withContext(Dispatchers.IO) {
            synchronized(open) {
                val doc = open.getOrPut(file.path) { openFile(file) }
                doc.openPage(pageIndex)?.use { page ->
                    page.renderPageBitmap(
                        bitmap, left, top, width, height,
                        renderAnnot = true,
                        canvasColor = 0,
                        pageBackgroundColor = WHITE,
                    )
                }
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
