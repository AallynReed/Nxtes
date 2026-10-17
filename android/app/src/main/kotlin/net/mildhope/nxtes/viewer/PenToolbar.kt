package net.mildhope.nxtes.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.ink.EraserMode
import net.mildhope.nxtes.ink.PenPreset
import net.mildhope.nxtes.ink.Tool
import net.mildhope.nxtes.ink.ToolState
import kotlin.math.roundToInt

/** Pen presets and the eraser. Tap a selected tool again for its settings. */
@Composable
fun PenToolbar(tools: ToolState, modifier: Modifier = Modifier) {
    var settingsFor by remember { mutableStateOf<Int?>(null) }
    var eraserSettings by remember { mutableStateOf(false) }
    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            tools.presets.forEachIndexed { i, preset ->
                val selected = tools.tool == Tool.PEN && tools.selected == i
                Box {
                    ToolButton(selected, stringResource(penName(preset.pen)), onClick = {
                        if (selected) settingsFor = i else tools.selectPen(i)
                    }) { PenGlyph(preset, Modifier.size(32.dp)) }
                    DropdownMenu(expanded = settingsFor == i, onDismissRequest = { settingsFor = null }) {
                        PenSettings(preset, onChange = { tools.updatePen(i, it) })
                    }
                }
            }
            val erasing = tools.tool == Tool.ERASER
            Box {
                ToolButton(erasing, stringResource(R.string.eraser), onClick = {
                    if (erasing) eraserSettings = true else tools.selectEraser()
                }) { EraserGlyph() }
                DropdownMenu(expanded = eraserSettings, onDismissRequest = { eraserSettings = false }) {
                    EraserSettings(tools)
                }
            }
        }
    }
}

@Composable
private fun ToolButton(selected: Boolean, label: String, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
    ) { glyph() }
}

@Composable
private fun PenGlyph(preset: PenPreset, modifier: Modifier = Modifier) {
    val color = Color(preset.color)
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.1f, size.height * 0.65f)
            cubicTo(size.width * 0.35f, size.height * 0.1f, size.width * 0.6f, size.height * 0.95f, size.width * 0.9f, size.height * 0.35f)
        }
        val width = (preset.size / preset.sizeRange.endInclusive * size.height * 0.5f).coerceAtLeast(2f)
        drawPath(path, color, style = Stroke(width, cap = if (preset.pen == Pen.HIGHLIGHTER || preset.pen == Pen.MARKER) StrokeCap.Butt else StrokeCap.Round))
    }
}

@Composable
private fun EraserGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(28.dp)) {
        rotate(-40f) {
            val body = Size(size.width * 0.8f, size.height * 0.42f)
            val topLeft = Offset((size.width - body.width) / 2, (size.height - body.height) / 2)
            drawRoundRect(color, topLeft, body, CornerRadius(6f), style = Stroke(4f))
            drawLine(color, Offset(topLeft.x + body.width * 0.4f, topLeft.y), Offset(topLeft.x + body.width * 0.4f, topLeft.y + body.height), 4f)
        }
    }
}

@Composable
private fun PenSettings(preset: PenPreset, onChange: (PenPreset) -> Unit) {
    Column(Modifier.padding(16.dp).width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pen.entries.forEach { pen ->
                FilterChip(
                    selected = preset.pen == pen,
                    onClick = {
                        val alpha = if (pen == Pen.HIGHLIGHTER) 0x80 else 0xFF
                        onChange(preset.copy(pen = pen, color = withAlpha(preset.color, alpha)))
                    },
                    label = { Text(stringResource(penName(pen))) },
                )
            }
        }
        ToolState.PALETTE.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { swatch ->
                    val chosen = (swatch and 0xFFFFFF) == (preset.color and 0xFFFFFF)
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(swatch))
                            .border(if (chosen) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { onChange(preset.copy(color = withAlpha(swatch, preset.color ushr 24))) },
                    )
                }
            }
        }
        LabelledSlider(
            label = stringResource(R.string.pen_size, preset.size),
            value = preset.size,
            range = preset.sizeRange,
            onChange = { onChange(preset.copy(size = it)) },
        )
        LabelledSlider(
            label = stringResource(R.string.pen_opacity, ((preset.color ushr 24) * 100 / 255f).roundToInt()),
            value = (preset.color ushr 24).toFloat(),
            range = 25f..255f,
            onChange = { onChange(preset.copy(color = withAlpha(preset.color, it.roundToInt()))) },
        )
        PenGlyph(preset, Modifier.fillMaxWidth().height(48.dp))
    }
}

@Composable
private fun EraserSettings(tools: ToolState) {
    Column(Modifier.padding(16.dp).width(280.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EraserMode.entries.forEach { mode ->
                FilterChip(
                    selected = tools.eraserMode == mode,
                    onClick = { tools.updateEraser(mode, tools.eraserRadiusDp) },
                    label = {
                        Text(stringResource(if (mode == EraserMode.STROKE) R.string.eraser_stroke else R.string.eraser_partial))
                    },
                )
            }
        }
        LabelledSlider(
            label = stringResource(R.string.eraser_size, tools.eraserRadiusDp.roundToInt()),
            value = tools.eraserRadiusDp,
            range = 4f..40f,
            onChange = { tools.updateEraser(tools.eraserMode, it) },
        )
    }
}

@Composable
private fun LabelledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

private fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or (alpha.coerceIn(0, 255) shl 24)

private fun penName(pen: Pen) = when (pen) {
    Pen.BALLPOINT -> R.string.pen_ballpoint
    Pen.FOUNTAIN -> R.string.pen_fountain
    Pen.PENCIL -> R.string.pen_pencil
    Pen.MARKER -> R.string.pen_marker
    Pen.HIGHLIGHTER -> R.string.pen_highlighter
}
