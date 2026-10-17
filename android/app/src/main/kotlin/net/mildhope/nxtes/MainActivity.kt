package net.mildhope.nxtes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import net.mildhope.nxtes.library.LibraryScreen
import net.mildhope.nxtes.library.TrashScreen
import net.mildhope.nxtes.ui.NxtesTheme
import net.mildhope.nxtes.viewer.DocumentViewer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NxtesTheme {
                NxtesRoot()
            }
        }
    }
}

@Composable
private fun NxtesRoot() {
    val app = LocalContext.current.nxtes
    val library by app.library.state.collectAsState()
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var documentId by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleFolder = folderId?.takeIf { library.folder(it) != null && !library.isInTrash(it) }
    val openDocument = library.document(documentId)?.takeIf { it.trashedAt == null && !library.isInTrash(it.folderId) }

    when {
        openDocument != null -> DocumentViewer(doc = openDocument, onBack = { documentId = null })
        showTrash -> TrashScreen(library = library, onBack = { showTrash = false })
        else -> LibraryScreen(
            library = library,
            folderId = visibleFolder,
            onOpenFolder = { folderId = it },
            onOpenDocument = { documentId = it },
            onShowTrash = { showTrash = true },
        )
    }
}
