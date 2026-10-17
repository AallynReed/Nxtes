package net.mildhope.nxtes.viewer

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.ink.InkSession
import net.mildhope.nxtes.nxtes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewer(doc: Document, onBack: () -> Unit) {
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
    var menuOpen by remember { mutableStateOf(false) }

    DisposableEffect(ink) { onDispose { app.scope.launch { ink.flush() } } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { app.scope.launch { ink.flush() } }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = ink::undo, enabled = ink.canUndo) {
                            Icon(painterResource(R.drawable.ic_undo), stringResource(R.string.undo))
                        }
                        IconButton(onClick = ink::redo, enabled = ink.canRedo) {
                            Icon(painterResource(R.drawable.ic_redo), stringResource(R.string.redo))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.draw_with_finger)) },
                                    trailingIcon = { Checkbox(checked = tools.drawWithFinger, onCheckedChange = null) },
                                    onClick = tools::toggleDrawWithFinger,
                                )
                            }
                        }
                    },
                )
                PenToolbar(tools)
            }
        },
    ) { padding ->
        PageCanvas(
            pages = doc.pages,
            renderer = renderer,
            ink = ink,
            tools = tools,
            textures = app.textures,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}
