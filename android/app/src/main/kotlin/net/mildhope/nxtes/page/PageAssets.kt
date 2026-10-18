package net.mildhope.nxtes.page

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.data.PageContent
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** Decoded pictures and loaded fonts, keyed by blob hash so documents share them. */
class PageAssets(private val blobFile: (documentId: String, sha256: String) -> File) {
    private val bitmaps = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = Collections.synchronizedSet(mutableSetOf<String>())
    private val typefaces = ConcurrentHashMap<String, Typeface>()

    /** Bumped on the main thread when a picture finishes loading, so canvases redraw. */
    var loaded by mutableIntStateOf(0)
        private set

    /** Resources that load pictures in the background and return null until they're ready. */
    fun resources(documentId: String, scope: CoroutineScope): PageResources = object : PageResources {
        override fun bitmap(blob: String): Bitmap? = bitmaps.get(blob) ?: null.also { request(documentId, blob, scope) }

        override fun typeface(font: String) = typeface(documentId, font)
    }

    /** Resources that load pictures on the calling thread, for rendering off the main thread. */
    fun blockingResources(documentId: String): PageResources = object : PageResources {
        override fun bitmap(blob: String): Bitmap? = bitmaps.get(blob) ?: decode(blobFile(documentId, blob))?.also { bitmaps.put(blob, it) }

        override fun typeface(font: String) = typeface(documentId, font)
    }

    fun typeface(documentId: String, font: String): Typeface = typefaces.getOrPut(font) {
        if (font.startsWith(PageContent.FONT_BLOB)) {
            val file = blobFile(documentId, font.removePrefix(PageContent.FONT_BLOB))
            runCatching { Typeface.createFromFile(file) }.getOrElse {
                Log.w(TAG, "Font $font failed to load", it)
                return Typeface.DEFAULT
            }
        } else {
            Typeface.create(font, Typeface.NORMAL)
        }
    }

    private fun request(documentId: String, blob: String, scope: CoroutineScope) {
        if (!pending.add(blob)) return
        scope.launch(Dispatchers.IO) {
            try {
                decode(blobFile(documentId, blob))?.let { bitmaps.put(blob, it) }
            } finally {
                pending.remove(blob)
                withContext(Dispatchers.Main) { loaded++ }
            }
        }
    }

    private companion object {
        const val TAG = "PageAssets"
        const val MEMORY_BYTES = 96 * 1024 * 1024
        const val MAX_SIDE = 2048

        fun decode(file: File): Bitmap? {
            if (!file.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
