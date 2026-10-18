package net.mildhope.nxtes.viewer

import androidx.compose.ui.graphics.ColorMatrix

/**
 * Swaps light and dark while keeping hues: white paper becomes near-black and black ink near-white,
 * and a blue pen stays blue. It's an inversion followed by a half turn of hue, squeezed into 30..230.
 */
val DARK_PAGES = floatArrayOf(
    0.450f, -1.121f, -0.113f, 0f, 230f,
    -0.334f, -0.337f, -0.113f, 0f, 230f,
    -0.334f, -1.121f, 0.671f, 0f, 230f,
    0f, 0f, 0f, 1f, 0f,
)

val darkPagesMatrix get() = ColorMatrix(DARK_PAGES.copyOf())

/** What [argb] looks like with dark pages on. */
fun darkColor(argb: Int): Int {
    val r = (argb shr 16 and 0xFF).toFloat()
    val g = (argb shr 8 and 0xFF).toFloat()
    val b = (argb and 0xFF).toFloat()
    fun row(i: Int) = (DARK_PAGES[i] * r + DARK_PAGES[i + 1] * g + DARK_PAGES[i + 2] * b + DARK_PAGES[i + 4]).toInt().coerceIn(0, 255)
    return (argb and 0xFF000000.toInt()) or (row(0) shl 16) or (row(5) shl 8) or row(10)
}
