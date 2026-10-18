package net.mildhope.nxtes.viewer

import android.util.Log
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import net.mildhope.nxtes.data.TextMark
import net.mildhope.nxtes.edit.MarkMenu
import net.mildhope.nxtes.edit.PageText
import net.mildhope.nxtes.edit.TextPick
import net.mildhope.nxtes.edit.TextPickActions
import net.mildhope.nxtes.edit.TextPickMenu
import net.mildhope.nxtes.edit.MaskMenu
import net.mildhope.nxtes.edit.answer
import net.mildhope.nxtes.edit.mask
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.edit.NoteEditor
import net.mildhope.nxtes.edit.STICKER_PIXELS
import net.mildhope.nxtes.page.BasicStickers
import net.mildhope.nxtes.page.keepBitmap
import java.util.UUID
import net.mildhope.nxtes.page.importImage
import net.mildhope.nxtes.page.cameraTarget
import net.mildhope.nxtes.edit.Picked
import net.mildhope.nxtes.edit.CropEditor
import net.mildhope.nxtes.data.Picture
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.activity.result.PickVisualMediaRequest
import android.net.Uri
import net.mildhope.nxtes.library.displayName
import net.mildhope.nxtes.ink.style
import net.mildhope.nxtes.ink.ToolState
import net.mildhope.nxtes.edit.newTextBox
import net.mildhope.nxtes.edit.TextEditor
import net.mildhope.nxtes.edit.MenuIcon
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.Icons
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.graphics.Typeface
import net.mildhope.nxtes.edit.SelectionState
import net.mildhope.nxtes.edit.SelectionMenu
import net.mildhope.nxtes.edit.SelectionActions
import net.mildhope.nxtes.edit.PageMenu
import net.mildhope.nxtes.data.Affine
import net.mildhope.nxtes.R
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.ink.PageSession
import net.mildhope.nxtes.library.runLibraryAction
import net.mildhope.nxtes.nxtes
import net.mildhope.nxtes.ui.LocalNxtesColors

@Composable
fun DocumentViewer(
    doc: Document,
    viewport: ViewportState,
    tabs: List<Document>,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseOtherTabs: (String) -> Unit,
    onCloseAllTabs: () -> Unit,
    onOpenDocument: (id: String, pageIndex: Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = LocalContext.current.nxtes
    val renderer = remember(doc.id, doc.pages) {
        PageRenderer { index, bitmap, left, top, width, height ->
            try {
                app.backgrounds.render(doc.id, doc.pages[index], bitmap, left, top, width, height)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Viewer", "Page $index of ${doc.id} failed to render", e)
            }
        }
    }
    val ink = remember(doc.id) {
        PageSession(
            load = { pageId -> app.library.readPage(doc.id, pageId) },
            save = { pages -> app.library.writePages(doc.id, pages) },
            scope = app.scope,
        )
    }
    val resources = remember(doc.id) { app.assets.resources(doc.id, app.scope) }
    val pageTexts = remember(doc.id) { HashMap<String, PageText>() }

    /** The PDF text on page [index] as it's shown, read once per page. */
    suspend fun pageText(index: Int): PageText? {
        val page = doc.pages.getOrNull(index) ?: return null
        val source = page.pdf ?: return null
        pageTexts[page.id]?.let { return it }
        val sideways = page.turns % 2 == 1
        val text = runCatching { app.pdf.text(app.library.blobFile(doc.id, source.blob), source.index) }
            .onFailure { Log.w("Viewer", "Text of page $index failed", it) }
            .getOrNull()
            ?.turned(page.turns, if (sideways) page.height else page.width, if (sideways) page.width else page.height)
            ?: return null
        pageTexts[page.id] = text
        return text
    }
    val selection = remember(doc.id) { SelectionState() }
    val actions = remember(doc.id, ink) { SelectionActions(app, doc.id, ink, selection) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ink.generation) { selection.clear() }
    val tools = app.tools
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var panelTab by rememberSaveable { mutableStateOf(SideTab.PAGES) }
    var navigatorOpen by rememberSaveable { mutableStateOf(false) }
    var goToPage by remember { mutableStateOf(false) }

    /** Runs a page change, saving this document's ink first and reloading it after if it changed. */
    fun editPages(documentId: String, action: LibraryStore.() -> Unit) {
        app.scope.launch {
            ink.flush()
            runLibraryAction(context, action)
            if (documentId == doc.id) ink.reset()
        }
    }

    DisposableEffect(ink) { onDispose { app.scope.launch { ink.flush() } } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { app.scope.launch { ink.flush() } }
    BackHandler(onBack = onBack)
    BackHandler(enabled = selection.text != null) { selection.commitText(ink, resources)?.let { tools.updateTextStyle(it.style) } }
    BackHandler(enabled = selection.note != null) { selection.commitNote(ink) }

    LaunchedEffect(app.view.readOnly) {
        if (!app.view.readOnly) return@LaunchedEffect
        selection.commitText(ink, resources)
        selection.commitNote(ink)
        selection.clear()
        selection.menuAt = null
        selection.asking = null
    }
    val hostView = LocalView.current
    DisposableEffect(hostView, app.view.keepScreenOn) {
        hostView.keepScreenOn = app.view.keepScreenOn
        onDispose { hostView.keepScreenOn = false }
    }
    DisposableEffect(hostView, app.view.fullScreen) {
        val bars = hostView.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, hostView) }
        if (app.view.fullScreen) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    /** Stamps the sticker tool's sticker centred on [point]. */
    fun stamp(pageIndex: Int, point: Offset) {
        val ref = tools.sticker
        val page = doc.pages.getOrNull(pageIndex) ?: return
        scope.launch {
            val blob = runLibraryAction(context) {
                val basic = ref.basic
                if (basic != null) {
                    val bitmap = BasicStickers.render(basic, STICKER_PIXELS / 2)
                    try {
                        keepBitmap(context, bitmap) { addBlob(doc.id, it) }
                    } finally {
                        bitmap.recycle()
                    }
                } else {
                    useSticker(doc.id, requireNotNull(ref.blob))
                    ref.blob
                }
            } ?: return@launch
            val aspect = ref.width.toFloat() / ref.height.coerceAtLeast(1)
            val w = if (aspect >= 1f) STICKER_POINTS else STICKER_POINTS * aspect
            val h = if (aspect >= 1f) STICKER_POINTS / aspect else STICKER_POINTS
            val picture = Picture(UUID.randomUUID().toString(), blob, point.x, point.y, w, h, sticker = true)
            ink.update(page.id) { it.copy(objects = it.objects + picture) }
        }
    }

    var imageTarget by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    /** Adds the image at [uri] to the page, centred on [imageTarget] or the middle of the screen, and selects it. */
    fun insertImage(uri: Uri) {
        val target = imageTarget ?: viewport.visibleCenter(doc.pages)
        imageTarget = null
        scope.launch {
            val stored = runLibraryAction(context) { importImage(context, uri, doc.id, this) } ?: return@launch
            val (index, point) = target
            val page = doc.pages.getOrNull(index) ?: return@launch
            val scale = minOf(page.width * IMAGE_FILL / stored.width, page.height * IMAGE_FILL / stored.height, MAX_POINTS_PER_PIXEL)
            val w = stored.width * scale
            val h = stored.height * scale
            val picture = Picture(
                UUID.randomUUID().toString(), stored.blob,
                cx = point.x.coerceIn(w / 2, (page.width - w / 2).coerceAtLeast(w / 2)),
                cy = point.y.coerceIn(h / 2, (page.height - h / 2).coerceAtLeast(h / 2)),
                width = w, height = h,
            )
            ink.update(page.id) { it.copy(objects = it.objects + picture) }
            selection.select(page.id, Picked(objects = setOf(picture.id)), ink.content(page.id))
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::insertImage) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::insertImage) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> cameraUri?.takeIf { taken }?.let(::insertImage) }

    fun addImage(source: ImageSource) {
        when (source) {
            ImageSource.GALLERY -> galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            ImageSource.FILES -> filePicker.launch(arrayOf("image/*"))
            ImageSource.CAMERA -> {
                val (_, uri) = cameraTarget(context)
                cameraUri = uri
                camera.launch(uri)
            }
        }
    }

    val fontInvalid = stringResource(R.string.font_invalid)
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val key = runLibraryAction(context) {
                val name = displayName(context, uri)?.substringBeforeLast('.')?.ifBlank { null } ?: fontInvalid
                val font = requireNotNull(context.contentResolver.openInputStream(uri)).use { addFont(it, name) }
                if (Typeface.Builder(fontFile(font.blob)).build() == null) {
                    deleteFont(font.blob)
                    null
                } else {
                    useFont(doc.id, font.blob)
                }
            }
            val edit = selection.text
            if (key == null) Toast.makeText(context, fontInvalid, Toast.LENGTH_LONG).show() else if (edit != null) selection.updateText(edit.box.copy(font = key))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Column(Modifier.fillMaxSize()) {
            WritingToolbar(
                tools = tools,
                view = app.view,
                canUndo = ink.canUndo,
                canRedo = ink.canRedo,
                onBack = onBack,
                onPages = { navigatorOpen = true },
                onUndo = {
                    selection.clear()
                    ink.undo()
                },
                onRedo = {
                    selection.clear()
                    ink.redo()
                },
                onSidePanel = { panelOpen = !panelOpen },
                onAddImage = ::addImage,
            )
            if (!app.view.hideTabs) DocumentTabBar(tabs, doc.id, onSelectTab, onCloseTab, onCloseOtherTabs, onCloseAllTabs)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                PageCanvas(
                    pages = doc.pages,
                    renderer = renderer,
                    ink = ink,
                    resources = resources,
                    tools = tools,
                    state = viewport,
                    selection = selection,
                    flow = app.view.flow,
                    onPageCounter = { goToPage = true },
                    readOnly = app.view.readOnly,
                    darkPages = app.view.darkPages,
                    assetsLoaded = { app.assets.loaded },
                    onStamp = ::stamp,
                    pageText = ::pageText,
                    overlay = { mapper ->
                        val edit = selection.text
                        val crop = selection.crop
                        val note = selection.note
                        if (note != null) {
                            NoteEditor(
                                edit = note,
                                mapper = mapper,
                                onChange = selection::updateNote,
                                onDelete = { selection.deleteNote(ink) },
                                onDone = { selection.commitNote(ink) },
                            )
                        } else if (crop != null) {
                            CropEditor(crop, mapper, resources) { selection.endCrop(ink, it) }
                        } else if (edit == null) {
                            EditMenus(doc, mapper, selection, actions, scope, tools, ink) { pageIndex, point ->
                                imageTarget = pageIndex to point
                                addImage(ImageSource.GALLERY)
                            }
                            selection.textPick?.let { pick -> TextPickMenu(pick, mapper, textActions(doc, pick, selection, ink, scope)) }
                            selection.markAt?.let { (pageId, markId) ->
                                ink.content(pageId).marks.firstOrNull { it.id == markId }?.let { mark ->
                                    MarkMenu(
                                        pageId, mark, mapper,
                                        onCopy = {
                                            copyText(context, mark.text)
                                            selection.markAt = null
                                        },
                                        onRecolour = { colour ->
                                            ink.update(pageId) { c -> c.copy(marks = c.marks.map { if (it.id == markId) TextMark(it.id, it.kind, colour, it.rects, it.text) else it }) }
                                        },
                                        onDelete = {
                                            ink.update(pageId) { c -> c.copy(marks = c.marks.filter { it.id != markId }) }
                                            selection.markAt = null
                                        },
                                    )
                                }
                            }
                            selection.asking?.let { (pageId, maskId) ->
                                ink.content(pageId).mask(maskId)?.let { mask ->
                                    MaskMenu(pageId, mask, mapper) { right ->
                                        ink.answer(pageId, maskId, right)
                                        selection.asking = null
                                    }
                                }
                            }
                        } else {
                            val box = edit.box
                            val index = mapper.indexOf(edit.pageId)
                            if (index >= 0) {
                                KeepAboveKeyboard(
                                    Rect(mapper.toScreen(index, Offset(box.x, box.y)), mapper.toScreen(index, Offset(box.x + box.width, box.y + box.height))),
                                    viewport,
                                )
                            }
                            TextEditor(
                                edit = edit,
                                mapper = mapper,
                                resources = resources,
                                onChange = selection::updateText,
                                onDone = { selection.commitText(ink, resources)?.let { tools.updateTextStyle(it.style) } },
                                onAddFont = { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream")) },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize().background(LocalNxtesColors.current.pageBackground),
                )
                if (panelOpen) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.12f))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { panelOpen = false },
                    )
                    SidePanel(
                        doc = doc,
                        viewport = viewport,
                        ink = ink,
                        selection = selection,
                        tab = panelTab,
                        onTab = { panelTab = it },
                        onClose = { panelOpen = false },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
        if (navigatorOpen) {
            PageNavigator(
                current = doc,
                currentPage = viewport.currentPage,
                onOpenPage = { id, index ->
                    navigatorOpen = false
                    if (id == doc.id) viewport.goToPage(index) else onOpenDocument(id, index)
                },
                onEdit = ::editPages,
                onClose = { navigatorOpen = false },
            )
        }
    }
    if (goToPage) {
        GoToPageDialog(
            pageCount = doc.pages.size,
            onGo = {
                goToPage = false
                viewport.goToPage(it)
            },
            onDismiss = { goToPage = false },
        )
    }
}

/** The lasso selection's menu and the touch-and-hold menu, placed over the page. */
@Composable
private fun EditMenus(
    doc: Document,
    mapper: PageMapper,
    selection: SelectionState,
    actions: SelectionActions,
    scope: CoroutineScope,
    tools: ToolState,
    ink: PageSession,
    onImage: (pageIndex: Int, point: Offset) -> Unit,
) {
    val context = LocalContext.current
    val app = context.nxtes
    val box = selection.bounds
    val index = mapper.indexOf(selection.pageId)
    if (selection.active && box != null && index >= 0 && selection.lasso.isEmpty() && selection.preview == Affine()) {
        val topLeft = mapper.toScreen(index, Offset(box.left, box.top))
        val bottomRight = mapper.toScreen(index, Offset(box.right, box.bottom))
        val single = selection.pageId?.let { page -> doc.pages.firstOrNull { it.id == page } }
        SelectionMenu(Rect(topLeft, bottomRight), actions) {
            val box = actions.singleText()
            if (box != null && single != null) {
                MenuIcon(rememberVectorPainter(Icons.Default.Edit), stringResource(R.string.edit_text)) { selection.startText(single.id, box, isNew = false) }
            }
            val note = actions.singleNote()
            if (note != null && single != null) {
                MenuIcon(painterResource(R.drawable.ic_sticky_note), stringResource(R.string.edit_note)) { selection.startNote(ink, single.id, note, isNew = false) }
            }
            val myStickers = stringResource(R.string.my_stickers)
            val addedToStickers = stringResource(R.string.added_to_stickers, myStickers)
            val failed = stringResource(R.string.action_failed)
            MenuIcon(painterResource(R.drawable.ic_sticker), stringResource(R.string.add_to_stickers)) {
                scope.launch {
                    val sticker = runCatching { actions.addToStickers(context, myStickers) }.onFailure { Log.w("Viewer", "Sticker failed", it) }.getOrNull()
                    Toast.makeText(context, if (sticker != null) addedToStickers else failed, Toast.LENGTH_SHORT).show()
                }
            }
            val picture = actions.singlePicture()
            if (picture != null && single != null) {
                var cropMenu by remember { mutableStateOf(false) }
                Box {
                    MenuIcon(painterResource(R.drawable.ic_crop), stringResource(R.string.crop)) { cropMenu = true }
                    DropdownMenu(expanded = cropMenu, onDismissRequest = { cropMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.crop_rectangle)) }, onClick = {
                            cropMenu = false
                            selection.startCrop(single.id, picture, freeform = false)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.crop_freeform)) }, onClick = {
                            cropMenu = false
                            selection.startCrop(single.id, picture, freeform = true)
                        })
                    }
                }
            }
        }
    }
    val (pageId, point) = selection.menuAt ?: return
    val pageIndex = mapper.indexOf(pageId).takeIf { it >= 0 } ?: return
    val at = mapper.toScreen(pageIndex, point)
    val pageTitle = stringResource(R.string.page_title, pageIndex + 1)
    val bookmarkAdded = stringResource(R.string.bookmark_added)
    val outlineAdded = stringResource(R.string.outline_added)
    fun addToDocument(message: String, action: LibraryStore.() -> Unit) {
        selection.menuAt = null
        scope.launch {
            if (runLibraryAction(context, action) != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    val items = mutableListOf<Pair<String, () -> Unit>>()
    if (app.copiedContent != null) {
        items += stringResource(R.string.paste) to {
            selection.menuAt = null
            scope.launch {
                runCatching { actions.paste(pageId, point) }.onFailure {
                    Log.w("Viewer", "Paste failed", it)
                    Toast.makeText(context, R.string.action_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val page = doc.pages[pageIndex]
    items += stringResource(R.string.image) to {
        selection.menuAt = null
        onImage(pageIndex, point)
    }
    items += stringResource(R.string.text_box) to { selection.startText(pageId, newTextBox(page.width, point.x, point.y, tools.textStyle), isNew = true) }
    items += stringResource(R.string.add_bookmark) to { addToDocument(bookmarkAdded) { addBookmark(doc.id, pageId, pageTitle) } }
    items += stringResource(R.string.add_outline) to { addToDocument(outlineAdded) { addOutline(doc.id, pageId, pageTitle) } }
    PageMenu(Rect(at, at), items)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Puts [text] on the clipboard; older Androids don't confirm it themselves. */
private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(text.take(40), text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

/** Opens [intent] in a chooser, or says nothing can handle it. */
private fun launch(context: Context, intent: Intent) {
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
        .onFailure { Toast.makeText(context, R.string.action_failed, Toast.LENGTH_SHORT).show() }
}

@Composable
private fun textActions(doc: Document, pick: TextPick, selection: SelectionState, ink: PageSession, scope: CoroutineScope): TextPickActions {
    val context = LocalContext.current
    val bookmarkAdded = stringResource(R.string.bookmark_added)
    val outlineAdded = stringResource(R.string.outline_added)
    val title = pick.string.lines().joinToString(" ").trim().take(TITLE_LENGTH)
    fun addToDocument(message: String, action: LibraryStore.() -> Unit) {
        selection.textPick = null
        scope.launch { if (runLibraryAction(context, action) != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
    return TextPickActions(
        mark = { kind, colour ->
            ink.update(pick.pageId) { it.copy(marks = it.marks + TextMark(UUID.randomUUID().toString(), kind, colour, pick.rects, pick.string)) }
            selection.textPick = null
        },
        copy = {
            copyText(context, pick.string)
            selection.textPick = null
        },
        lookUp = {
            launch(context, Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, pick.string).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
        },
        share = { launch(context, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pick.string)) },
        bookmark = { addToDocument(bookmarkAdded) { addBookmark(doc.id, pick.pageId, title) } },
        outline = { addToDocument(outlineAdded) { addOutline(doc.id, pick.pageId, title) } },
    )
}

/** Bookmarks and outline entries made from picked text keep this many characters. */
private const val TITLE_LENGTH = 80

/** Scrolls the pages up when the keyboard would cover [target] (screen pixels), keeping its top in view. */
@Composable
private fun KeepAboveKeyboard(target: Rect, viewport: ViewportState) {
    var visible by remember { mutableIntStateOf(0) }
    val margin = with(LocalDensity.current) { 24.dp.toPx() }
    Box(Modifier.fillMaxSize().imePadding().onSizeChanged { visible = it.height })
    LaunchedEffect(visible) {
        if (visible <= 0) return@LaunchedEffect
        val hidden = target.bottom + margin - visible
        if (hidden > 0f) viewport.transformBy(Offset.Zero, Offset(0f, -minOf(hidden, (target.top - margin).coerceAtLeast(0f))), 1f)
    }
}

/** Stickers are stamped this many page points on their long side. */
private const val STICKER_POINTS = 64f

/** Images start at most this share of the page across. */
private const val IMAGE_FILL = 0.6f

/** And no bigger than this many points per pixel, so small images aren't blown up. */
private const val MAX_POINTS_PER_PIXEL = 0.75f

