package net.mildhope.nxtes.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.StickyNote
import net.mildhope.nxtes.ink.ToolState
import net.mildhope.nxtes.page.PagePainter
import net.mildhope.nxtes.viewer.PageMapper
import java.text.DateFormat
import java.util.Date

private val NOTE_INK = Color(0xFF202124)

/** A sticky note opened next to its icon: its text on its colour, with colours and delete above. */
@Composable
fun NoteEditor(edit: NoteEdit, mapper: PageMapper, onChange: (StickyNote) -> Unit, onDelete: () -> Unit, onDone: () -> Unit) {
    val index = mapper.indexOf(edit.pageId).takeIf { it >= 0 } ?: return
    val note = edit.note
    val side = PagePainter.NOTE_SIZE * mapper.pixelsPerPoint
    val anchor = Rect(mapper.toScreen(index, Offset(note.x, note.y)), Size(side, side))
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().imePadding()) {
    AnchoredMenu(anchor, gap = 8.dp) {
        Column(Modifier.width(300.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolState.NOTE_COLORS.forEach { colour ->
                        val selected = colour == note.color
                        Spacer(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(colour))
                                .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable(onClickLabel = stringResource(R.string.note_colour)) { onChange(note.copy(color = colour)) },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                MenuIcon(rememberVectorPainter(Icons.Default.Delete), stringResource(R.string.delete), MaterialTheme.colorScheme.error, onDelete)
                TextButton(onClick = onDone) { Text(stringResource(R.string.done)) }
            }
            BasicTextField(
                value = note.text,
                onValueChange = { onChange(note.copy(text = it)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = NOTE_INK),
                cursorBrush = SolidColor(NOTE_INK),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 320.dp)
                    .background(Color(note.color))
                    .padding(12.dp)
                    .focusRequester(focus),
            )
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.created)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    }
    LaunchedEffect(note.id) { if (edit.isNew) focus.requestFocus() }
}
