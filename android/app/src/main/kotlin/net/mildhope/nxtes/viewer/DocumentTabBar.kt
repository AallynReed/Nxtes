package net.mildhope.nxtes.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document

/** One tab per open document, the active one underlined, with the open count on the right. */
@Composable
fun DocumentTabBar(tabs: List<Document>, activeId: String, onSelect: (String) -> Unit, onClose: (String) -> Unit) {
    var listOpen by remember { mutableStateOf(false) }
    val divider = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(38.dp)) {
            tabs.forEach { doc ->
                val active = doc.id == activeId
                Box(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(doc.id) }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxHeight().padding(start = 12.dp, end = 4.dp),
                    ) {
                        Text(
                            text = doc.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.close_tab),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp).clickable { onClose(doc.id) }.padding(6.dp),
                        )
                    }
                    if (active) {
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.primary))
                    }
                    Box(Modifier.align(Alignment.CenterEnd).width(1.dp).height(20.dp).background(divider))
                }
            }
            Box(Modifier.width(64.dp).fillMaxHeight()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.Center).clickable { listOpen = true }.padding(8.dp),
                ) {
                    Text("${tabs.size}", style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.open_tabs), modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = listOpen, onDismissRequest = { listOpen = false }) {
                    tabs.forEach { doc ->
                        DropdownMenuItem(
                            text = {
                                Text(doc.title, color = if (doc.id == activeId) MaterialTheme.colorScheme.primary else Color.Unspecified)
                            },
                            onClick = {
                                listOpen = false
                                onSelect(doc.id)
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = divider)
    }
}
