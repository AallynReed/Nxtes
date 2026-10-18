package net.mildhope.nxtes.edit

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * The PDF characters a highlighter stroke runs over, so the stroke can become a highlight on that
 * text as it does in Flexcil: everything it touches when it stays on one line, or from where it
 * first touches text to where it last does when it crosses lines. Null when less than half the
 * stroke lies over text, so it stays ink. [width] is the stroke's width.
 */
fun snapHighlight(text: PageText, xs: FloatArray, ys: FloatArray, width: Float): IntRange? {
    if (xs.isEmpty() || text.length == 0) return null
    val samples = sample(xs, ys)
    val hits = (samples.indices step 2).map { charUnder(text, samples[it], samples[it + 1], width / 2) }.filter { it >= 0 }
    if (hits.isEmpty() || hits.size * 2 < samples.size / 2) return null
    val b = text.boxes
    val top = b[hits[0] * 4 + 1]
    val bottom = b[hits[0] * 4 + 3]
    val oneLine = hits.all { val middle = (b[it * 4 + 1] + b[it * 4 + 3]) / 2; middle > top && middle < bottom }
    return if (oneLine) hits.min()..hits.max() else minOf(hits.first(), hits.last())..maxOf(hits.first(), hits.last())
}

/** The character whose box the point is over, letting the stroke's half-width reach into it from above or below. */
private fun charUnder(text: PageText, x: Float, y: Float, halfWidth: Float): Int {
    val b = text.boxes
    for (i in 0 until text.length) {
        val left = b[i * 4]
        val top = b[i * 4 + 1]
        val right = b[i * 4 + 2]
        val bottom = b[i * 4 + 3]
        if (right <= left || bottom <= top) continue
        if (x < left || x > right) continue
        val reach = max(0f, minOf(halfWidth, (bottom - top) / 2))
        if (y >= top - reach && y <= bottom + reach) return i
    }
    return -1
}

/** Points along the stroke no more than [STEP] apart, as x, y pairs. */
private fun sample(xs: FloatArray, ys: FloatArray): FloatArray {
    val out = mutableListOf(xs[0], ys[0])
    for (i in 1 until xs.size) {
        val dx = xs[i] - xs[i - 1]
        val dy = ys[i] - ys[i - 1]
        val steps = ceil(hypot(dx, dy) / STEP).toInt().coerceAtLeast(1)
        for (k in 1..steps) {
            out += xs[i - 1] + dx * k / steps
            out += ys[i - 1] + dy * k / steps
        }
    }
    return out.toFloatArray()
}

private const val STEP = 2f
