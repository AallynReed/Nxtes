package net.mildhope.nxtes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.mildhope.nxtes.library.LibraryScreen
import net.mildhope.nxtes.library.importFiles
import net.mildhope.nxtes.library.importFlexcil
import net.mildhope.nxtes.library.isFlexcil
import net.mildhope.nxtes.sync.SyncConflicts
import net.mildhope.nxtes.ui.NxtesTheme
import net.mildhope.nxtes.viewer.DocumentViewer
import net.mildhope.nxtes.viewer.ViewportState

class MainActivity : ComponentActivity() {
    private val openRequests = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) importShared(intent)
        setContent {
            NxtesTheme {
                NxtesRoot(openRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importShared(intent)
    }

    /** Imports PDFs, images and Flexcil backups sent or opened from other apps, and opens the document if there was just one. */
    private fun importShared(intent: Intent) {
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val (flexcil, files) = uris.partition { isFlexcil(this@MainActivity, it) }
            flexcil.forEach { importFlexcil(this@MainActivity, it, folderId = null) }
            if (files.isEmpty()) return@launch
            val imported = importFiles(this@MainActivity, files, folderId = null)
            imported.singleOrNull()?.takeIf { flexcil.isEmpty() }?.let { openRequests.value = it.id }
        }
    }
}

@Composable
private fun NxtesRoot(openRequests: MutableStateFlow<String?>) {
    val app = LocalContext.current.nxtes
    val library by app.library.state.collectAsState()
    val openRequest by openRequests.collectAsState()
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var documentId by rememberSaveable { mutableStateOf<String?>(null) }
    var tabIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()
    val visibleFolder = folderId?.takeIf { library.folder(it) != null && !library.isInTrash(it) }
    val openDocument = library.document(documentId)?.takeIf { it.trashedAt == null && !library.isInTrash(it.folderId) }

    fun open(id: String) {
        documentId = id
        if (id !in tabIds) tabIds = tabIds + id
        scope.launch(Dispatchers.IO) { runCatching { app.library.markOpened(id) } }
    }

    LaunchedEffect(openRequest) {
        openRequest?.let {
            open(it)
            openRequests.value = null
        }
    }

    val viewports = remember { mutableMapOf<String, ViewportState>() }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { app.sync.setForeground(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { app.sync.setForeground(false) }
    SyncConflicts()

    val tabs = tabIds.mapNotNull { id -> library.document(id)?.takeIf { it.trashedAt == null && !library.isInTrash(it.folderId) } }

    if (openDocument != null) {
        DocumentViewer(
            doc = openDocument,
            viewport = viewports.getOrPut(openDocument.id) { ViewportState() },
            tabs = tabs.ifEmpty { listOf(openDocument) },
            onSelectTab = ::open,
            onCloseTab = { id ->
                val index = tabIds.indexOf(id)
                tabIds = tabIds - id
                viewports.remove(id)
                if (id == documentId) documentId = tabIds.getOrNull(index.coerceAtMost(tabIds.lastIndex))
            },
            onCloseOtherTabs = { id ->
                tabIds.filter { it != id }.forEach { viewports.remove(it) }
                tabIds = listOf(id)
                documentId = id
            },
            onCloseAllTabs = {
                viewports.clear()
                tabIds = emptyList()
                documentId = null
            },
            onOpenDocument = { id, page ->
                open(id)
                viewports.getOrPut(id) { ViewportState() }.goToPage(page)
            },
            onBack = { documentId = null },
        )
    } else {
        LibraryScreen(
            library = library,
            folderId = visibleFolder,
            onOpenFolder = { folderId = it },
            onOpenDocument = ::open,
            onOpenPage = { id, page ->
                open(id)
                viewports.getOrPut(id) { ViewportState() }.goToPage(page)
            },
        )
    }
}
