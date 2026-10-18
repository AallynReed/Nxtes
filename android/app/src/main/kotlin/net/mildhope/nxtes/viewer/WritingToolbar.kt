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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import net.mildhope.nxtes.R
import net.mildhope.nxtes.ui.ColorChooser
import net.mildhope.nxtes.edit.StickerPanel
import net.mildhope.nxtes.edit.importSticker
import net.mildhope.nxtes.edit.ref
import net.mildhope.nxtes.library.runLibraryAction
import net.mildhope.nxtes.data.LineStyle
import net.mildhope.nxtes.data.MaskKind
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.data.ShapeKind
import net.mildhope.nxtes.edit.LassoType
import net.mildhope.nxtes.ink.EraserMode
import net.mildhope.nxtes.ink.LaserMode
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
    view: ViewSettings,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onPages: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSidePanel: () -> Unit,
    onAddImage: (ImageSource) -> Unit,
    modifier: Modifier = Modifier,
    recordButton: @Composable () -> Unit = {},
    onExport: () -> Unit = {},
) {
    var settingsFor by remember { mutableStateOf<Int?>(null) }
    var eraserSettings by remember { mutableStateOf(false) }
    var viewSettings by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().height(BAR_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarButton(rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack), stringResource(R.string.back), onClick = onBack)
                    BarButton(painterResource(R.drawable.ic_grid_view), stringResource(R.string.pages), onClick = onPages)
                }
                val toolScroll = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxHeight().fadingEdges(toolScroll), contentAlignment = Alignment.Center) {
                if (view.readOnly) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer, onClick = view::toggleReadOnly) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                            Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.read_only_banner), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                } else Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(toolScroll).padding(horizontal = 8.dp),
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
                    ImageButton(onAddImage)
                    ToolSlot(
                        selected = penMode && tools.tool == Tool.TEXT,
                        label = stringResource(R.string.text_box),
                        onSelect = { tools.selectTool(Tool.TEXT) },
                        glyph = { Icon(painterResource(R.drawable.ic_text), null, tint = it, modifier = Modifier.size(24.dp)) },
                    )
                    ToolSlot(
                        selected = penMode && tools.tool == Tool.NOTE,
                        label = stringResource(R.string.sticky_note),
                        onSelect = { tools.selectTool(Tool.NOTE) },
                        glyph = { Icon(painterResource(R.drawable.ic_sticky_note), null, tint = it, modifier = Modifier.size(24.dp)) },
                    ) { NoteSettings(tools) }
                    StickerSlot(tools, selected = penMode && tools.tool == Tool.STICKER)
                    ToolSlot(
                        selected = penMode && tools.tool == Tool.MASK,
                        label = stringResource(R.string.masking),
                        onSelect = { tools.selectTool(Tool.MASK) },
                        glyph = { Icon(painterResource(R.drawable.ic_masking), null, tint = it, modifier = Modifier.size(24.dp)) },
                    ) { MaskSettings(tools) }
                    ToolSlot(
                        selected = penMode && tools.tool == Tool.LASER,
                        label = stringResource(R.string.laser),
                        onSelect = { tools.selectTool(Tool.LASER) },
                        glyph = { Icon(painterResource(R.drawable.ic_laser), null, tint = it, modifier = Modifier.size(24.dp)) },
                    ) { LaserSettings(tools) }
                }
                }
                Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarButton(painterResource(R.drawable.ic_share), stringResource(R.string.export), onClick = onExport)
                    recordButton()
                    BarButton(painterResource(R.drawable.ic_undo), stringResource(R.string.undo), enabled = canUndo && !view.readOnly, onClick = onUndo)
                    BarButton(painterResource(R.drawable.ic_redo), stringResource(R.string.redo), enabled = canRedo && !view.readOnly, onClick = onRedo)
                    Box {
                        BarButton(rememberVectorPainter(Icons.Default.Settings), stringResource(R.string.view_settings)) { viewSettings = true }
                        DropdownMenu(expanded = viewSettings, onDismissRequest = { viewSettings = false }) {
                            PageFlowSettings(view)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            listOf(
                                Triple(R.string.dark_pages, view.darkPages, view::toggleDarkPages),
                                Triple(R.string.keep_screen_on, view.keepScreenOn, view::toggleKeepScreenOn),
                                Triple(R.string.full_screen, view.fullScreen, view::toggleFullScreen),
                                Triple(R.string.hide_tabs, view.hideTabs, view::toggleHideTabs),
                            ).forEach { (label, checked, toggle) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    trailingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                                    onClick = toggle,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.use_stylus_only)) },
                                trailingIcon = { Checkbox(checked = !tools.drawWithFinger, onCheckedChange = null) },
                                onClick = tools::toggleDrawWithFinger,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.show_annotations)) },
                                trailingIcon = { Checkbox(checked = !tools.annotationsHidden, onCheckedChange = null) },
                                onClick = tools::toggleAnnotations,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.read_only)) },
                                trailingIcon = { Checkbox(checked = view.readOnly, onCheckedChange = null) },
                                onClick = view::toggleReadOnly,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                stringResource(R.string.shape_conversion),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                            ToolState.CONVERTIBLE.forEach { kind ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(convertName(kind))) },
                                    trailingIcon = { Checkbox(checked = kind in tools.convertShapes, onCheckedChange = null) },
                                    onClick = { tools.toggleConvert(kind) },
                                )
                            }
                        }
                    }
                    BarButton(painterResource(R.drawable.ic_menu_open), stringResource(R.string.side_panel), onClick = onSidePanel)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Fades the ends of a horizontally scrolled strip when there's more to scroll to that way. */
private fun Modifier.fadingEdges(scroll: ScrollState) = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    val w = FADE_WIDTH.toPx()
    if (scroll.canScrollBackward) {
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), endX = w), size = Size(w, size.height), blendMode = BlendMode.DstIn)
    }
    if (scroll.canScrollForward) {
        drawRect(
            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
            topLeft = Offset(size.width - w, 0f),
            size = Size(w, size.height),
            blendMode = BlendMode.DstIn,
        )
    }
}

private val FADE_WIDTH = 24.dp

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

/** An upright pen in its color; the selected pen sits raised out of the toolbar edge. */
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
    if (preset.pen == Pen.SHAPE) {
        val side = bodyWidth * 0.9f
        drawRect(color, Offset(size.width / 2 - side / 2, top), Size(side, side * 0.8f), style = Stroke(2.dp.toPx()))
    } else if (preset.pen == Pen.PENCIL) {
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

/** Where a new image comes from. */
enum class ImageSource { GALLERY, FILES, CAMERA }

/** Opens a menu to add an image from the gallery, files or the camera. */
@Composable
private fun ImageButton(onAdd: (ImageSource) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolSlot(selected = false, label = stringResource(R.string.image), onSelect = { open = true }, glyph = {
            Icon(painterResource(R.drawable.ic_image), null, tint = it, modifier = Modifier.size(24.dp))
        })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                ImageSource.GALLERY to R.string.image_gallery,
                ImageSource.FILES to R.string.image_files,
                ImageSource.CAMERA to R.string.image_camera,
            ).forEach { (source, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                    open = false
                    onAdd(source)
                })
            }
        }
    }
}

/** A tool button that selects its tool, or opens its settings when it's already selected. */
@Composable
private fun ToolSlot(
    selected: Boolean,
    label: String,
    onSelect: () -> Unit,
    glyph: @Composable (Color) -> Unit,
    settings: (@Composable (close: () -> Unit) -> Unit)? = null,
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
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) { settings { open = false } }
        }
    }
}

/** Scroll direction, continuous or page by page, and two pages side by side. */
@Composable
private fun PageFlowSettings(view: ViewSettings) {
    val flow = view.flow
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.page_layout), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = flow.axis == ScrollAxis.VERTICAL, onClick = { view.updateFlow(flow.copy(axis = ScrollAxis.VERTICAL)) }, label = { Text(stringResource(R.string.scroll_vertical)) })
            FilterChip(selected = flow.axis == ScrollAxis.HORIZONTAL, onClick = { view.updateFlow(flow.copy(axis = ScrollAxis.HORIZONTAL)) }, label = { Text(stringResource(R.string.scroll_horizontal)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = flow.continuous, onClick = { view.updateFlow(flow.copy(continuous = true)) }, label = { Text(stringResource(R.string.flow_continuous)) })
            FilterChip(selected = !flow.continuous, onClick = { view.updateFlow(flow.copy(continuous = false)) }, label = { Text(stringResource(R.string.flow_paged)) })
        }
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.two_pages)) },
        trailingIcon = { Checkbox(checked = flow.twoPage, onCheckedChange = null) },
        onClick = { view.updateFlow(flow.copy(twoPage = !flow.twoPage)) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.cover_alone)) },
        enabled = flow.twoPage,
        trailingIcon = { Checkbox(checked = flow.coverAlone, onCheckedChange = null, enabled = flow.twoPage) },
        onClick = { view.updateFlow(flow.copy(coverAlone = !flow.coverAlone)) },
    )
}

/** Tape boxes or pen lines, their color, and the pen's width. */
@Composable
private fun MaskSettings(tools: ToolState) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(280.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(MaskKind.TAPE to R.string.mask_tape, MaskKind.PEN to R.string.mask_pen).forEach { (kind, label) ->
                FilterChip(
                    selected = tools.maskKind == kind,
                    onClick = { tools.updateMask(kind, tools.maskColor, tools.maskWidth) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        ColorChooser(tools.maskColor, ToolState.MASK_COLORS, { tools.updateMask(tools.maskKind, it, tools.maskWidth) }, Modifier.padding(vertical = 8.dp))
        if (tools.maskKind == MaskKind.PEN) {
            Text(stringResource(R.string.mask_size, tools.maskWidth.roundToInt()), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = tools.maskWidth,
                onValueChange = { tools.updateMask(tools.maskKind, tools.maskColor, it) },
                valueRange = ToolState.MASK_WIDTHS,
            )
        }
        Text(stringResource(R.string.mask_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Dot or trailing line, and the laser's color. */
@Composable
private fun LaserSettings(tools: ToolState) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(280.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(LaserMode.DOT to R.string.laser_dot, LaserMode.LINE to R.string.laser_line).forEach { (mode, label) ->
                FilterChip(
                    selected = tools.laserMode == mode,
                    onClick = { tools.updateLaser(mode, tools.laserColor) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        ColorChooser(tools.laserColor, ToolState.LASER_COLORS, { tools.updateLaser(tools.laserMode, it) }, Modifier.padding(top = 8.dp))
    }
}

/** The color new sticky notes get. */
@Composable
private fun NoteSettings(tools: ToolState) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(280.dp)) {
        Text(stringResource(R.string.note_color), style = MaterialTheme.typography.labelLarge)
        ColorChooser(tools.noteColor, ToolState.NOTE_COLORS, { tools.updateNoteColor(it) }, Modifier.padding(top = 8.dp))
    }
}

/** The sticker tool, whose settings are the sticker panel; images picked there join the open pack. */
@Composable
private fun StickerSlot(tools: ToolState, selected: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pack by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val packId = pack
        if (uri == null || packId == null) return@rememberLauncherForActivityResult
        scope.launch { runLibraryAction(context) { importSticker(context, uri, packId, this) }?.let { tools.armSticker(it.ref()) } }
    }
    ToolSlot(
        selected = selected,
        label = stringResource(R.string.sticker),
        onSelect = { tools.selectTool(Tool.STICKER) },
        glyph = { Icon(painterResource(R.drawable.ic_sticker), null, tint = it, modifier = Modifier.size(24.dp)) },
    ) { close ->
        StickerPanel(tools, onPicked = close, onAddImage = { id ->
            pack = id
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
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

/** A short wavy stroke previewing a pen's color and size. */
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
        ColorChooser(preset.color, ToolState.PALETTE, { onChange(preset.copy(color = withAlpha(it, preset.color ushr 24))) })
        if (preset.pen == Pen.SHAPE) ShapeSettings(preset, onChange)
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
        if (preset.pen != Pen.SHAPE) PenPreview(preset, Modifier.fillMaxWidth().height(48.dp))
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
    Pen.SHAPE -> R.string.pen_shape
}

private val SHAPE_CHOICES = listOf(null, ShapeKind.ELLIPSE, ShapeKind.TRIANGLE, ShapeKind.RECTANGLE, ShapeKind.POLYGON, ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.CURVE)

internal fun shapeName(kind: ShapeKind?) = when (kind) {
    null -> R.string.shape_free
    ShapeKind.LINE -> R.string.shape_line
    ShapeKind.ARROW -> R.string.shape_arrow
    ShapeKind.RECTANGLE -> R.string.shape_rectangle
    ShapeKind.ELLIPSE -> R.string.shape_ellipse
    ShapeKind.TRIANGLE -> R.string.shape_triangle
    ShapeKind.POLYGON -> R.string.shape_polygon
    ShapeKind.CURVE -> R.string.shape_curve
}

private fun convertName(kind: ShapeKind) = when (kind) {
    ShapeKind.LINE -> R.string.convert_line
    ShapeKind.ELLIPSE -> R.string.convert_ellipse
    ShapeKind.TRIANGLE -> R.string.convert_triangle
    ShapeKind.RECTANGLE -> R.string.convert_rectangle
    else -> R.string.convert_polygon
}

/** A small drawing of a shape pen's shape. */
@Composable
private fun ShapeGlyph(kind: ShapeKind?, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
        when (kind) {
            null -> drawPath(Path().apply { moveTo(w * 0.15f, h * 0.7f); cubicTo(w * 0.3f, h * 0.1f, w * 0.6f, h * 0.9f, w * 0.85f, h * 0.3f) }, color, style = stroke)
            ShapeKind.ELLIPSE -> drawOval(color, Offset(w * 0.1f, h * 0.2f), Size(w * 0.8f, h * 0.6f), style = stroke)
            ShapeKind.TRIANGLE -> drawPath(Path().apply { moveTo(w / 2, h * 0.15f); lineTo(w * 0.88f, h * 0.85f); lineTo(w * 0.12f, h * 0.85f); close() }, color, style = stroke)
            ShapeKind.RECTANGLE -> drawRect(color, Offset(w * 0.15f, h * 0.22f), Size(w * 0.7f, h * 0.56f), style = stroke)
            ShapeKind.POLYGON -> drawPath(Path().apply {
                for (i in 0 until 5) {
                    val a = -Math.PI / 2 + 2 * Math.PI * i / 5
                    val x = (w / 2 + kotlin.math.cos(a) * w * 0.4f).toFloat()
                    val y = (h / 2 + kotlin.math.sin(a) * h * 0.4f).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }, color, style = stroke)
            ShapeKind.LINE -> drawLine(color, Offset(w * 0.15f, h * 0.85f), Offset(w * 0.85f, h * 0.15f), stroke.width, StrokeCap.Round)
            ShapeKind.ARROW -> {
                drawLine(color, Offset(w * 0.15f, h * 0.85f), Offset(w * 0.85f, h * 0.15f), stroke.width, StrokeCap.Round)
                drawPath(Path().apply { moveTo(w * 0.5f, h * 0.15f); lineTo(w * 0.85f, h * 0.15f); lineTo(w * 0.85f, h * 0.5f) }, color, style = stroke)
            }
            ShapeKind.CURVE -> drawPath(Path().apply { moveTo(w * 0.1f, h * 0.8f); quadraticTo(w * 0.5f, h * -0.2f, w * 0.9f, h * 0.8f) }, color, style = stroke)
        }
    }
}

@Composable
private fun ShapeSettings(preset: PenPreset, onChange: (PenPreset) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SHAPE_CHOICES.forEach { kind ->
            val chosen = preset.shape == kind
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable(onClickLabel = stringResource(shapeName(kind))) { onChange(preset.copy(shape = kind)) },
            ) { ShapeGlyph(kind, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (preset.shape == ShapeKind.POLYGON) {
        LabelledSlider(stringResource(R.string.polygon_sides, preset.sides), preset.sides.toFloat(), 3f..12f) { onChange(preset.copy(sides = it.roundToInt())) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LineStyle.entries.forEach { style ->
            FilterChip(
                selected = preset.line == style,
                onClick = { onChange(preset.copy(line = style)) },
                label = {
                    Text(stringResource(when (style) { LineStyle.SOLID -> R.string.line_solid; LineStyle.DASHED -> R.string.line_dashed; LineStyle.DOTTED -> R.string.line_dotted }))
                },
            )
        }
    }
    Text(stringResource(R.string.fill), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = preset.fill == 0, onClick = { onChange(preset.copy(fill = 0)) }, label = { Text(stringResource(R.string.no_fill)) })
        ToolState.PALETTE.take(8).forEach { swatch ->
            val fill = withAlpha(swatch, FILL_ALPHA)
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(fill))
                    .border(if (preset.fill == fill) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onChange(preset.copy(fill = fill)) },
            )
        }
    }
}

private const val FILL_ALPHA = 0x55
