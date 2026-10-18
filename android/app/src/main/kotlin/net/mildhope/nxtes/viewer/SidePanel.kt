package net.mildhope.nxtes.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.nxtes
import net.mildhope.nxtes.pdf.OutlineEntry

enum class SideTab { PAGES, OUTLINE }

private const val PAGE_THUMBNAIL_PX = 240

/** The panel that slides in from the right: page thumbnails and the PDF outline. */
@Composable
fun SidePanel(
    doc: Document,
    viewport: ViewportState,
    tab: SideTab,
    onTab: (SideTab) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(shadowElevation = 8.dp, modifier = modifier.width(320.dp).fillMaxHeight()) {
        Column {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                SideTabButton(painterResource(R.drawable.ic_grid_view), stringResource(R.string.pages), tab == SideTab.PAGES) { onTab(SideTab.PAGES) }
                SideTabButton(rememberVectorPainter(Icons.AutoMirrored.Filled.List), stringResource(R.string.outline), tab == SideTab.OUTLINE) { onTab(SideTab.OUTLINE) }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (tab) {
                SideTab.PAGES -> PageGrid(doc, viewport)
                SideTab.OUTLINE -> Outline(doc, viewport)
            }
        }
    }
}

@Composable
private fun RowScope.SideTabButton(icon: Painter, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = label, onClick = onClick),
    ) {
        Icon(icon, label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun PageGrid(doc: Document, viewport: ViewportState) {
    val grid = rememberLazyGridState()
    LaunchedEffect(doc.id) { grid.scrollToItem(viewport.currentPage) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = grid,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(doc.pages.size, key = { doc.pages[it].id }) { index ->
            val current = index == viewport.currentPage
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { viewport.goToPage(index) }) {
                PageThumbnail(
                    doc,
                    index,
                    Modifier.border(
                        if (current) 2.dp else 1.dp,
                        if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PageThumbnail(doc: Document, index: Int, modifier: Modifier) {
    val context = LocalContext.current
    val page = doc.pages[index]
    val bitmap by produceState<ImageBitmap?>(null, doc.id, doc.modified, index) {
        value = runCatching { context.nxtes.thumbnails.get(doc, PAGE_THUMBNAIL_PX, index) }.getOrNull()?.asImageBitmap()
    }
    val box = modifier.fillMaxWidth().aspectRatio(page.width / page.height).background(Color.White)
    val image = bitmap
    if (image == null) Box(box) else Image(image, null, contentScale = ContentScale.FillBounds, modifier = box)
}

@Composable
private fun Outline(doc: Document, viewport: ViewportState) {
    val context = LocalContext.current
    val entries by produceState<List<OutlineEntry>?>(null, doc.id) {
        val source = doc.pages.firstNotNullOfOrNull { it.pdf }
        value = if (source == null) {
            emptyList()
        } else {
            runCatching { context.nxtes.pdf.outline(context.nxtes.library.blobFile(doc.id, source.blob)) }.getOrDefault(emptyList())
        }
    }
    val list = entries ?: return
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_outline), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(list) { _, entry ->
            val pageIndex = doc.pages.indexOfFirst { it.pdf?.index == entry.pdfPage }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = pageIndex >= 0) { viewport.goToPage(pageIndex) }
                    .padding(start = (16 + entry.depth * 16).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (pageIndex >= 0) {
                    Text("${pageIndex + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
