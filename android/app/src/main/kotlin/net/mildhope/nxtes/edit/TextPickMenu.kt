package net.mildhope.nxtes.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.MarkKind
import net.mildhope.nxtes.data.TextMark
import net.mildhope.nxtes.viewer.PageMapper

/** Highlighter colours for PDF text; they multiply with the page. */
val HIGHLIGHT_COLORS = listOf(0xFFFFF176, 0xFFA5D6A7, 0xFF90CAF9, 0xFFF48FB1, 0xFFFFCC80).map { it.toInt() }

/** Underlines and strike-throughs are drawn in this colour. */
const val TEXT_LINE_COLOR = 0xFFE53935.toInt()

/** What picked PDF text offers. */
class TextPickActions(
    val mark: (MarkKind, Int) -> Unit,
    val copy: () -> Unit,
    val lookUp: () -> Unit,
    val share: () -> Unit,
    val bookmark: () -> Unit,
    val outline: () -> Unit,
)

/** Screen rectangle around page-point [rects] (x, y, width, height each) on page [index]. */
private fun PageMapper.around(index: Int, rects: FloatArray): Rect {
    var box = Rect(mapper(index, rects[0], rects[1]), mapper(index, rects[0] + rects[2], rects[1] + rects[3]))
    for (i in 4 until rects.size step 4) {
        box = box.union(Rect(mapper(index, rects[i], rects[i + 1]), mapper(index, rects[i] + rects[i + 2], rects[i + 1] + rects[i + 3])))
    }
    return box
}

private fun Rect.union(o: Rect) = Rect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

private fun PageMapper.mapper(index: Int, x: Float, y: Float) = toScreen(index, Offset(x, y))

/** Highlight colours and actions over picked PDF text. */
@Composable
fun TextPickMenu(pick: TextPick, mapper: PageMapper, actions: TextPickActions) {
    val index = mapper.indexOf(pick.pageId).takeIf { it >= 0 } ?: return
    if (pick.rects.isEmpty()) return
    AnchoredMenu(mapper.around(index, pick.rects), gap = 16.dp) {
        Column(Modifier.widthIn(max = 520.dp).padding(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                Text(stringResource(R.string.highlight), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.size(4.dp))
                HIGHLIGHT_COLORS.forEach { colour ->
                    Spacer(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(colour))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable(onClickLabel = stringResource(R.string.highlight)) { actions.mark(MarkKind.HIGHLIGHT, colour) },
                    )
                }
            }
            FlowRow {
                TextButton(onClick = { actions.mark(MarkKind.UNDERLINE, TEXT_LINE_COLOR) }) { Text(stringResource(R.string.underline)) }
                TextButton(onClick = { actions.mark(MarkKind.STRIKEOUT, TEXT_LINE_COLOR) }) { Text(stringResource(R.string.strike_through)) }
                TextButton(onClick = actions.copy) { Text(stringResource(R.string.copy)) }
                TextButton(onClick = actions.lookUp) { Text(stringResource(R.string.look_up)) }
                TextButton(onClick = actions.share) { Text(stringResource(R.string.share)) }
                TextButton(onClick = actions.bookmark) { Text(stringResource(R.string.add_bookmark)) }
                TextButton(onClick = actions.outline) { Text(stringResource(R.string.add_outline)) }
            }
        }
    }
}

/** Copy or delete a highlight, underline or strike-through. */
@Composable
fun MarkMenu(pageId: String, mark: TextMark, mapper: PageMapper, onCopy: () -> Unit, onRecolour: (Int) -> Unit, onDelete: () -> Unit) {
    val index = mapper.indexOf(pageId).takeIf { it >= 0 } ?: return
    if (mark.rects.isEmpty()) return
    AnchoredMenu(mapper.around(index, mark.rects), gap = 16.dp) {
        Column(Modifier.padding(6.dp)) {
            if (mark.kind == MarkKind.HIGHLIGHT) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(6.dp)) {
                    HIGHLIGHT_COLORS.forEach { colour ->
                        Spacer(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(colour))
                                .border(if (colour == mark.color) 2.dp else 1.dp, if (colour == mark.color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable(onClickLabel = stringResource(R.string.highlight)) { onRecolour(colour) },
                        )
                    }
                }
            }
            Row {
                TextButton(onClick = onCopy) { Text(stringResource(R.string.copy)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
