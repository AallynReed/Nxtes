package net.mildhope.nxtes.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.ink.ToolState
import kotlin.math.roundToInt

/** A menu card centred above [anchor] (screen pixels), [gap] clear of it, or below when there's no room. */
@Composable
fun AnchoredMenu(anchor: Rect, gap: Dp, content: @Composable () -> Unit) {
    Layout(content = { Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp, content = content) }) { measurables, constraints ->
        val card = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val margin = 8.dp.roundToPx()
            val x = (anchor.center.x - card.width / 2f).roundToInt()
                .coerceIn(margin, (constraints.maxWidth - card.width - margin).coerceAtLeast(margin))
            val above = (anchor.top - gap.toPx() - card.height).roundToInt()
            val below = (anchor.bottom + margin).roundToInt()
            val y = (if (above >= margin) above else below).coerceIn(margin, (constraints.maxHeight - card.height - margin).coerceAtLeast(margin))
            card.place(x, y)
        }
    }
}

/** Colours and actions for the lasso selection. */
@Composable
fun SelectionMenu(
    anchor: Rect,
    actions: SelectionActions,
    extra: @Composable RowScope.() -> Unit = {},
) {
    AnchoredMenu(anchor, gap = 56.dp) {
        Column(Modifier.width(IntrinsicSize.Max).padding(6.dp)) {
            if (actions.canRecolour) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(6.dp)) {
                    ToolState.PALETTE.forEach { colour ->
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(colour))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { actions.recolour(colour) },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                extra()
                MenuIcon(painterResource(R.drawable.ic_copy), stringResource(R.string.copy), onClick = actions::copy)
                MenuIcon(painterResource(R.drawable.ic_cut), stringResource(R.string.cut), onClick = actions::cut)
                if (actions.canReorder) {
                    MenuIcon(painterResource(R.drawable.ic_to_front), stringResource(R.string.bring_to_front)) { actions.reorder(toFront = true) }
                    MenuIcon(painterResource(R.drawable.ic_to_back), stringResource(R.string.send_to_back)) { actions.reorder(toFront = false) }
                }
                MenuIcon(rememberVectorPainter(Icons.Default.Delete), stringResource(R.string.delete), MaterialTheme.colorScheme.error, actions::delete)
            }
        }
    }
}

@Composable
fun MenuIcon(painter: Painter, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(painter, label, tint = tint, modifier = Modifier.size(22.dp)) }
}

/** What a touch and hold on an empty spot offers. */
@Composable
fun PageMenu(anchor: Rect, items: List<Pair<String, () -> Unit>>) {
    AnchoredMenu(anchor, gap = 16.dp) {
        Row(Modifier.padding(horizontal = 4.dp)) {
            items.forEach { (label, action) -> TextButton(onClick = action) { Text(label) } }
        }
    }
}
