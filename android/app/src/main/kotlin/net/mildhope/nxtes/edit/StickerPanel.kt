package net.mildhope.nxtes.edit

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.data.Sticker
import net.mildhope.nxtes.data.StickerPack
import net.mildhope.nxtes.ink.StickerRef
import net.mildhope.nxtes.ink.ToolState
import net.mildhope.nxtes.library.ConfirmDialog
import net.mildhope.nxtes.library.NameDialog
import net.mildhope.nxtes.library.runLibraryAction
import net.mildhope.nxtes.nxtes
import net.mildhope.nxtes.page.BasicStickers
import net.mildhope.nxtes.page.decodeImage
import net.mildhope.nxtes.page.keepBitmap

/** Sticker images are kept at most this many pixels on their long side. */
const val STICKER_PIXELS = 512

private const val RECENT = "recent"
private const val BASIC = "basic"

fun Sticker.ref() = StickerRef(blob = blob, width = width, height = height)

/** Adds the image at [uri] to a pack, scaled down to [STICKER_PIXELS]. Blocks. */
fun importSticker(context: Context, uri: Uri, packId: String, store: LibraryStore): Sticker {
    val bitmap = decodeImage(context, uri, STICKER_PIXELS)
    try {
        return keepBitmap(context, bitmap) { store.addSticker(packId, it, bitmap.width, bitmap.height) }
    } finally {
        bitmap.recycle()
    }
}

/**
 * The sticker tool's panel: stickers used lately, the basics Nxtes draws, and the user's packs.
 * Picking one arms the sticker tool. Touching and holding a pack or one of its stickers offers to
 * rename or delete it.
 */
@Composable
fun StickerPanel(tools: ToolState, onPicked: () -> Unit, onAddImage: (packId: String) -> Unit) {
    val context = LocalContext.current
    val library = context.nxtes.library
    val scope = rememberCoroutineScope()
    val packs by library.stickerPacks.collectAsState()
    var tab by rememberSaveable { mutableStateOf(if (tools.recentStickers.isEmpty()) BASIC else RECENT) }
    var newPack by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<StickerPack?>(null) }
    var deleting by remember { mutableStateOf<StickerPack?>(null) }
    val pack = packs.firstOrNull { it.id == tab }

    fun deleteSticker(pack: StickerPack, sticker: Sticker) {
        scope.launch {
            runLibraryAction(context) { deleteSticker(pack.id, sticker.id) }
            if (library.stickerPacks.value.none { p -> p.stickers.any { it.blob == sticker.blob } }) tools.forgetSticker(sticker.blob)
        }
    }

    Column(Modifier.width(352.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            TabChip(stringResource(R.string.stickers_recent), tab == RECENT, onClick = { tab = RECENT })
            TabChip(stringResource(R.string.stickers_basic), tab == BASIC, onClick = { tab = BASIC })
            packs.forEach { p -> PackChip(p, selected = tab == p.id, onSelect = { tab = p.id }, onRename = { renaming = p }, onDelete = { deleting = p }) }
            MenuIcon(rememberVectorPainter(Icons.Default.Add), stringResource(R.string.sticker_pack_new)) { newPack = true }
        }
        val stickers: List<Pair<StickerRef, Sticker?>> = when {
            tab == RECENT -> tools.recentStickers.map { it to null }
            tab == BASIC -> BasicStickers.ids.map { StickerRef(basic = it) to null }
            pack != null -> pack.stickers.map { it.ref() to it }
            else -> emptyList()
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            stickers.forEach { (ref, sticker) ->
                StickerCell(
                    ref,
                    selected = ref == tools.sticker,
                    onClick = {
                        tools.armSticker(ref)
                        onPicked()
                    },
                    onDelete = if (pack != null && sticker != null) ({ deleteSticker(pack, sticker) }) else null,
                )
            }
            if (pack != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .combinedClickable(onClickLabel = stringResource(R.string.sticker_add_image), onClick = { onAddImage(pack.id) }),
                ) { Icon(Icons.Default.Add, stringResource(R.string.sticker_add_image), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (stickers.isEmpty() && pack == null) {
            Text(stringResource(R.string.no_stickers), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (packs.isNotEmpty()) {
            Text(
                stringResource(R.string.sticker_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
    if (newPack) {
        NameDialog(stringResource(R.string.sticker_pack_new), stringResource(R.string.sticker_pack_name), onConfirm = { name ->
            newPack = false
            scope.launch { runLibraryAction(context) { addStickerPack(name) }?.let { tab = it.id } }
        }, onDismiss = { newPack = false })
    }
    renaming?.let { p ->
        NameDialog(stringResource(R.string.rename), p.name, onConfirm = { name ->
            renaming = null
            scope.launch { runLibraryAction(context) { renameStickerPack(p.id, name) } }
        }, onDismiss = { renaming = null })
    }
    deleting?.let { p ->
        ConfirmDialog(stringResource(R.string.delete_template_title, p.name), stringResource(R.string.delete), onConfirm = {
            deleting = null
            tab = BASIC
            scope.launch {
                runLibraryAction(context) { deleteStickerPack(p.id) }
                p.stickers.filter { s -> library.stickerPacks.value.none { o -> o.stickers.any { it.blob == s.blob } } }.forEach { tools.forgetSticker(it.blob) }
            }
        }, onDismiss = { deleting = null })
    }
}

@Composable
private fun PackChip(pack: StickerPack, selected: Boolean, onSelect: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TabChip(pack.name, selected, onClick = onSelect, onLongClick = { menu = true })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = {
                menu = false
                onRename()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                menu = false
                onDelete()
            })
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StickerCell(ref: StickerRef, selected: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(shape)
                .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                .combinedClickable(onClick = onClick, onLongClick = onDelete?.let { { menu = true } }),
        ) { StickerImage(ref, Modifier.size(44.dp)) }
        if (onDelete != null) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}

/** A sticker drawn to fit [modifier]'s square. */
@Composable
fun StickerImage(ref: StickerRef, modifier: Modifier) {
    val basic = ref.basic
    if (basic != null) {
        Canvas(modifier) { drawIntoCanvas { BasicStickers.draw(basic, it.nativeCanvas, size.minDimension) } }
        return
    }
    val library = LocalContext.current.nxtes.library
    val bitmap by produceState<ImageBitmap?>(null, ref.blob) {
        value = withContext(Dispatchers.IO) { ref.blob?.let { BitmapFactory.decodeFile(library.stickerFile(it).path) }?.asImageBitmap() }
    }
    bitmap?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = modifier) } ?: Box(modifier)
}
