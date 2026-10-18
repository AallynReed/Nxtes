package net.mildhope.nxtes.viewer

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.edit.LassoType
import net.mildhope.nxtes.ink.EraserMode
import net.mildhope.nxtes.ink.LassoShape
import net.mildhope.nxtes.ink.InputMode
import net.mildhope.nxtes.ink.PenPreset
import net.mildhope.nxtes.ink.Tool
import net.mildhope.nxtes.ink.ToolState
import kotlin.math.roundToInt

private val BAR_HEIGHT = 52.dp

/** The writing view's one toolbar. See docs/ui.md for the layout. */
@Composable
fun WritingToolbar(
    tools: ToolState,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onPages: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSidePanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var settingsFor by remember { mutableStateOf<Int?>(null) }
    var eraserSettings by remember { mutableStateOf(false) }
    var viewSettings by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column {
            Box(Modifier.fillMaxWidth().height(BAR_HEIGHT)) {
                Row(Modifier.align(Alignment.CenterStart).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarButton(rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack), stringResource(R.string.back), onClick = onBack)
                    BarButton(painterResource(R.drawable.ic_grid_view), stringResource(R.string.pages), onClick = onPages)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 112.dp).horizontalScroll(rememberScrollState()),
                ) {
                    val penMode = tools.mode == InputMode.PEN
                    BarButton(
                        painter = if (penMode) rememberVectorPainter(Icons.Default.Edit) else painterResource(R.drawable.ic_pan_tool),
                        label = stringResource(if (penMode) R.string.pen_mode else R.string.gesture_mode),
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = tools::toggleMode,
                    )
                    ToolbarSeparator()
                    tools.presets.forEachIndexed { i, preset ->
                        val selected = penMode && tools.tool == Tool.PEN && tools.selected == i
                        Box {
                            PenStick(preset, selected) { if (selected) settingsFor = i else tools.selectPen(i) }
                            DropdownMenu(expanded = settingsFor == i, onDismissRequest = { settingsFor = null }) {
                                PenSettings(
                                    preset,
                                    onChange = { tools.updatePen(i, it) },
                                    onDelete = if (tools.presets.size > 1) {
                                        {
                                            settingsFor = null
                                            tools.removePen(i)
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                    if (tools.presets.size < ToolState.MAX_PENS) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable(onClickLabel = stringResource(R.string.add_pen), role = Role.Button, onClick = tools::addPen),
                        ) {
                            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                    ToolbarSeparator()
                    val erasing = penMode && tools.tool == Tool.ERASER
                    Box {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (erasing) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable(onClickLabel = stringResource(R.string.eraser), role = Role.Button) {
                                    if (erasing) eraserSettings = true else tools.selectEraser()
                                },
                        ) { EraserGlyph(if (erasing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        DropdownMenu(expanded = eraserSettings, onDismissRequest = { eraserSettings = false }) {
                            EraserSettings(tools)
                        }
                    }
                    ToolSlot(
                        selected = penMode && tools.tool == Tool.LASSO,
                        label = stringResource(R.string.lasso),
                        onSelect = { tools.selectTool(Tool.LASSO) },
                        glyph = { LassoGlyph(it) },
                    ) { LassoSettings(tools) }
                }
                Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarButton(painterResource(R.drawable.ic_undo), stringResource(R.string.undo), enabled = canUndo, onClick = onUndo)
                    BarButton(painterResource(R.drawable.ic_redo), stringResource(R.string.redo), enabled = canRedo, onClick = onRedo)
                    Box {
                        BarButton(rememberVectorPainter(Icons.Default.Settings), stringResource(R.string.view_settings)) { viewSettings = true }
                        DropdownMenu(expanded = viewSettings, onDismissRequest = { viewSettings = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.use_stylus_only)) },
                                trailingIcon = { Checkbox(checked = !tools.drawWithFinger, onCheckedChange = null) },
                                onClick = tools::toggleDrawWithFinger,
                            )
                        }
                    }
                    BarButton(painterResource(R.drawable.ic_menu_open), stringResource(R.string.side_panel), onClick = onSidePanel)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun BarButton(
    painter: Painter,
    label: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painter, label, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ToolbarSeparator() {
    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** An upright pen in its colour; the selected pen sits raised out of the toolbar edge. */
@Composable
private fun PenStick(preset: PenPreset, selected: Boolean, onClick: () -> Unit) {
    val lift by animateDpAsState(if (selected) 6.dp else 16.dp, label = "pen lift")
    val outline = MaterialTheme.colorScheme.outline
    val body = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(
        Modifier
            .width(30.dp)
            .height(BAR_HEIGHT)
            .clickable(onClickLabel = stringResource(penName(preset.pen)), role = Role.Button, onClick = onClick),
    ) { drawPen(preset, lift, outline, body) }
}

private fun DrawScope.drawPen(preset: PenPreset, lift: Dp, outline: Color, body: Color) {
    val color = Color(preset.color).copy(alpha = 1f)
    val wide = preset.pen == Pen.HIGHLIGHTER || preset.pen == Pen.MARKER
    val bodyWidth = (if (wide) 16.dp else 12.dp).toPx()
    val left = (size.width - bodyWidth) / 2
    val right = left + bodyWidth
    val top = lift.toPx()
    val tipHeight = 10.dp.toPx()
    val bandTop = top + tipHeight
    val bandHeight = 4.dp.toPx()
    val tip = Path().apply {
        if (wide) {
            moveTo(left + bodyWidth * 0.2f, top)
            lineTo(right - bodyWidth * 0.2f, top + tipHeight * 0.3f)
            lineTo(right, bandTop)
            lineTo(left, bandTop)
        } else {
            moveTo(size.width / 2, top)
            lineTo(right, bandTop)
            lineTo(left, bandTop)
        }
        close()
    }
    if (preset.pen == Pen.PENCIL) {
        drawPath(tip, Color(0xFFE8C9A0))
        val lead = Path().apply {
            moveTo(size.width / 2, top)
            lineTo(size.width / 2 + bodyWidth * 0.18f, top + tipHeight * 0.38f)
            lineTo(size.width / 2 - bodyWidth * 0.18f, top + tipHeight * 0.38f)
            close()
        }
        drawPath(lead, color)
    } else {
        drawPath(tip, color)
    }
    if (preset.pen == Pen.FOUNTAIN) {
        drawLine(body, Offset(size.width / 2, top + tipHeight * 0.35f), Offset(size.width / 2, bandTop), 1.dp.toPx())
    }
    drawRect(color, Offset(left, bandTop), Size(bodyWidth, bandHeight))
    val bodyTop = bandTop + bandHeight
    val bodySize = Size(bodyWidth, size.height - bodyTop + 4f)
    drawRect(body, Offset(left, bodyTop), bodySize)
    drawRect(outline, Offset(left, bodyTop), bodySize, style = Stroke(1.dp.toPx()))
}

/** A tool button that selects its tool, or opens its settings when it's already selected. */
@Composable
private fun ToolSlot(
    selected: Boolean,
    label: String,
    onSelect: () -> Unit,
    glyph: @Composable (Color) -> Unit,
    settings: (@Composable () -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .clickable(onClickLabel = label, role = Role.Button) { if (selected && settings != null) open = true else onSelect() },
        ) { glyph(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        if (settings != null) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) { settings() }
        }
    }
}

@Composable
private fun LassoGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))
        drawOval(color, Offset(size.width * 0.08f, size.height * 0.08f), Size(size.width * 0.84f, size.height * 0.6f), style = Stroke(2.dp.toPx(), pathEffect = dash))
        val tail = Path().apply {
            moveTo(size.width * 0.3f, size.height * 0.66f)
            quadraticTo(size.width * 0.2f, size.height * 0.85f, size.width * 0.38f, size.height * 0.95f)
        }
        drawPath(tail, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun LassoSettings(tools: ToolState) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(280.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LassoShape.entries.forEach { shape ->
                FilterChip(
                    selected = tools.lassoShape == shape,
                    onClick = { tools.updateLasso(shape, tools.lassoTypes, tools.autoDeselect) },
                    label = { Text(stringResource(if (shape == LassoShape.FREE) R.string.lasso_free else R.string.lasso_rect)) },
                )
            }
        }
        Text(stringResource(R.string.lasso_picks), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        LassoType.entries.forEach { type ->
            val on = type in tools.lassoTypes
            SettingSwitch(stringResource(lassoTypeName(type)), on) {
                tools.updateLasso(tools.lassoShape, if (on) tools.lassoTypes - type else tools.lassoTypes + type, tools.autoDeselect)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SettingSwitch(stringResource(R.string.auto_deselect), tools.autoDeselect) {
            tools.updateLasso(tools.lassoShape, tools.lassoTypes, !tools.autoDeselect)
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

private fun lassoTypeName(type: LassoType) = when (type) {
    LassoType.PEN -> R.string.lasso_type_pen
    LassoType.HIGHLIGHTER -> R.string.lasso_type_highlighter
    LassoType.MASK -> R.string.lasso_type_mask
    LassoType.SHAPE -> R.string.lasso_type_shape
    LassoType.IMAGE -> R.string.lasso_type_image
    LassoType.TEXT -> R.string.lasso_type_text
    LassoType.NOTE -> R.string.lasso_type_note
}

@Composable
private fun EraserGlyph(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        rotate(-40f) {
            val body = Size(size.width * 0.8f, size.height * 0.42f)
            val topLeft = Offset((size.width - body.width) / 2, (size.height - body.height) / 2)
            drawRoundRect(color, topLeft, body, CornerRadius(6f), style = Stroke(4f))
            drawLine(color, Offset(topLeft.x + body.width * 0.4f, topLeft.y), Offset(topLeft.x + body.width * 0.4f, topLeft.y + body.height), 4f)
        }
    }
}

/** A short wavy stroke previewing a pen's colour and size. */
@Composable
private fun PenPreview(preset: PenPreset, modifier: Modifier = Modifier) {
    val color = Color(preset.color)
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.1f, size.height * 0.65f)
            cubicTo(size.width * 0.35f, size.height * 0.1f, size.width * 0.6f, size.height * 0.95f, size.width * 0.9f, size.height * 0.35f)
        }
        val width = (preset.size / preset.sizeRange.endInclusive * size.height * 0.5f).coerceAtLeast(2f)
        val cap = if (preset.pen == Pen.HIGHLIGHTER || preset.pen == Pen.MARKER) StrokeCap.Butt else StrokeCap.Round
        drawPath(path, color, style = Stroke(width, cap = cap))
    }
}

@Composable
private fun PenSettings(preset: PenPreset, onChange: (PenPreset) -> Unit, onDelete: (() -> Unit)?) {
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
        PenPreview(preset, Modifier.fillMaxWidth().height(48.dp))
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete_pen), color = MaterialTheme.colorScheme.error) }
        }
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
