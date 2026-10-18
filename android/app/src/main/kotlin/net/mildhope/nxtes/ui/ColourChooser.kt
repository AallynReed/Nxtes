package net.mildhope.nxtes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.library.NameDialog
import net.mildhope.nxtes.nxtes
import android.graphics.Color as AndroidColor

private val SWATCH = 30.dp

/**
 * Colours to pick from: [builtIn], or a saved palette chosen from the chips on top. A custom colour
 * comes from the hue and shade picker or a hex code, and can be added to the shown palette, which
 * forgets a colour that's touched and held. Picks are opaque; [colour]'s alpha is ignored.
 */
@Composable
fun ColourChooser(colour: Int, builtIn: List<Int>, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val palettes = LocalContext.current.nxtes.palettes
    val shown = palettes.shown
    val current = colour or OPAQUE
    var custom by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Palette?>(null) }
    val newName = stringResource(R.string.palette_number, palettes.saved.size + 1)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = shown == null, onClick = { palettes.show(null) }, label = { Text(stringResource(R.string.palette_basic)) })
                palettes.saved.forEach { palette ->
                    FilterChip(selected = shown?.id == palette.id, onClick = { palettes.show(palette.id) }, label = { Text(palette.name) })
                }
                IconButton(onClick = { palettes.add(newName, listOf(current)) }) { Icon(Icons.Default.Add, stringResource(R.string.palette_new)) }
            }
            if (shown != null) PaletteMenu(onRename = { renaming = shown }, onDelete = { palettes.delete(shown.id) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val swatches = shown?.colors ?: builtIn.map { it or OPAQUE }
            swatches.forEach { swatch ->
                Box(
                    Modifier
                        .size(SWATCH)
                        .clip(CircleShape)
                        .background(Color(swatch))
                        .border(if (swatch == current) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .combinedClickable(
                            onClick = { onPick(swatch) },
                            onLongClick = shown?.let { palette -> { palettes.removeColor(palette.id, swatch) } },
                        ),
                )
            }
            if (shown != null && current !in shown.colors) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(SWATCH)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.palette_add_colour)) { palettes.addColor(shown.id, current) },
                ) { Icon(Icons.Default.Add, null, tint = Color(current), modifier = Modifier.size(20.dp)) }
            }
        }
        if (shown != null) {
            Text(stringResource(R.string.palette_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { custom = !custom }) {
            Text(stringResource(if (custom) R.string.custom_colour_hide else R.string.custom_colour))
        }
        if (custom) CustomColour(current, onPick)
    }
    renaming?.let { palette ->
        NameDialog(stringResource(R.string.palette_rename), palette.name, { name ->
            palettes.rename(palette.id, name)
            renaming = null
        }, { renaming = null })
    }
}

@Composable
private fun PaletteMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.palette_options)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = {
                open = false
                onRename()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, onClick = {
                open = false
                onDelete()
            })
        }
    }
}

/** A shade square and hue bar, plus a hex code field. */
@Composable
private fun CustomColour(colour: Int, onPick: (Int) -> Unit) {
    val latestPick by rememberUpdatedState(onPick)
    var hsv by remember { mutableStateOf(hsvOf(colour)) }
    var hex by remember { mutableStateOf(hexOf(colour)) }
    LaunchedEffect(colour) {
        if (colorOf(hsv) != colour) hsv = hsvOf(colour)
        if (parseHex(hex) != colour) hex = hexOf(colour)
    }
    fun pick(next: FloatArray) {
        hsv = next
        latestPick(colorOf(next))
    }
    val hue = Color(colorOf(floatArrayOf(hsv[0], 1f, 1f)))
    val shadeInput = Modifier.pointerInput(Unit) {
        fun at(p: Offset) = pick(floatArrayOf(hsv[0], (p.x / size.width).coerceIn(0f, 1f), 1 - (p.y / size.height).coerceIn(0f, 1f)))
        detectTapGestures { at(it) }
    }.pointerInput(Unit) {
        detectDragGestures { change, _ ->
            change.consume()
            pick(floatArrayOf(hsv[0], (change.position.x / size.width).coerceIn(0f, 1f), 1 - (change.position.y / size.height).coerceIn(0f, 1f)))
        }
    }
    val hueInput = Modifier.pointerInput(Unit) {
        detectTapGestures { pick(floatArrayOf((it.x / size.width).coerceIn(0f, 1f) * 360f, hsv[1], hsv[2])) }
    }.pointerInput(Unit) {
        detectDragGestures { change, _ ->
            change.consume()
            pick(floatArrayOf((change.position.x / size.width).coerceIn(0f, 1f) * 360f, hsv[1], hsv[2]))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(6.dp)).then(shadeInput)) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hue)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val thumb = Offset(hsv[1] * size.width, (1 - hsv[2]) * size.height)
            drawCircle(Color.White, 9.dp.toPx(), thumb, style = Stroke(2.dp.toPx()))
            drawCircle(Color.Black, 10.dp.toPx(), thumb, style = Stroke(1.dp.toPx()))
        }
        Canvas(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp)).then(hueInput)) {
            drawRect(Brush.horizontalGradient(HUES))
            val x = hsv[0] / 360f * size.width
            drawCircle(Color.White, size.height / 2 - 2.dp.toPx(), Offset(x, size.height / 2), style = Stroke(2.dp.toPx()))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color(colour)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
            OutlinedTextField(
                value = hex,
                onValueChange = { text ->
                    hex = text.take(7)
                    parseHex(hex)?.let { onPick(it) }
                },
                singleLine = true,
                label = { Text(stringResource(R.string.hex_colour)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private const val OPAQUE = 0xFF000000.toInt()
private val HUES = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color(AndroidColor.HSVToColor(floatArrayOf(it, 1f, 1f))) }

private fun hsvOf(colour: Int) = FloatArray(3).also { AndroidColor.colorToHSV(colour, it) }

private fun colorOf(hsv: FloatArray) = AndroidColor.HSVToColor(hsv)

private fun hexOf(colour: Int) = "#%06X".format(colour and 0xFFFFFF)

/** "#RRGGBB" or "RRGGBB" as an opaque colour, or null. */
internal fun parseHex(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6) return null
    return digits.toIntOrNull(16)?.let { it or OPAQUE }
}
