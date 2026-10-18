package net.mildhope.nxtes.page

import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.File
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private const val LONG_SIDE_PT = 842f
private const val MAX_PIXELS = 2400

/** Writes the image at [uri] to [out] as a one-page PDF whose long side matches A4's. */
fun imageToPdf(context: Context, uri: Uri, out: File) {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val longest = max(info.size.width, info.size.height)
        if (longest > MAX_PIXELS) decoder.setTargetSampleSize(ceil(longest / MAX_PIXELS.toFloat()).toInt())
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val scale = LONG_SIDE_PT / max(bitmap.width, bitmap.height)
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    val pdf = PdfDocument()
    try {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
        page.canvas.drawColor(Color.WHITE)
        page.canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        pdf.finishPage(page)
        out.outputStream().use(pdf::writeTo)
    } finally {
        pdf.close()
        bitmap.recycle()
    }
}

/** Opens a picked PDF, or a picked image converted to a PDF, for [use]. Blocks; call off the main thread. */
fun <T> openAsPdf(context: Context, uri: Uri, image: Boolean, use: (InputStream) -> T): T {
    if (!image) return requireNotNull(context.contentResolver.openInputStream(uri)) { "No data for $uri" }.use(use)
    val tmp = File.createTempFile("image", ".pdf", context.cacheDir)
    try {
        imageToPdf(context, uri, tmp)
        return tmp.inputStream().use(use)
    } finally {
        tmp.delete()
    }
}
