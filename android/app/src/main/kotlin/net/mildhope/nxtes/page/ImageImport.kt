package net.mildhope.nxtes.page

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import net.mildhope.nxtes.data.LibraryStore
import java.io.File
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.max

private const val MAX_SIDE = 2048
private const val JPEG_QUALITY = 90

/** A picture stored in a document: its blob and size in pixels. */
class StoredImage(val blob: String, val width: Int, val height: Int)

/**
 * Copies the image at [uri] into the document, turned upright and at most 2048 pixels on its long
 * side. Images with transparency stay PNG; others become JPEG. Blocks; call off the main thread.
 */
fun importImage(context: Context, uri: Uri, documentId: String, store: LibraryStore): StoredImage {
    val bitmap = decodeImage(context, uri, MAX_SIDE)
    try {
        return StoredImage(keepBitmap(context, bitmap) { store.addBlob(documentId, it) }, bitmap.width, bitmap.height)
    } finally {
        bitmap.recycle()
    }
}

/** The image at [uri], upright and at most about [maxSide] pixels on its long side. Blocks. */
fun decodeImage(context: Context, uri: Uri, maxSide: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val longest = max(info.size.width, info.size.height)
        if (longest > maxSide) decoder.setTargetSampleSize(ceil(longest / maxSide.toFloat()).toInt())
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

/** Hands [keep] the bitmap as PNG if it has transparency or JPEG if not, and returns what it gives back. Blocks. */
fun <T> keepBitmap(context: Context, bitmap: Bitmap, keep: (InputStream) -> T): T {
    val tmp = File.createTempFile("image", null, context.cacheDir)
    try {
        val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        tmp.outputStream().use { bitmap.compress(format, JPEG_QUALITY, it) }
        return tmp.inputStream().use(keep)
    } finally {
        tmp.delete()
    }
}

/** A fresh file the camera app can write a photo into, and its shareable content URI. */
fun cameraTarget(context: Context): Pair<File, Uri> {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "photo.jpg").apply { delete() }
    return file to FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
