package net.mildhope.nxtes.viewer

import android.util.Log
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
                    overlay = { mapper -> EditMenus(doc, mapper, selection, actions, scope) },
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
private fun EditMenus(doc: Document, mapper: PageMapper, selection: SelectionState, actions: SelectionActions, scope: CoroutineScope) {
    val context = LocalContext.current
    val app = context.nxtes
    val box = selection.bounds
    val index = mapper.indexOf(selection.pageId)
    if (selection.active && box != null && index >= 0 && selection.lasso.isEmpty() && selection.preview == Affine()) {
        val topLeft = mapper.toScreen(index, Offset(box.left, box.top))
        val bottomRight = mapper.toScreen(index, Offset(box.right, box.bottom))
        SelectionMenu(Rect(topLeft, bottomRight), actions)
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
    items += stringResource(R.string.add_bookmark) to { addToDocument(bookmarkAdded) { addBookmark(doc.id, pageId, pageTitle) } }
    items += stringResource(R.string.add_outline) to { addToDocument(outlineAdded) { addOutline(doc.id, pageId, pageTitle) } }
    PageMenu(Rect(at, at), items)
}
