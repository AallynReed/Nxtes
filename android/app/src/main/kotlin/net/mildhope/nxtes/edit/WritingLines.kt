package net.mildhope.nxtes.edit

import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.Pen
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Whether the stroke is writing: ink from any pen but the highlighter. */
val InkStroke.isWriting get() = pen.inks && pen != Pen.HIGHLIGHTER

/** The box around the stroke, counting its width. */
val InkStroke.bounds get() = Box.of(x, y, size / 2)

/** The height most of [boxes] have, to judge the others against. */
fun typicalHeight(boxes: List<Box>): Float = boxes.map { it.height }.sorted()[boxes.size / 2]

/**
 * [boxes] sorted into lines of writing, top to bottom, as indices. Each line grows from its tallest
 * stroke and takes the strokes whose middle is level with that one; pieces of a line that drifts up
 * or down are joined back up. Dots, dashes and outsized strokes such as brackets go to the line
 * whose middle is nearest instead of starting one.
 */
fun writingLines(boxes: List<Box>): List<List<Int>> {
    if (boxes.isEmpty()) return emptyList()
    val typical = typicalHeight(boxes)
    val (ordinary, odd) = boxes.indices.partition { i ->
        val b = boxes[i]
        b.height <= typical * OUTSIZED && (b.width >= typical * MARK || b.height >= typical * MARK)
    }
    val lines = mutableListOf<Line>()
    for (i in ordinary.sortedByDescending { boxes[it].height }) {
        val b = boxes[i]
        val line = lines.filter { it.level(b.centerY) }.minByOrNull { abs(it.seed.centerY - b.centerY) }
        (line ?: Line(b).also { lines += it }).add(i, b)
    }
    joinDrifts(lines)
    if (lines.isEmpty()) return listOf(odd)
    val middles = lines.map { it.middle() }
    for (i in odd) lines[middles.indices.minBy { abs(middles[it] - boxes[i].centerY) }].add(i, boxes[i])
    return lines.zip(middles).sortedBy { it.second }.map { it.first.members }
}

private class Line(val seed: Box) {
    val members = mutableListOf<Int>()
    val centers = mutableListOf<Float>()
    var box = seed

    fun level(y: Float) = y >= seed.top - seed.height * LEVEL && y <= seed.bottom + seed.height * LEVEL

    fun add(i: Int, b: Box) {
        members += i
        centers += b.centerY
        box = box.union(b)
    }

    fun absorb(other: Line) {
        members += other.members
        centers += other.centers
        box = box.union(other.box)
    }

    fun middle() = centers.sorted()[centers.size / 2]
}

/** Joins lines that sit side by side at about the same height, as a line written on a slope splits up. */
private fun joinDrifts(lines: MutableList<Line>) {
    var joined = true
    while (joined) {
        joined = false
        loop@ for (a in lines.indices) for (b in lines.indices) {
            if (a == b) continue
            val x = lines[a].box
            val y = lines[b].box
            val across = min(x.right, y.right) - max(x.left, y.left)
            val level = min(x.bottom, y.bottom) - max(x.top, y.top)
            val height = max(lines[a].seed.height, lines[b].seed.height)
            if (across <= min(x.width, y.width) * SIDE_BY_SIDE && -across <= height * DRIFT_GAP && level >= min(x.height, y.height) * DRIFT_LEVEL) {
                lines[a].absorb(lines[b])
                lines.removeAt(b)
                joined = true
                break@loop
            }
        }
    }
}

/** Strokes this many times taller than most aren't letters. */
private const val OUTSIZED = 4f

/** Strokes smaller than this share of most strokes' height, both ways, are marks like dots. */
private const val MARK = 0.5f

/** How far past a line's tallest stroke, in its height, a stroke's middle can be and still be level. */
private const val LEVEL = 0.1f

/** Lines overlapping by at most this share of the narrower one count as side by side. */
private const val SIDE_BY_SIDE = 0.2f

/** The widest gap, in line heights, between pieces of one drifting line. */
private const val DRIFT_GAP = 3f

/** Pieces of one drifting line share at least this much of the shorter one's height. */
private const val DRIFT_LEVEL = 0.5f
