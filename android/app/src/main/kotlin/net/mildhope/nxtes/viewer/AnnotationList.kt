package net.mildhope.nxtes.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.MarkKind
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.data.Picture
import net.mildhope.nxtes.data.Shape
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.ink.PageSession

/** Kinds of annotation the list can be narrowed to. */
enum class AnnotationKind(val label: Int) {
    ALL(R.string.filter_all),
    HIGHLIGHT(R.string.annotations_highlights),
    TEXT(R.string.lasso_type_text),
    NOTE(R.string.lasso_type_note),
    IMAGE(R.string.lasso_type_image),
    SHAPE(R.string.lasso_type_shape),
    INK(R.string.annotations_ink),
}

/** One row of the list: what it is, a line describing it, its colour, and the page it's on. */
private class AnnotationRow(val key: String, val kind: AnnotationKind, val pageIndex: Int, val text: String, val color: Int)

/** Everything written on the document, page by page, narrowed by kind; tapping a row goes to its page. */
@Composable
fun ColumnScope.AnnotationList(doc: Document, ink: PageSession, viewport: ViewportState) {
    LaunchedEffect(doc.id, doc.pages) { ink.ensureLoaded(doc.pages.map { it.id }) }
    var filter by rememberSaveable { mutableStateOf(AnnotationKind.ALL) }
    val resources = LocalContext.current.resources
    val untitledNote = stringResource(R.string.empty_note)
    val markNames = mapOf(
        MarkKind.HIGHLIGHT to stringResource(R.string.highlight),
        MarkKind.UNDERLINE to stringResource(R.string.underline),
        MarkKind.STRIKEOUT to stringResource(R.string.strike_through),
    )
    val image = stringResource(R.string.image)
    val sticker = stringResource(R.string.sticker)
    val shapeNames = net.mildhope.nxtes.data.ShapeKind.entries.associateWith { stringResource(shapeName(it)) }
    val rows = doc.pages.flatMapIndexed { i, page ->
        val content = ink.content(page.id)
        buildList {
            content.marks.forEach { add(AnnotationRow(it.id, AnnotationKind.HIGHLIGHT, i, "${markNames.getValue(it.kind)}: ${it.text}", it.color)) }
            content.objects.forEach { o ->
                when (o) {
                    is TextBox -> add(AnnotationRow(o.id, AnnotationKind.TEXT, i, o.text, o.color))
                    is Picture -> add(AnnotationRow(o.id, AnnotationKind.IMAGE, i, if (o.sticker) sticker else image, 0))
                    is Shape -> add(AnnotationRow(o.id, AnnotationKind.SHAPE, i, shapeNames.getValue(o.kind), o.color))
                }
            }
            content.notes.forEach { add(AnnotationRow(it.id, AnnotationKind.NOTE, i, it.text.ifBlank { untitledNote }, it.color)) }
            val strokes = content.strokes.filter { it.pen != Pen.HIGHLIGHTER }
            if (strokes.isNotEmpty()) {
                add(AnnotationRow("ink-${page.id}", AnnotationKind.INK, i, resources.getQuantityString(R.plurals.stroke_count, strokes.size, strokes.size), strokes.first().color))
            }
            val highlighter = content.strokes.filter { it.pen == Pen.HIGHLIGHTER }
            if (highlighter.isNotEmpty()) {
                add(AnnotationRow("marker-${page.id}", AnnotationKind.INK, i, resources.getQuantityString(R.plurals.highlighter_count, highlighter.size, highlighter.size), highlighter.first().color))
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AnnotationKind.entries.forEach { kind ->
            FilterChip(selected = filter == kind, onClick = { filter = kind }, label = { Text(stringResource(kind.label)) })
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val shown = rows.filter { filter == AnnotationKind.ALL || it.kind == filter }
    if (shown.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.annotations_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.weight(1f)) {
        var lastPage = -1
        shown.forEach { row ->
            if (row.pageIndex != lastPage) {
                lastPage = row.pageIndex
                item(key = "page-${row.pageIndex}") {
                    Text(
                        stringResource(R.string.page_title, row.pageIndex + 1),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            item(key = row.key) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable { viewport.goToPage(row.pageIndex) }
                        .padding(horizontal = 16.dp),
                ) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (row.color == 0) MaterialTheme.colorScheme.outlineVariant else Color(row.color).copy(alpha = 1f)),
                    )
                    Text(row.text.lines().joinToString(" "), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}
