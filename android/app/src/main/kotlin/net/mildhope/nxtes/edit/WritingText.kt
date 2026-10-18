package net.mildhope.nxtes.edit

import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.page.PagePainter
import net.mildhope.nxtes.page.PageResources
import java.util.UUID
import kotlin.math.abs

/** Handwriting turned into text gets a font size this share of its line height. */
private const val TEXT_PER_LINE = 0.75f
private val TEXT_SIZES = 6f..96f

/** Writing this many line heights past the end of a text box's last line still carries it on. */
private const val SAME_LINE_GAP = 4f

/** Writing that starts the next line may sit this many line heights from the box's left edge. */
private const val INDENT = 3f

/**
 * A text box saying [text] where [writing] is, in [font] about as big as the writing's lines and in
 * its main color. It's as wide as the writing or its text, whichever is wider, up to the page edge.
 */
fun textBoxFor(writing: List<InkStroke>, text: String, font: String, pageWidth: Float, resources: PageResources): TextBox {
    val boxes = writing.map { it.bounds }
    val area = boxes.reduce(Box::union)
    val lineHeight = writingLines(boxes).map { line -> line.map { boxes[it] }.reduce(Box::union).height }.sorted().let { it[it.size / 2] }
    val color = writing.groupingBy { it.color }.eachCount().maxBy { it.value }.key
    val box = TextBox(
        UUID.randomUUID().toString(), text, area.left, area.top, area.width, 0f,
        font = font, size = (lineHeight * TEXT_PER_LINE).coerceIn(TEXT_SIZES), color = color,
    )
    return fitted(box, (pageWidth - area.left).coerceAtLeast(area.width), resources)
}

/**
 * [box] with [text] added when [writing] carries on its last line (after a space) or starts the line
 * below it (after a line break); null when the writing is somewhere else.
 */
fun continueText(box: TextBox, writing: List<InkStroke>, text: String, pageWidth: Float, resources: PageResources): TextBox? {
    if (box.rotation != 0f || box.text.isEmpty()) return null
    val area = writing.map { it.bounds }.reduce(Box::union)
    val layout = PagePainter.textLayout(box, resources)
    val last = layout.lineCount - 1
    val top = box.y + layout.getLineTop(last)
    val bottom = box.y + layout.getLineBottom(last)
    val end = box.x + layout.getLineRight(last)
    val line = bottom - top
    val joined = when {
        area.centerY in top - line / 2..bottom + line / 2 && area.left in end - line..end + line * SAME_LINE_GAP -> "${box.text} $text"
        area.top in bottom - line / 2..bottom + line * 2 && abs(area.left - box.x) <= line * INDENT -> "${box.text}\n$text"
        else -> return null
    }
    return fitted(box.copy(text = joined), (pageWidth - box.x).coerceAtLeast(box.width), resources)
}

/** [box] widened to fit its text, up to [room], with the height its lines need. */
private fun fitted(box: TextBox, room: Float, resources: PageResources): TextBox {
    val natural = PagePainter.textLayout(box.copy(width = room), resources).let { layout -> (0 until layout.lineCount).maxOf { layout.getLineWidth(it) } }
    val sized = box.copy(width = (natural + 1f).coerceIn(box.width, room))
    return sized.copy(height = PagePainter.textLayout(sized, resources).height.toFloat().coerceAtLeast(sized.size))
}
