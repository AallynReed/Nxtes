package net.mildhope.nxtes.viewer

import android.util.Log
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
import net.mildhope.nxtes.ink.InkSession
import net.mildhope.nxtes.nxtes
import net.mildhope.nxtes.ui.LocalNxtesColors

@Composable
fun DocumentViewer(
    doc: Document,
    viewport: ViewportState,
    tabs: List<Document>,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onBack: () -> Unit,
) {
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
        InkSession(
            load = { pageId -> app.library.readPageInk(doc.id, pageId) },
            save = { pages -> app.library.writePageInk(doc.id, pages) },
            scope = app.scope,
        )
    }
    val tools = app.tools
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var panelTab by rememberSaveable { mutableStateOf(SideTab.PAGES) }

    DisposableEffect(ink) { onDispose { app.scope.launch { ink.flush() } } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { app.scope.launch { ink.flush() } }
    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        WritingToolbar(
            tools = tools,
            canUndo = ink.canUndo,
            canRedo = ink.canRedo,
            onBack = onBack,
            onPages = {
                panelTab = SideTab.PAGES
                panelOpen = true
            },
            onUndo = ink::undo,
            onRedo = ink::redo,
            onSidePanel = { panelOpen = !panelOpen },
        )
        DocumentTabBar(tabs, doc.id, onSelectTab, onCloseTab)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            PageCanvas(
                pages = doc.pages,
                renderer = renderer,
                ink = ink,
                tools = tools,
                state = viewport,
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
}
