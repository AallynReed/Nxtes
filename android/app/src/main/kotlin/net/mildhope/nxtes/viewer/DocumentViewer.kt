package net.mildhope.nxtes.viewer

import android.util.Log
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
            DocumentTabBar(tabs, doc.id, onSelectTab, onCloseTab)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                PageCanvas(
                    pages = doc.pages,
                    renderer = renderer,
                    ink = ink,
                    resources = resources,
                    tools = tools,
                    state = viewport,
                    selection = selection,
                    onPageCounter = { goToPage = true },
                    assetsLoaded = { app.assets.loaded },
                    overlay = { mapper ->
                        val edit = selection.text
                        val crop = selection.crop
                        if (crop != null) {
                            CropEditor(crop, mapper, resources) { selection.endCrop(ink, it) }
                        } else if (edit == null) {
                            EditMenus(doc, mapper, selection, actions, scope, tools) { pageIndex, point ->
                                imageTarget = pageIndex to point
                                addImage(ImageSource.GALLERY)
                            }
                        } else {
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

/** Images start at most this share of the page across. */
private const val IMAGE_FILL = 0.6f

/** And no bigger than this many points per pixel, so small images aren't blown up. */
private const val MAX_POINTS_PER_PIXEL = 0.75f

