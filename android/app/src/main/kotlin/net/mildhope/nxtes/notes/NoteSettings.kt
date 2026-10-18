package net.mildhope.nxtes.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.withScale
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Background
import net.mildhope.nxtes.data.Paper
import net.mildhope.nxtes.data.PaperSize
import net.mildhope.nxtes.data.Pattern
import net.mildhope.nxtes.data.TemplateChoice
import net.mildhope.nxtes.page.TemplatePainter

private enum class SettingsTab { COVER, TEMPLATE }

private val BASIC = listOf(Pattern.PLAIN, Pattern.LINED, Pattern.DOTTED, Pattern.GRAPH, Pattern.LEGAL, Pattern.CORNELL, Pattern.VERTICAL)
private val COLUMNS = listOf(Pattern.COLUMNS, Pattern.COLUMNS_LINED, Pattern.COLUMNS_DOTTED, Pattern.COLUMNS_GRAPH)
private val ITEM_SHORT = 84.dp
private val ITEM_LONG = 119.dp
private val PREVIEW_SHORT = 96.dp
private val PREVIEW_LONG = 136.dp

/**
 * The full-screen note editor (docs/ui.md): cover and template previews and the title on top,
 * the covers or templates to pick from below. With [title] null it only picks a template.
 * [noCover] previews the document when it has no cover.
 */
@Composable
fun NoteSettingsDialog(
    title: String?,
    cover: Int?,
    choice: TemplateChoice,
    onDone: (title: String, cover: Int?, choice: TemplateChoice) -> Unit,
    onDismiss: () -> Unit,
    noCover: @Composable (Modifier) -> Unit = { TemplateThumb(choice.background, choice.width, choice.height, it) },
) {
    var name by rememberSaveable { mutableStateOf(title.orEmpty()) }
    var pickedCover by rememberSaveable { mutableStateOf(cover) }
    var picked by remember { mutableStateOf(choice) }
    var tab by rememberSaveable { mutableStateOf(SettingsTab.TEMPLATE) }
    val defaultTitle = stringResource(R.string.default_note_title)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { onDone(name.trim().ifEmpty { defaultTitle }, pickedCover, picked) }, shape = RoundedCornerShape(50)) {
                        Text(stringResource(R.string.done))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    if (title != null) {
                        PreviewTile(stringResource(R.string.cover), tab == SettingsTab.COVER, { tab = SettingsTab.COVER }) {
                            val art = Covers.get(pickedCover)
                            val box = Modifier.width(PREVIEW_SHORT).aspectRatio(1 / 1.414f)
                            if (art != null) CoverArt(art, box) else noCover(Modifier.width(PREVIEW_SHORT))
                        }
                    }
                    PreviewTile(stringResource(R.string.template), tab == SettingsTab.TEMPLATE, { tab = SettingsTab.TEMPLATE }) {
                        TemplateThumb(picked.background, picked.width, picked.height, Modifier.width(if (picked.landscape) PREVIEW_LONG else PREVIEW_SHORT))
                    }
                    if (title != null) {
                        Spacer(Modifier.weight(1f))
                        TitleField(name, { name = it }, defaultTitle)
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (tab == SettingsTab.TEMPLATE) {
                    TemplateOptions(picked, { picked = it }, Modifier.align(Alignment.End).padding(horizontal = 24.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                    when (tab) {
                        SettingsTab.TEMPLATE -> templateSections(picked) { picked = it }
                        SettingsTab.COVER -> coverSections(pickedCover, noCover) { pickedCover = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewTile(label: String, selected: Boolean, onClick: () -> Unit, preview: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(8.dp),
    ) {
        Box(Modifier.height(PREVIEW_LONG), contentAlignment = Alignment.Center) {
            Box(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant)) { preview() }
        }
        Text(
            label,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun TitleField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Column(Modifier.width(270.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(36.dp)) {
            if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.primary)
    }
}

/** Paper size, orientation and paper colour, on the right above the template list. */
@Composable
private fun TemplateOptions(choice: TemplateChoice, onChange: (TemplateChoice) -> Unit, modifier: Modifier = Modifier) {
    var sizesOpen by remember { mutableStateOf(false) }
    Row(modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box {
            TextButton(onClick = { sizesOpen = true }) {
                Text(sizeLabel(choice.size))
                Icon(Icons.Default.ArrowDropDown, stringResource(R.string.paper_size))
            }
            DropdownMenu(expanded = sizesOpen, onDismissRequest = { sizesOpen = false }) {
                PaperSize.entries.forEach { size ->
                    DropdownMenuItem(
                        text = { Text(sizeLabel(size), color = if (size == choice.size) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                        onClick = {
                            sizesOpen = false
                            onChange(choice.copy(size = size))
                        },
                    )
                }
            }
        }
        val orientation = stringResource(if (choice.landscape) R.string.landscape else R.string.portrait)
        IconButton(onClick = { onChange(choice.copy(landscape = !choice.landscape)) }) {
            val tint = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.size(22.dp).semantics { contentDescription = orientation }) {
                val w = if (choice.landscape) size.width else size.width * 0.7f
                val h = if (choice.landscape) size.height * 0.7f else size.height
                drawRoundRect(
                    tint,
                    topLeft = Offset((size.width - w) / 2, (size.height - h) / 2),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }
        Paper.entries.forEach { paper ->
            val selected = paper == choice.background.paper
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(TemplatePainter.paperColor(paper)))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(onClickLabel = stringResource(paperLabel(paper))) { onChange(choice.copy(background = choice.background.copy(paper = paper))) },
            ) {
                if (selected) Icon(Icons.Default.Check, null, tint = if (paper == Paper.DARK) Color.White else Color.Black, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun LazyListScope.templateSections(choice: TemplateChoice, onPick: (TemplateChoice) -> Unit) {
    listOf(R.string.templates_basic to BASIC, R.string.templates_columns to COLUMNS).forEach { (heading, patterns) ->
        item(key = heading) {
            SectionHeading(stringResource(heading))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                patterns.forEach { pattern ->
                    val background = choice.background.copy(pattern = pattern)
                    val selected = choice.customId == null && choice.background.pattern == pattern
                    PickItem(stringResource(patternLabel(pattern)), selected, if (choice.landscape) ITEM_LONG else ITEM_SHORT, { onPick(choice.copy(background = background, customId = null)) }) {
                        TemplateThumb(background, choice.width, choice.height, it)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.coverSections(cover: Int?, noCover: @Composable (Modifier) -> Unit, onPick: (Int?) -> Unit) {
    item(key = "solid") {
        SectionHeading(stringResource(R.string.covers_solid))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PickItem(stringResource(R.string.no_cover), cover == null, ITEM_SHORT, { onPick(null) }) { noCover(it) }
            Covers.solid.forEachIndexed { i, art ->
                PickItem(null, cover == i, ITEM_SHORT, { onPick(i) }) { CoverArt(art, it.aspectRatio(1 / 1.414f)) }
            }
        }
    }
    item(key = "gradients") {
        SectionHeading(stringResource(R.string.covers_gradient))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Covers.gradients.forEachIndexed { i, art ->
                val index = Covers.solid.size + i
                PickItem(null, cover == index, ITEM_SHORT, { onPick(index) }) { CoverArt(art, it.aspectRatio(1 / 1.414f)) }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
}

/** A cover or template to pick, with a check when selected. */
@Composable
private fun PickItem(label: String?, selected: Boolean, width: Dp, onClick: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(width).clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.BottomEnd) {
            content(
                Modifier.width(width).border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(4.dp).size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(2.dp),
                )
            }
        }
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** A generated template drawn at the size of [modifier]'s width. */
@Composable
fun TemplateThumb(background: Background, width: Float, height: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(width / height)) {
        val scale = size.width / width
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.withScale(scale, scale) { TemplatePainter.draw(this, background, width, height, scale) }
        }
    }
}

fun sizeLabel(size: PaperSize) = when (size) {
    PaperSize.A4 -> "A4"
    PaperSize.A5 -> "A5"
    PaperSize.B5 -> "B5"
    PaperSize.LETTER -> "Letter"
}

fun patternLabel(pattern: Pattern) = when (pattern) {
    Pattern.PLAIN, Pattern.COLUMNS -> R.string.pattern_plain
    Pattern.LINED, Pattern.COLUMNS_LINED -> R.string.pattern_lined
    Pattern.DOTTED, Pattern.COLUMNS_DOTTED -> R.string.pattern_dotted
    Pattern.GRAPH, Pattern.COLUMNS_GRAPH -> R.string.pattern_graph
    Pattern.LEGAL -> R.string.pattern_legal
    Pattern.CORNELL -> R.string.pattern_cornell
    Pattern.VERTICAL -> R.string.pattern_vertical
}

private fun paperLabel(paper: Paper) = when (paper) {
    Paper.WHITE -> R.string.paper_white
    Paper.YELLOW -> R.string.paper_yellow
    Paper.DARK -> R.string.paper_dark
}

/** Asks whether a note's new template applies to new pages only or to every page. */
@Composable
fun TemplateScopeDialog(onPick: (allPages: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apply_template_title)) },
        text = {
            Column {
                listOf(false to R.string.apply_template_new_pages, true to R.string.apply_template_all_pages).forEach { (all, label) ->
                    Text(
                        stringResource(label),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(all) }
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
