package net.mildhope.nxtes.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.page.decodeImage
import net.mildhope.nxtes.page.keepBitmap
import java.io.File
import kotlin.math.max

/** A generated notebook cover: a vertical gradient, solid when both ends match. */
class Cover(val top: Int, val bottom: Int)

object Covers {
    val solid = listOf(
        0xFF2F4858, 0xFF33658A, 0xFF86BBD8, 0xFF2E8B57, 0xFFF6AE2D, 0xFFF26419, 0xFFB23A48, 0xFF6D597A, 0xFF3D3D3D, 0xFFE9E4D8,
    ).map { Cover(it.toInt(), it.toInt()) }

    val gradients = listOf(
        0xFFFF9A8B to 0xFFFFD6A5, 0xFF89F7FE to 0xFF66A6FF, 0xFFA18CD1 to 0xFFFBC2EB, 0xFF84FAB0 to 0xFF8FD3F4,
        0xFFF6D365 to 0xFFFDA085, 0xFF5EE7DF to 0xFFB490CA, 0xFFFFECD2 to 0xFFFCB69F, 0xFF434343 to 0xFF000000,
    ).map { (top, bottom) -> Cover(top.toInt(), bottom.toInt()) }

    val all = solid + gradients

    fun get(index: Int?): Cover? = index?.let(all::getOrNull)

    /** Covers are A-series shaped, whatever the pages are. */
    const val RATIO = 1 / 1.414f
}

/** Longest side, in pixels, a cover image is kept at. */
private const val COVER_SIDE = 1600

/** Shading along the left edge that makes a cover read as a notebook. */
private fun Modifier.spine() = drawWithContent {
    drawContent()
    val spine = size.width * 0.09f
    drawRect(Color.Black.copy(alpha = 0.16f), size = Size(spine, size.height))
    drawRect(Color.White.copy(alpha = 0.3f), topLeft = Offset(spine, 0f), size = Size(max(1f, size.width * 0.012f), size.height))
}

@Composable
fun CoverArt(cover: Cover, modifier: Modifier = Modifier) {
    Canvas(modifier.spine()) {
        drawRect(Brush.verticalGradient(listOf(Color(cover.top), Color(cover.bottom))))
    }
}

/** An image cover, cropped to fill [modifier]'s box. [load] gives the picture, keyed by [key]. */
@Composable
fun CoverPhoto(key: Any?, load: suspend () -> Bitmap?, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, key) {
        value = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }?.asImageBitmap()
    }
    val box = modifier.background(Color.LightGray).spine()
    val image = bitmap
    if (image == null) Box(box) else Image(image, null, contentScale = ContentScale.Crop, modifier = box)
}

/** A cover image file decoded at most about [maxSide] pixels on its long side. Blocks. */
fun decodeCover(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Makes the image at [uri] the document's cover, upright and at most [COVER_SIDE] pixels long. Blocks. */
fun LibraryStore.importCover(context: Context, documentId: String, uri: Uri) {
    val bitmap = decodeImage(context, uri, COVER_SIDE)
    try {
        keepBitmap(context, bitmap) { setCoverImage(documentId, it) }
    } finally {
        bitmap.recycle()
    }
}
