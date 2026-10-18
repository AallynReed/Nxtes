package net.mildhope.nxtes.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Library
import net.mildhope.nxtes.data.LibraryItem

enum class LibrarySection { DOCUMENTS, TRASH, RECENTS, FAVORITES }

/** Search, the main sections, and the Recents/Favorites shortcut list. */
@Composable
fun LibrarySideMenu(
    library: Library,
    section: LibrarySection,
    query: String,
    onQuery: (String) -> Unit,
    onSection: (LibrarySection) -> Unit,
    shortcutsShowFavorites: Boolean,
    onShortcutsShowFavorites: (Boolean) -> Unit,
    onOpen: (LibraryItem) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight().width(240.dp).padding(horizontal = 12.dp, vertical = 12.dp)) {
        SearchField(query, onQuery)
        Spacer(Modifier.height(16.dp))
        NavRow(painterResource(R.drawable.ic_document), stringResource(R.string.documents), section == LibrarySection.DOCUMENTS && query.isEmpty()) {
            onQuery("")
            onSection(LibrarySection.DOCUMENTS)
        }
        NavRow(rememberVectorPainter(Icons.Default.Delete), stringResource(R.string.trash_title), section == LibrarySection.TRASH && query.isEmpty()) {
            onQuery("")
            onSection(LibrarySection.TRASH)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            ShortcutTab(painterResource(R.drawable.ic_schedule), stringResource(R.string.recents), !shortcutsShowFavorites) { onShortcutsShowFavorites(false) }
            ShortcutTab(rememberVectorPainter(Icons.Default.Star), stringResource(R.string.favorites), shortcutsShowFavorites) { onShortcutsShowFavorites(true) }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { onQuery(""); onSection(if (shortcutsShowFavorites) LibrarySection.FAVORITES else LibrarySection.RECENTS) },
        ) {
            Text(stringResource(R.string.show_all), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        val shortcuts = if (shortcutsShowFavorites) library.favorites() else library.recents()
        LazyColumn(Modifier.weight(1f)) {
            items(shortcuts, key = { it.id }) { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(item) }.padding(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    Box(Modifier.width(30.dp).height(40.dp), contentAlignment = Alignment.Center) { ItemPreview(item) }
                    Text(item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        NavRow(rememberVectorPainter(Icons.Default.Settings), stringResource(R.string.settings), false, onSettings)
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp),
    ) {
        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.search), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Default.Close, stringResource(R.string.cancel), modifier = Modifier.size(18.dp).clickable { onQuery("") })
        }
    }
}

@Composable
private fun NavRow(icon: Painter, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Text(label, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ShortcutTab(icon: Painter, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(8.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}
