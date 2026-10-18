package net.mildhope.nxtes.edit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A PDF page's characters, with a box per character in page points: left, top, right, bottom.
 * Characters PDFium makes up, like line breaks, have empty boxes.
 */
class PageText(val text: String, val boxes: FloatArray) {
    val length get() = text.length

    private fun empty(i: Int) = boxes[i * 4 + 2] <= boxes[i * 4] || boxes[i * 4 + 3] <= boxes[i * 4 + 1]

    /** The character under the point, or the nearest one within [reach]; -1 if there's none. */
    fun charAt(x: Float, y: Float, reach: Float): Int {
        var best = -1
        var bestDistance = reach
        for (i in 0 until length) {
            if (empty(i)) continue
            val dx = max(0f, max(boxes[i * 4] - x, x - boxes[i * 4 + 2]))
            val dy = max(0f, max(boxes[i * 4 + 1] - y, y - boxes[i * 4 + 3]))
            if (dx == 0f && dy == 0f) return i
            val d = dx + dy
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /** The word around character [i]: the run of letters and digits it's in, or just it. */
    fun wordAt(i: Int): IntRange {
        if (!text[i].isLetterOrDigit()) return i..i
        var start = i
        var end = i
        while (start > 0 && text[start - 1].isLetterOrDigit()) start--
        while (end < length - 1 && text[end + 1].isLetterOrDigit()) end++
        return start..end
    }

    fun textOf(start: Int, end: Int) = text.substring(start, end + 1).replace("\r\n", "\n").trim()

    /** One rectangle per line over characters [start] to [end]: x, y, width, height for each. */
    fun rects(start: Int, end: Int): FloatArray {
        val out = mutableListOf<Float>()
        var l = 0f
        var t = 0f
        var r = 0f
        var b = 0f
        var open = false
        fun close() {
            if (open) out += listOf(l, t, r - l, b - t)
            open = false
        }
        for (i in start..end) {
            if (empty(i)) continue
            val bl = boxes[i * 4]
            val bt = boxes[i * 4 + 1]
            val br = boxes[i * 4 + 2]
            val bb = boxes[i * 4 + 3]
            val middle = (bt + bb) / 2
            val sameLine = open && middle > t && middle < b && bl > l - (b - t) && abs(bl - r) < (b - t) * 4
            if (!sameLine) {
                close()
                l = bl; t = bt; r = br; b = bb
                open = true
            } else {
                l = min(l, bl); t = min(t, bt); r = max(r, br); b = max(b, bb)
            }
        }
        close()
        return out.toFloatArray()
    }

    /** The same text on a page turned clockwise by [turns] quarter turns; [width] and [height] are the unturned page's. */
    fun turned(turns: Int, width: Float, height: Float): PageText {
        val quarter = turns.mod(4)
        if (quarter == 0) return this
        val out = FloatArray(boxes.size)
        for (i in 0 until length) {
            val (x0, y0) = turn(quarter, boxes[i * 4], boxes[i * 4 + 1], width, height)
            val (x1, y1) = turn(quarter, boxes[i * 4 + 2], boxes[i * 4 + 3], width, height)
            out[i * 4] = min(x0, x1); out[i * 4 + 1] = min(y0, y1); out[i * 4 + 2] = max(x0, x1); out[i * 4 + 3] = max(y0, y1)
        }
        return PageText(text, out)
    }

    private fun turn(quarter: Int, x: Float, y: Float, w: Float, h: Float) = when (quarter) {
        1 -> h - y to x
        2 -> w - x to h - y
        else -> y to w - x
    }
}
