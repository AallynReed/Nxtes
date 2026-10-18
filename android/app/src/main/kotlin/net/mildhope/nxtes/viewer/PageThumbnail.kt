package net.mildhope.nxtes.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.nxtes

private const val PAGE_THUMBNAIL_PX = 240

/** A page preview with its ink, at the page's aspect ratio; size it with [modifier]. */
@Composable
fun PageThumbnail(doc: Document, index: Int, modifier: Modifier = Modifier, widthPx: Int = PAGE_THUMBNAIL_PX) {
    val context = LocalContext.current
    val page = doc.pages.getOrNull(index) ?: return
    val bitmap by produceState<ImageBitmap?>(null, doc.id, doc.modified, page) {
        value = runCatching { context.nxtes.thumbnails.get(doc, widthPx, index) }.getOrNull()?.asImageBitmap()
    }
    val box = modifier.aspectRatio(page.width / page.height).background(Color.White)
    val image = bitmap
    if (image == null) Box(box) else Image(image, null, contentScale = ContentScale.FillBounds, modifier = box)
}
