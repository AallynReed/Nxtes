package net.mildhope.nxtes.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Mask
import net.mildhope.nxtes.data.MaskKind
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.distance
import net.mildhope.nxtes.ink.PageSession
import net.mildhope.nxtes.viewer.PageMapper
import kotlin.math.abs

/** Makes masks from a drag, in page points. */
object MaskBuilder {
    /** Tape is never thinner than this many points; a shorter drag makes nothing. */
    private const val MIN_TAPE = 4f

    fun tape(id: String, x0: Float, y0: Float, x1: Float, y1: Float, colour: Int): Mask? {
        if (abs(x1 - x0) < MIN_TAPE || abs(y1 - y0) < MIN_TAPE) return null
        val l = minOf(x0, x1)
        val r = maxOf(x0, x1)
        val t = minOf(y0, y1)
        val b = maxOf(y0, y1)
        return Mask(id, MaskKind.TAPE, floatArrayOf(l, r, r, l), floatArrayOf(t, t, b, b), 0f, colour)
    }

    /** A pen mask along [points], keeping only points at least a tenth of [width] apart. */
    fun pen(id: String, points: List<Offset>, width: Float, colour: Int): Mask? {
        if (points.isEmpty()) return null
        val kept = mutableListOf(points.first())
        for (p in points.drop(1)) if (distance(kept.last().x, kept.last().y, p.x, p.y) >= width / 10) kept += p
        if (kept.size == 1) kept += kept.first()
        return Mask(id, MaskKind.PEN, kept.map { it.x }.toFloatArray(), kept.map { it.y }.toFloatArray(), width, colour)
    }
}

/** Right answers and tries over [masks]. */
fun score(masks: List<Mask>): Pair<Int, Int> = masks.sumOf { it.correct } to masks.sumOf { it.tries }

/** Counts an answer for a revealed mask. */
fun PageSession.answer(pageId: String, maskId: String, right: Boolean) = update(pageId) { c ->
    c.copy(masks = c.masks.map { if (it.id == maskId) it.copy(tries = it.tries + 1, correct = it.correct + if (right) 1 else 0) else it })
}

fun PageContent.mask(id: String) = masks.firstOrNull { it.id == id }

/** Asks whether the just-revealed mask's answer was right, next to it. */
@Composable
fun MaskMenu(pageId: String, mask: Mask, mapper: PageMapper, onAnswer: (right: Boolean) -> Unit) {
    val index = mapper.indexOf(pageId).takeIf { it >= 0 } ?: return
    val box = Geometry.bounds(mask)
    val anchor = Rect(mapper.toScreen(index, Offset(box.left, box.top)), mapper.toScreen(index, Offset(box.right, box.bottom)))
    AnchoredMenu(anchor, gap = 8.dp) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.mask_correct)) }
                TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.mask_wrong), color = MaterialTheme.colorScheme.error) }
            }
            if (mask.tries > 0) {
                Text(
                    stringResource(R.string.mask_score, mask.correct, mask.tries),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp),
                )
            }
        }
    }
}
