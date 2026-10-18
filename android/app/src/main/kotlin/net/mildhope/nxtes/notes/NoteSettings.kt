package net.mildhope.nxtes.notes

import android.net.Uri
import androidx.compose.runtime.saveable.Saver
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.page.decodeImage
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.launch
import net.mildhope.nxtes.data.CustomTemplate
import net.mildhope.nxtes.library.displayName
import net.mildhope.nxtes.library.runLibraryAction
import net.mildhope.nxtes.nxtes
import net.mildhope.nxtes.page.openAsPdf
import kotlin.math.roundToInt
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

/** The cover picked in note settings. */
sealed interface CoverPick {
    data object FirstPage : CoverPick

    data class Generated(val index: Int) : CoverPick

    /** An image just picked, stored once settings are done. */
    data class NewImage(val uri: Uri) : CoverPick

    /** The image the document already has. */
    data object CurrentImage : CoverPick

    companion object {
        fun of(doc: Document) = when {
            doc.coverImage != null -> CurrentImage
            doc.cover != null -> Generated(doc.cover)
            else -> FirstPage
        }
    }
}

private val CoverPickSaver = Saver<CoverPick, Any>(
    save = {
        when (it) {
            CoverPick.FirstPage -> "first"
            CoverPick.CurrentImage -> "current"
            is CoverPick.Generated -> it.index
            is CoverPick.NewImage -> it.uri
        }
    },
    restore = {
        when (it) {
            "current" -> CoverPick.CurrentImage
            is Int -> CoverPick.Generated(it)
            is Uri -> CoverPick.NewImage(it)
            else -> CoverPick.FirstPage
        }
    },
)

private enum class TemplateTab { BUILT_IN, CUSTOM }

private val BASIC = listOf(Pattern.PLAIN, Pattern.LINED, Pattern.DOTTED, Pattern.GRAPH, Pattern.LEGAL, Pattern.CORNELL, Pattern.VERTICAL)
private val COLUMNS = listOf(Pattern.COLUMNS, Pattern.COLUMNS_LINED, Pattern.COLUMNS_DOTTED, Pattern.COLUMNS_GRAPH)
private val ITEM_SHORT = 84.dp
private val ITEM_LONG = 119.dp
private val PREVIEW_SHORT = 96.dp
private val PREVIEW_LONG = 136.dp
private const val CUSTOM_THUMBNAIL_PX = 240
private const val COVER_PREVIEW_PX = 480

/**
 * The full-screen note editor (docs/ui.md): cover and template previews and the title on top,
 * the covers or templates to pick from below. With [title] null it only picks a template.
 * [noCover] previews the document when it has no cover; [coverImage] is its current cover image.
 */
@Composable
fun NoteSettingsDialog(
    title: String?,
    cover: CoverPick,
    choice: TemplateChoice,
    onDone: (title: String, cover: CoverPick, choice: TemplateChoice) -> Unit,
    onDismiss: () -> Unit,
    noCover: (@Composable (Modifier) -> Unit)? = null,
    coverImage: File? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val templates by context.nxtes.library.templates.collectAsState()
    var name by rememberSaveable { mutableStateOf(title.orEmpty()) }
    var pickedCover by rememberSaveable(stateSaver = CoverPickSaver) { mutableStateOf(cover) }
    var newImage by rememberSaveable { mutableStateOf<Uri?>(null) }
    var picked by remember { mutableStateOf(if (templates.any { it.id == choice.customId }) choice else choice.copy(customId = null)) }
    var tab by rememberSaveable { mutableStateOf(SettingsTab.TEMPLATE) }
    var templateTab by rememberSaveable { mutableStateOf(if (picked.customId != null) TemplateTab.CUSTOM else TemplateTab.BUILT_IN) }
    var deleting by remember { mutableStateOf<CustomTemplate?>(null) }
    val defaultTitle = stringResource(R.string.default_note_title)

    fun addTemplate(uri: Uri?, image: Boolean) {
        uri ?: return
        val fileName = if (image) null else displayName(context, uri)?.substringBeforeLast('.')?.ifBlank { null }
        val name = fileName ?: resources.getString(R.string.custom_template_number, templates.size + 1)
        scope.launch {
            runLibraryAction(context) { openAsPdf(context, uri, image) { addCustomTemplate(it, name) } }
                ?.let { picked = picked.copy(customId = it.id) }
        }
    }

    val firstPage: @Composable (Modifier) -> Unit = noCover ?: { ChoiceThumb(picked, templates, it) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { addTemplate(it, image = true) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { addTemplate(it, image = false) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            newImage = uri
            pickedCover = CoverPick.NewImage(uri)
        }
    }
    val coverArt: @Composable (CoverPick, Modifier) -> Unit = { pick, box -> CoverPreview(pick, coverImage, box) }

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
                            if (pickedCover == CoverPick.FirstPage) {
                                firstPage(Modifier.width(PREVIEW_SHORT))
                            } else {
                                coverArt(pickedCover, Modifier.width(PREVIEW_SHORT).aspectRatio(Covers.RATIO))
                            }
                        }
                    }
                    PreviewTile(stringResource(R.string.template), tab == SettingsTab.TEMPLATE, { tab = SettingsTab.TEMPLATE }) {
                        ChoiceThumb(picked, templates, Modifier.width(if (isLandscape(picked, templates)) PREVIEW_LONG else PREVIEW_SHORT))
                    }
                    if (title != null) {
                        Spacer(Modifier.weight(1f))
                        TitleField(name, { name = it }, defaultTitle)
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (tab == SettingsTab.TEMPLATE) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        TemplateTab.entries.forEach { option ->
                            val active = option == templateTab
                            Text(
                                stringResource(if (option == TemplateTab.BUILT_IN) R.string.templates_built_in else R.string.templates_custom),
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.clickable { templateTab = option }.padding(end = 24.dp, top = 12.dp, bottom = 12.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (templateTab == TemplateTab.BUILT_IN) TemplateOptions(picked, { picked = it.copy(customId = null) })
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                    when (tab) {
                        SettingsTab.TEMPLATE -> when (templateTab) {
                            TemplateTab.BUILT_IN -> templateSections(picked) { picked = it }
                            TemplateTab.CUSTOM -> customSection(
                                templates = templates,
                                choice = picked,
                                onPick = { picked = picked.copy(customId = it.id) },
                                onDelete = { deleting = it },
                                onAddImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                onAddPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                            )
                        }
                        SettingsTab.COVER -> coverSections(
                            cover = pickedCover,
                            images = listOfNotNull(CoverPick.CurrentImage.takeIf { coverImage != null }, newImage?.let { CoverPick.NewImage(it) }),
                            noCover = firstPage,
                            art = coverArt,
                            onAddImage = { coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        ) { pickedCover = it }
                    }
                }
            }
        }
    }
    deleting?.let { doomed ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_template_title, doomed.name)) },
            text = { Text(stringResource(R.string.delete_template_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    if (picked.customId == doomed.id) picked = picked.copy(customId = null)
                    scope.launch { runLibraryAction(context) { deleteCustomTemplate(doomed.id) } }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
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

private fun LazyListScope.customSection(
    templates: List<CustomTemplate>,
    choice: TemplateChoice,
    onPick: (CustomTemplate) -> Unit,
    onDelete: (CustomTemplate) -> Unit,
    onAddImage: () -> Unit,
    onAddPdf: () -> Unit,
) {
    item(key = "custom") {
        FlowRow(
            modifier = Modifier.padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AddTemplateTile(onAddImage, onAddPdf)
            templates.forEach { template ->
                val width = if (template.width > template.height) ITEM_LONG else ITEM_SHORT
                PickItem(template.name, choice.customId == template.id, width, { onPick(template) }, onLongClick = { onDelete(template) }) {
                    CustomTemplateThumb(template, it)
                }
            }
        }
        if (templates.isNotEmpty()) {
            Text(
                stringResource(R.string.custom_template_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/** A dashed page-shaped tile with a plus, for adding something. */
@Composable
private fun AddTile(label: String, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(ITEM_SHORT).clickable(onClick = onClick)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(ITEM_SHORT)
                .aspectRatio(Covers.RATIO)
                .drawBehind {
                    drawRect(outline, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                },
        ) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun AddTemplateTile(onAddImage: () -> Unit, onAddPdf: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AddTile(stringResource(R.string.add_template)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.template_from_image)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_image), null) },
                onClick = {
                    open = false
                    onAddImage()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.template_from_pdf)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_document), null) },
                onClick = {
                    open = false
                    onAddPdf()
                },
            )
        }
    }
}

private fun LazyListScope.coverSections(
    cover: CoverPick,
    images: List<CoverPick>,
    noCover: @Composable (Modifier) -> Unit,
    art: @Composable (CoverPick, Modifier) -> Unit,
    onAddImage: () -> Unit,
    onPick: (CoverPick) -> Unit,
) {
    item(key = "image") {
        SectionHeading(stringResource(R.string.covers_image))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AddTile(stringResource(R.string.cover_from_image), onAddImage)
            images.forEach { image ->
                PickItem(null, cover == image, ITEM_SHORT, { onPick(image) }) { art(image, it.aspectRatio(Covers.RATIO)) }
            }
        }
    }
    item(key = "solid") {
        SectionHeading(stringResource(R.string.covers_solid))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PickItem(stringResource(R.string.no_cover), cover == CoverPick.FirstPage, ITEM_SHORT, { onPick(CoverPick.FirstPage) }) { noCover(it) }
            Covers.solid.indices.forEach { i ->
                val pick = CoverPick.Generated(i)
                PickItem(null, cover == pick, ITEM_SHORT, { onPick(pick) }) { art(pick, it.aspectRatio(Covers.RATIO)) }
            }
        }
    }
    item(key = "gradients") {
        SectionHeading(stringResource(R.string.covers_gradient))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Covers.gradients.indices.forEach { i ->
                val pick = CoverPick.Generated(Covers.solid.size + i)
                PickItem(null, cover == pick, ITEM_SHORT, { onPick(pick) }) { art(pick, it.aspectRatio(Covers.RATIO)) }
            }
        }
    }
}

/** A generated or image cover filling [modifier]'s box; [coverImage] is the document's current image. */
@Composable
private fun CoverPreview(pick: CoverPick, coverImage: File?, modifier: Modifier) {
    val context = LocalContext.current
    when (pick) {
        is CoverPick.Generated -> Covers.get(pick.index)?.let { CoverArt(it, modifier) } ?: Box(modifier)
        is CoverPick.NewImage -> CoverPhoto(pick.uri, { decodeImage(context, pick.uri, COVER_PREVIEW_PX) }, modifier)
        CoverPick.CurrentImage -> CoverPhoto(coverImage, { coverImage?.let { decodeCover(it, COVER_PREVIEW_PX) } }, modifier)
        CoverPick.FirstPage -> Box(modifier)
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
}

/** A cover or template to pick, with a check when selected. */
@Composable
private fun PickItem(
    label: String?,
    selected: Boolean,
    width: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(width).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
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

private fun isLandscape(choice: TemplateChoice, templates: List<CustomTemplate>): Boolean {
    val custom = choice.customId?.let { id -> templates.find { it.id == id } }
    return if (custom != null) custom.width > custom.height else choice.landscape
}

/** The picked template's preview: a custom template's page or a generated one, sized by [modifier]'s width. */
@Composable
fun ChoiceThumb(choice: TemplateChoice, templates: List<CustomTemplate>, modifier: Modifier = Modifier) {
    val custom = choice.customId?.let { id -> templates.find { it.id == id } }
    if (custom != null) CustomTemplateThumb(custom, modifier) else TemplateThumb(choice.background, choice.width, choice.height, modifier)
}

/** The first page of a custom template, rendered at a small size. */
@Composable
fun CustomTemplateThumb(template: CustomTemplate, modifier: Modifier = Modifier) {
    val app = LocalContext.current.nxtes
    val bitmap by produceState<ImageBitmap?>(null, template.id) {
        value = runCatching {
            val width = CUSTOM_THUMBNAIL_PX
            val height = (width * template.height / template.width).roundToInt().coerceAtLeast(1)
            createBitmap(width, height).also { app.pdf.render(app.library.templateFile(template), 0, it, 0, 0, width, height) }.asImageBitmap()
        }.getOrNull()
    }
    val box = modifier.aspectRatio(template.width / template.height).background(Color.White)
    val image = bitmap
    if (image == null) Box(box) else Image(image, null, contentScale = ContentScale.FillBounds, modifier = box)
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
