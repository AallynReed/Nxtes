package net.mildhope.nxtes.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.LibraryStore
import java.io.File
import kotlin.math.roundToInt

/** First-page previews, cached in memory and as JPEGs on disk. */
class Thumbnails(private val dir: File, private val pdf: PdfEngine, private val library: LibraryStore) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun get(doc: Document, widthPx: Int): Bitmap? {
        val page = doc.pages.firstOrNull() ?: return null
        val source = page.pdf ?: return null
        val key = "${page.id}-${doc.modified}-$widthPx"
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val docDir = File(dir, doc.id)
            val file = File(docDir, "$key.jpg")
            val cached = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            val bitmap = cached ?: run {
                val heightPx = (widthPx * page.height / page.width).roundToInt().coerceAtLeast(1)
                val rendered = createBitmap(widthPx, heightPx)
                pdf.render(library.blobFile(doc.id, source.blob), source.index, rendered, 0, 0, widthPx, heightPx)
                docDir.listFiles()?.forEach { it.delete() }
                docDir.mkdirs()
                file.outputStream().use { rendered.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                rendered
            }
            memory.put(key, bitmap)
            bitmap
        }
    }

    private companion object {
        const val MEMORY_BYTES = 24 * 1024 * 1024
        const val JPEG_QUALITY = 85
    }
}
