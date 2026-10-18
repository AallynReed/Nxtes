package net.mildhope.nxtes.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.Mask
import net.mildhope.nxtes.edit.SelectionState
import net.mildhope.nxtes.edit.score
import net.mildhope.nxtes.ink.PageSession
import kotlin.math.roundToInt

private enum class MaskSort { PAGE, HIGHEST, LOWEST }

private class MaskRow(val pageIndex: Int, val mask: Mask) {
    /** The share answered right, or null before the first try. */
    val rate: Float? get() = if (mask.tries == 0) null else mask.correct.toFloat() / mask.tries
}

/** Every mask in the document with its score, the document's correct rate, and reveal or cover all. */
@Composable
fun ColumnScope.MaskList(doc: Document, ink: PageSession, selection: SelectionState, viewport: ViewportState) {
    LaunchedEffect(doc.id, doc.pages) { ink.ensureLoaded(doc.pages.map { it.id }) }
    var sort by rememberSaveable { mutableStateOf(MaskSort.PAGE) }
    val rows = doc.pages.flatMapIndexed { i, page -> ink.content(page.id).masks.map { MaskRow(i, it) } }
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.masks_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val (right, tries) = score(rows.map { it.mask })
    Text(
        if (tries == 0) stringResource(R.string.masks_rate_none) else stringResource(R.string.masks_rate, (right * 100f / tries).roundToInt(), right, tries),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        listOf(MaskSort.PAGE to R.string.sort_page, MaskSort.HIGHEST to R.string.sort_highest, MaskSort.LOWEST to R.string.sort_lowest).forEach { (option, label) ->
            FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(stringResource(label)) })
        }
    }
    Row(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { selection.revealAll(rows.map { it.mask.id }) }) { Text(stringResource(R.string.reveal_all)) }
        TextButton(onClick = selection::coverAll) { Text(stringResource(R.string.cover_all)) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val sorted = when (sort) {
        MaskSort.PAGE -> rows
        MaskSort.HIGHEST -> rows.sortedWith(compareByDescending<MaskRow> { it.rate ?: -1f }.thenBy { it.pageIndex })
        MaskSort.LOWEST -> rows.sortedWith(compareBy<MaskRow> { it.rate ?: 2f }.thenBy { it.pageIndex })
    }
    LazyColumn(Modifier.weight(1f)) {
        items(sorted, key = { it.mask.id }) { row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable { viewport.goToPage(row.pageIndex) }
                    .padding(horizontal = 16.dp),
            ) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(Color(row.mask.color)))
                Text(stringResource(R.string.page_title, row.pageIndex + 1), modifier = Modifier.padding(start = 12.dp))
                Spacer(Modifier.weight(1f))
                val rate = row.rate
                Text(
                    if (rate == null) stringResource(R.string.mask_untried) else stringResource(R.string.mask_rate_row, row.mask.correct, row.mask.tries, (rate * 100).roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
