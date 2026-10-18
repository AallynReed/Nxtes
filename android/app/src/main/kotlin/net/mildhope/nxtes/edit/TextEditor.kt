package net.mildhope.nxtes.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.ui.ColorChooser
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.ink.ToolState
import net.mildhope.nxtes.nxtes
import net.mildhope.nxtes.page.PagePainter
import net.mildhope.nxtes.page.PageResources
import net.mildhope.nxtes.viewer.PageMapper
import kotlin.math.roundToInt

/** Android's generic font families, with their names in the font menu. */
private val SYSTEM_FONTS = listOf(
    "sans-serif" to R.string.font_sans,
    "sans-serif-light" to R.string.font_sans_light,
    "sans-serif-medium" to R.string.font_sans_medium,
    "sans-serif-condensed" to R.string.font_sans_condensed,
    "serif" to R.string.font_serif,
    "monospace" to R.string.font_mono,
    "serif-monospace" to R.string.font_serif_mono,
    "casual" to R.string.font_casual,
    "cursive" to R.string.font_cursive,
    "sans-serif-smallcaps" to R.string.font_small_caps,
)

private val TEXT_SIZES = listOf(8f, 9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 40f, 48f, 64f, 72f, 96f)

/**
 * Types into a text box where it sits on the page, with a bar above it for font, size, colors
 * and B/I/U. [onAddFont] picks a font file and calls back with the box's new font key.
 */
@Composable
fun TextEditor(
    edit: TextEdit,
    mapper: PageMapper,
    resources: PageResources,
    onChange: (TextBox) -> Unit,
    onDone: () -> Unit,
    onAddFont: () -> Unit,
) {
    val index = mapper.indexOf(edit.pageId).takeIf { it >= 0 } ?: return
    val box = edit.box
    val k = mapper.pixelsPerPoint
    val topLeft = mapper.toScreen(index, Offset(box.x, box.y))
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    val typeface = remember(box.font, box.bold, box.italic) {
        android.graphics.Typeface.create(resources.typeface(box.font), PagePainter.typefaceStyle(box.bold, box.italic))
    }
    val style = TextStyle(
        color = Color(box.color),
        fontSize = with(density) { (box.size * k).toSp() },
        fontFamily = FontFamily(typeface),
        textDecoration = if (box.underline) TextDecoration.Underline else null,
    )
    val widthPx = (box.width * k).roundToInt().coerceAtLeast(1)
    BasicTextField(
        value = box.text,
        onValueChange = { onChange(box.copy(text = it)) },
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = widthPx, maxWidth = widthPx, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            }
            .graphicsLayer {
                rotationZ = box.rotation
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
            .background(if (box.background != 0) Color(box.background) else Color.Transparent)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            .focusRequester(focus),
    )
    LaunchedEffect(box.id) { focus.requestFocus() }

    val anchor = Rect(topLeft, Offset(topLeft.x + widthPx, topLeft.y + box.height * k))
    AnchoredMenu(anchor, gap = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.done)) }
            FontMenu(box, onChange, onAddFont)
            SizeStepper(box.size) { onChange(box.copy(size = it)) }
            ColorDot(Color(box.color), stringResource(R.string.text_color), allowNone = false) { onChange(box.copy(color = it)) }
            StyleToggle("B", box.bold, FontWeight.Bold, null) { onChange(box.copy(bold = !box.bold)) }
            StyleToggle("I", box.italic, FontWeight.Normal, FontStyle.Italic) { onChange(box.copy(italic = !box.italic)) }
            StyleToggle("U", box.underline, FontWeight.Normal, null, TextDecoration.Underline) { onChange(box.copy(underline = !box.underline)) }
            ColorDot(if (box.background == 0) null else Color(box.background), stringResource(R.string.text_background), allowNone = true) { onChange(box.copy(background = it)) }
            MenuIcon(rememberVectorPainter(Icons.Default.Delete), stringResource(R.string.delete), MaterialTheme.colorScheme.error) {
                onChange(box.copy(text = ""))
                onDone()
            }
        }
    }
}

@Composable
private fun FontMenu(box: TextBox, onChange: (TextBox) -> Unit, onAddFont: () -> Unit) {
    val app = LocalContext.current.nxtes
    val fonts by app.library.fonts.collectAsState()
    var open by remember { mutableStateOf(false) }
    val name = SYSTEM_FONTS.firstOrNull { it.first == box.font }?.let { stringResource(it.second) }
        ?: fonts.firstOrNull { PageContent.FONT_BLOB + it.blob == box.font }?.name
        ?: stringResource(R.string.embedded_font)
    Box {
        TextButton(onClick = { open = true }) { Text(name, maxLines = 1, modifier = Modifier.width(120.dp)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SYSTEM_FONTS.forEach { (family, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label), fontFamily = FontFamily(android.graphics.Typeface.create(family, android.graphics.Typeface.NORMAL))) },
                    onClick = {
                        open = false
                        onChange(box.copy(font = family))
                    },
                )
            }
            if (fonts.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            fonts.forEach { font ->
                DropdownMenuItem(
                    text = { Text(font.name) },
                    onClick = {
                        open = false
                        onChange(box.copy(font = PageContent.FONT_BLOB + font.blob))
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_font)) },
                leadingIcon = { Icon(Icons.Default.Add, null) },
                onClick = {
                    open = false
                    onAddFont()
                },
            )
        }
    }
}

@Composable
private fun SizeStepper(size: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange(TEXT_SIZES.lastOrNull { it < size } ?: size) }) { Text("−") }
        Text(stringResource(R.string.text_size, size.roundToInt()), style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { onChange(TEXT_SIZES.firstOrNull { it > size } ?: size) }) { Text("+") }
    }
}

@Composable
private fun StyleToggle(
    label: String,
    on: Boolean,
    weight: FontWeight,
    style: FontStyle?,
    decoration: TextDecoration? = null,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
    ) {
        Text(label, fontWeight = weight, fontStyle = style, textDecoration = decoration, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

/** A color swatch that opens the palette; with [allowNone] the palette offers no color (0). */
@Composable
private fun ColorDot(color: Color?, label: String, allowNone: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .padding(6.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(color ?: Color.Transparent)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(onClickLabel = label) { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 12.dp).width(280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (allowNone) {
                    TextButton(onClick = {
                        open = false
                        onPick(0)
                    }) { Text(stringResource(R.string.no_fill)) }
                }
                ColorChooser(color?.toArgb() ?: 0, ToolState.PALETTE, { onPick(if (allowNone) (it and 0xFFFFFF) or 0x55000000 else it) })
            }
        }
    }
}

/** A new, empty text box at [x],[y] in a page [pageWidth] points wide, in [style]. */
fun newTextBox(pageWidth: Float, x: Float, y: Float, style: net.mildhope.nxtes.ink.TextStyle) = TextBox(
    java.util.UUID.randomUUID().toString(), "", x, y - style.size / 2,
    (pageWidth - x - TEXT_MARGIN).coerceIn(TEXT_MIN_WIDTH, TEXT_WIDTH), style.size * 1.4f,
    font = style.font, size = style.size, color = style.color, background = style.background,
    bold = style.bold, italic = style.italic, underline = style.underline,
)

private const val TEXT_WIDTH = 240f
private const val TEXT_MIN_WIDTH = 60f
private const val TEXT_MARGIN = 12f
