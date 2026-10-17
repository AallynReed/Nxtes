package net.mildhope.nxtes.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.Folder
import net.mildhope.nxtes.data.LibraryItem
import net.mildhope.nxtes.nxtes

private const val CELL_ASPECT = 0.75f
private const val THUMBNAIL_WIDTH_PX = 360

@Composable
fun LibraryCell(
    item: LibraryItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(CELL_ASPECT),
            contentAlignment = Alignment.BottomCenter,
        ) {
            when (item) {
                is Folder -> Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize(),
                )
                is Document -> Thumbnail(item)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(top = 4.dp),
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.move)) }, onClick = { menuOpen = false; onMove() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_to_trash)) }, onClick = { menuOpen = false; onTrash() })
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(doc: Document) {
    val context = LocalContext.current
    val first = doc.pages.firstOrNull() ?: return
    val ratio = first.width / first.height
    val bitmap by produceState<ImageBitmap?>(null, doc.id, doc.modified) {
        value = runCatching { context.nxtes.thumbnails.get(doc, THUMBNAIL_WIDTH_PX) }.getOrNull()?.asImageBitmap()
    }
    val page = Modifier
        .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < CELL_ASPECT)
        .shadow(2.dp, RoundedCornerShape(2.dp))
        .background(Color.White)
    val image = bitmap
    if (image == null) {
        Box(page)
    } else {
        Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = page)
    }
}
