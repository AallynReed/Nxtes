package net.mildhope.nxtes.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.LruCache
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.data.Page
import net.mildhope.nxtes.ink.InkPainter
import net.mildhope.nxtes.page.PageBackgrounds
import java.io.File
import kotlin.math.roundToInt

/** Page previews with their ink, cached in memory and as JPEGs on disk. */
class Thumbnails(
    private val dir: File,
    private val backgrounds: PageBackgrounds,
    private val library: LibraryStore,
    private val painter: Lazy<InkPainter>,
) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun get(doc: Document, widthPx: Int, pageIndex: Int = 0): Bitmap? {
        val page = doc.pages.getOrNull(pageIndex) ?: return null
        val key = "${page.id}-${doc.modified}-$widthPx"
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val docDir = File(dir, doc.id)
            val file = File(docDir, "$key.jpg")
            val cached = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            val bitmap = cached ?: render(doc, page, widthPx).also { rendered ->
                docDir.listFiles()?.filter { it.name.startsWith("${page.id}-") }?.forEach { it.delete() }
                docDir.mkdirs()
                file.outputStream().use { rendered.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            }
            memory.put(key, bitmap)
            bitmap
        }
    }

    private suspend fun render(doc: Document, page: Page, widthPx: Int): Bitmap {
        val heightPx = (widthPx * page.height / page.width).roundToInt().coerceAtLeast(1)
        val bitmap = createBitmap(widthPx, heightPx)
        backgrounds.render(doc.id, page, bitmap, 0, 0, widthPx, heightPx)
        val strokes = library.readPageInk(doc.id, page.id)
        if (strokes.isNotEmpty()) {
            val scale = widthPx / page.width
            val matrix = Matrix().apply { setScale(scale, scale) }
            val clip = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
            synchronized(painter) { painter.value.draw(Canvas(bitmap), strokes, matrix, clip) }
        }
        return bitmap
    }

    private companion object {
        const val MEMORY_BYTES = 48 * 1024 * 1024
        const val JPEG_QUALITY = 85
    }
}
