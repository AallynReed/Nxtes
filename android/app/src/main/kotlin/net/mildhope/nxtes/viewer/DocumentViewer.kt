package net.mildhope.nxtes.viewer

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
fun DocumentViewer(doc: Document, viewport: ViewportState, onBack: () -> Unit) {
    val app = LocalContext.current.nxtes
    val renderer = remember(doc.id) {
        PageRenderer { index, bitmap, left, top, width, height ->
            val source = doc.pages[index].pdf ?: return@PageRenderer
            try {
                app.pdf.render(app.library.blobFile(doc.id, source.blob), source.index, bitmap, left, top, width, height)
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
            onPages = {},
            onUndo = ink::undo,
            onRedo = ink::redo,
            onSidePanel = {},
        )
        PageCanvas(
            pages = doc.pages,
            renderer = renderer,
            ink = ink,
            tools = tools,
            state = viewport,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(LocalNxtesColors.current.pageBackground),
        )
    }
}
