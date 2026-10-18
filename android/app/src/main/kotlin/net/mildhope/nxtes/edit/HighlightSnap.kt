package net.mildhope.nxtes.edit

import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.Pen
import kotlin.math.abs
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

/**
 * The lines of handwriting in [strokes] that a highlighter stroke runs over, as one box per line
 * around the strokes it touches there, so it can be straightened over them as on PDF text. Null when
 * less than half the stroke lies over writing, so it stays as drawn. [width] is the stroke's width.
 */
fun snapToWriting(strokes: List<InkStroke>, xs: FloatArray, ys: FloatArray, width: Float): List<Box>? {
    if (xs.isEmpty()) return null
    val path = Box.of(xs, ys, width)
    val boxes = strokes.filter { it.isWriting }.map { it.bounds }
        .filter { it.right >= path.left && it.left <= path.right && it.bottom >= path.top && it.top <= path.bottom }
    if (boxes.isEmpty()) return null
    val tallest = typicalHeight(boxes) * OUTSIZED_LETTER
    val samples = sample(xs, ys)
    val hits = IntArray(boxes.size)
    var over = 0
    for (k in samples.indices step 2) {
        val x = samples[k]
        val y = samples[k + 1]
        val near = boxes.indices.filter { val b = boxes[it]; b.height <= tallest && b.inflate(minOf(width / 2, b.height / 2)).contains(x, y) }
        if (near.isEmpty()) continue
        over++
        near.filter { x >= boxes[it].left && x <= boxes[it].right }.minByOrNull { abs(y - boxes[it].centerY) }?.let { hits[it]++ }
    }
    if (over * 2 < samples.size / 2) return null
    val touched = boxes.indices.filter { hits[it] > 0 }.ifEmpty { return null }
    val lines = writingLines(touched.map { boxes[it] }).map { line -> line.map { touched[it] } }
    val most = lines.maxOf { line -> line.sumOf { hits[it] } }
    return lines.filter { line -> line.sumOf { hits[it] } * 4 >= most }.map { line -> line.map { boxes[it] }.reduce(Box::union) }
}

/** Straight highlighter strokes in [stroke]'s color, each filling one of [bands]. */
fun straightHighlights(stroke: InkStroke, bands: List<Box>, newId: () -> String): List<InkStroke> = bands.map { b ->
    val size = b.height * BAND_PADDING
    val inset = minOf(size * TIP_INSET, b.width / 2)
    InkStroke(
        id = newId(), pen = Pen.HIGHLIGHTER, color = stroke.color, size = size, tool = stroke.tool,
        noiseSeed = stroke.noiseSeed, created = stroke.created,
        x = floatArrayOf(b.left + inset, b.right - inset), y = floatArrayOf(b.centerY, b.centerY),
        t = intArrayOf(0, stroke.t.last().coerceAtLeast(1)),
    )
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

/** Strokes this many times taller than most, like a box drawn round a paragraph, aren't highlighted. */
private const val OUTSIZED_LETTER = 4f

/** A straightened highlight is this much taller than the writing under it. */
private const val BAND_PADDING = 1.1f

/** How far, in its width, a straightened highlight's ends sit inside the writing, as its slanted tip reaches past them. */
private const val TIP_INSET = 0.15f
