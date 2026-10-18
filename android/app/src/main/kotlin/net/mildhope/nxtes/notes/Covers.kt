package net.mildhope.nxtes.notes

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
}

@Composable
fun CoverArt(cover: Cover, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(cover.top), Color(cover.bottom))))
        val spine = size.width * 0.09f
        drawRect(Color.Black.copy(alpha = 0.16f), size = Size(spine, size.height))
        drawRect(Color.White.copy(alpha = 0.3f), topLeft = Offset(spine, 0f), size = Size(max(1f, size.width * 0.012f), size.height))
    }
}
