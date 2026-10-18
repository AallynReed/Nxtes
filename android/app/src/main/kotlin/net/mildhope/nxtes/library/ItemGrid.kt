package net.mildhope.nxtes.library

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.Folder
import net.mildhope.nxtes.data.Library
import net.mildhope.nxtes.data.LibraryItem
import net.mildhope.nxtes.notes.CoverArt
import net.mildhope.nxtes.notes.Covers
import net.mildhope.nxtes.nxtes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MenuAction(val label: String, val run: () -> Unit)

val DEFAULT_FOLDER_COLOR = 0xFF7FB2F0.toInt()
val FOLDER_COLORS = listOf(
    0xFF7FB2F0, 0xFFF48FB1, 0xFFFFB74D, 0xFFFFD54F, 0xFF81C784, 0xFF4DB6AC, 0xFFB39DDB, 0xFF90A4AE,
).map { it.toInt() }
private val FAVORITE_STAR = Color(0xFFFFC107)
private const val THUMBNAIL_WIDTH_PX = 360

/** Library items as a grid of cells or a list of rows, folders first. */
@Composable
fun ItemGrid(
    items: List<LibraryItem>,
    library: Library,
    listView: Boolean,
    onClick: (LibraryItem) -> Unit,
    onFavorite: ((LibraryItem) -> Unit)?,
    actions: (LibraryItem) -> List<MenuAction>,
    modifier: Modifier = Modifier,
) {
    if (listView) {
        LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
            items(items, key = { it.id }) { item ->
                ItemRow(item, info(item, library), { onClick(item) }, onFavorite?.let { { it(item) } }, actions(item))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = modifier,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(items, key = { it.id }) { item ->
                ItemCell(item, info(item, library), { onClick(item) }, onFavorite?.let { { it(item) } }, actions(item))
            }
        }
    }
}

@Composable
private fun ItemCell(item: LibraryItem, info: String, onClick: () -> Unit, onFavorite: (() -> Unit)?, actions: List<MenuAction>) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.8f).padding(8.dp), contentAlignment = Alignment.BottomCenter) {
            ItemPreview(item)
            if (onFavorite != null) {
                FavoriteStar(item.favorite, onFavorite, Modifier.align(Alignment.TopEnd))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
            ItemMenu(menuOpen, { menuOpen = it }, actions)
        }
        Text(info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ItemRow(item: LibraryItem, info: String, onClick: () -> Unit, onFavorite: (() -> Unit)?, actions: List<MenuAction>) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
    ) {
        Box(Modifier.width(40.dp).height(52.dp), contentAlignment = Alignment.Center) { ItemPreview(item) }
        Column(Modifier.weight(1f)) {
            Text(item.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onFavorite != null) FavoriteStar(item.favorite, onFavorite)
        ItemMenu(menuOpen, { menuOpen = it }, actions)
    }
}

@Composable
private fun ItemMenu(open: Boolean, setOpen: (Boolean) -> Unit, actions: List<MenuAction>) {
    Box {
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.more_options),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp).clickable { setOpen(true) }.padding(2.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { setOpen(false) }) {
            actions.forEach { action ->
                DropdownMenuItem(text = { Text(action.label) }, onClick = { setOpen(false); action.run() })
            }
        }
    }
}

@Composable
private fun FavoriteStar(favorite: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Icon(
        Icons.Default.Star,
        contentDescription = stringResource(if (favorite) R.string.remove_from_favorites else R.string.add_to_favorites),
        tint = if (favorite) FAVORITE_STAR else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        modifier = modifier.size(32.dp).clickable(onClick = onToggle).padding(4.dp),
    )
}

/** A folder icon in the folder's colour, or the document's first-page thumbnail. */
@Composable
fun ItemPreview(item: LibraryItem, modifier: Modifier = Modifier) {
    when (item) {
        is Folder -> Icon(
            painterResource(R.drawable.ic_folder),
            contentDescription = null,
            tint = Color(item.color ?: DEFAULT_FOLDER_COLOR),
            modifier = modifier.fillMaxSize(),
        )
        is Document -> DocumentThumbnail(item, modifier)
    }
}

@Composable
private fun DocumentThumbnail(doc: Document, modifier: Modifier) {
    val context = LocalContext.current
    val cover = Covers.get(doc.cover)
    if (cover != null) {
        val ratio = 1 / 1.414f
        CoverArt(cover, modifier.aspectRatio(ratio, matchHeightConstraintsFirst = true).shadow(3.dp, RoundedCornerShape(2.dp)))
        return
    }
    val first = doc.pages.firstOrNull() ?: return
    val ratio = first.width / first.height
    val bitmap by produceState<ImageBitmap?>(null, doc.id, doc.modified) {
        value = runCatching { context.nxtes.thumbnails.get(doc, THUMBNAIL_WIDTH_PX) }.getOrNull()?.asImageBitmap()
    }
    val page = modifier
        .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 0.8f)
        .shadow(3.dp, RoundedCornerShape(2.dp))
        .background(Color.White)
    val image = bitmap
    if (image == null) Box(page) else Image(image, null, contentScale = ContentScale.FillBounds, modifier = page)
}

@Composable
private fun info(item: LibraryItem, library: Library): String {
    val context = LocalContext.current
    val date = remember(item.modified) { DATE_FORMAT.format(Date(item.modified)) }
    return when (item) {
        is Folder -> pluralStringResource(R.plurals.item_count, library.itemCount(item.id), library.itemCount(item.id)) + "  " + date
        is Document -> {
            val size by produceState(0L, item.id, item.modified) {
                value = withContext(Dispatchers.IO) { context.nxtes.library.documentSize(item.id) }
            }
            Formatter.formatShortFileSize(context, size) + "  " + date
        }
    }
}

private val DATE_FORMAT = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier)
}
