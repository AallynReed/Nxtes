package net.mildhope.nxtes.viewer

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CancellationException
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
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

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        PageCanvas(
            pages = doc.pages,
            renderer = renderer,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}
