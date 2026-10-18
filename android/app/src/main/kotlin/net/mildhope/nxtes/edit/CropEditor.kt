package net.mildhope.nxtes.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Picture
import net.mildhope.nxtes.nxtes
import net.mildhope.nxtes.page.PageResources
import net.mildhope.nxtes.viewer.PageMapper
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** A picture being cropped, with a box or along a freeform line. */
class CropEdit(val pageId: String, val picture: Picture, val freeform: Boolean)

private enum class CropHandle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

private const val MIN = 0.03f

/**
 * Shows the whole image where the picture sits, with the part being kept bright: a box with corner
 * handles, or a line drawn around what to keep. [onDone] gets the cropped picture, or null to cancel.
 * Touches anywhere are the crop's, so a handle at the image's edge is easy to catch.
 */
@Composable
fun CropEditor(edit: CropEdit, mapper: PageMapper, resources: PageResources, onDone: (Picture?) -> Unit) {
    val index = mapper.indexOf(edit.pageId).takeIf { it >= 0 } ?: return
    val picture = edit.picture
    val frame = Crop.frame(picture)
    val k = mapper.pixelsPerPoint
    val centre = mapper.toScreen(index, Offset(frame.cx, frame.cy))
    val widthPx = (frame.width * k).roundToInt().coerceAtLeast(1)
    val heightPx = (frame.height * k).roundToInt().coerceAtLeast(1)
    val touch = with(LocalDensity.current) { 32.dp.toPx() }
    LocalContext.current.nxtes.assets.loaded
    val bitmap = resources.bitmap(picture.blob)
    var box by remember { mutableStateOf(Rect(picture.cropLeft, picture.cropTop, picture.cropRight, picture.cropBottom)) }
    var path by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val accent = MaterialTheme.colorScheme.primary

    /** A screen point as a fraction of the image, in its own unturned axes. */
    fun fraction(p: Offset): Offset {
        val r = Math.toRadians(-frame.rotation.toDouble())
        val dx = p.x - centre.x
        val dy = p.y - centre.y
        val x = (dx * cos(r) - dy * sin(r)).toFloat() + widthPx / 2f
        val y = (dx * sin(r) + dy * cos(r)).toFloat() + heightPx / 2f
        return Offset(x / widthPx, y / heightPx)
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(edit.freeform, widthPx, heightPx, centre) {
                if (edit.freeform) {
                    detectDragGestures(
                        onDragStart = { path = listOf(fraction(it)) },
                        onDrag = { change, _ ->
                            change.consume()
                            path = path + fraction(change.position)
                        },
                    )
                } else {
                    var handle = CropHandle.NONE
                    detectDragGestures(
                        onDragStart = { at ->
                            val p = fraction(at)
                            val corners = listOf(
                                CropHandle.TOP_LEFT to Offset(box.left, box.top),
                                CropHandle.TOP_RIGHT to Offset(box.right, box.top),
                                CropHandle.BOTTOM_RIGHT to Offset(box.right, box.bottom),
                                CropHandle.BOTTOM_LEFT to Offset(box.left, box.bottom),
                            )
                            handle = corners.firstOrNull { (_, c) -> hypot((c.x - p.x) * widthPx, (c.y - p.y) * heightPx) < touch }?.first
                                ?: if (box.contains(p)) CropHandle.MOVE else CropHandle.NONE
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val now = fraction(change.position)
                            val before = fraction(change.previousPosition)
                            box = moved(box, handle, now.x - before.x, now.y - before.y)
                        },
                    )
                }
            },
    ) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(Constraints.fixed(widthPx, heightPx))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place((centre.x - widthPx / 2f).roundToInt(), (centre.y - heightPx / 2f).roundToInt())
                    }
                }
                .graphicsLayer {
                    rotationZ = frame.rotation
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                },
        ) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(Color.LightGray))
            }
            Canvas(Modifier.fillMaxSize()) {
                if (edit.freeform) drawFreeform(path, accent) else drawBox(box, accent)
            }
        }
        val anchor = Rect(Offset(centre.x - widthPx / 2f, centre.y - heightPx / 2f), Size(widthPx.toFloat(), heightPx.toFloat()))
        AnchoredMenu(anchor, gap = 12.dp) {
            Row(Modifier.padding(horizontal = 4.dp)) {
                TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = {
                    box = Rect(0f, 0f, 1f, 1f)
                    path = emptyList()
                }) { Text(stringResource(R.string.crop_reset)) }
                TextButton(onClick = {
                    onDone(
                        when {
                            !edit.freeform -> Crop.apply(picture, box.left, box.top, box.right, box.bottom)
                            path.size >= 3 -> Crop.freeform(picture, path.flatMap { listOf(it.x, it.y) })
                            else -> Crop.apply(picture, 0f, 0f, 1f, 1f)
                        },
                    )
                }) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

/** The crop box after dragging [handle] by [dx],[dy] (fractions of the image). */
private fun moved(box: Rect, handle: CropHandle, dx: Float, dy: Float): Rect = when (handle) {
    CropHandle.NONE -> box
    CropHandle.MOVE -> box.translate(dx.coerceIn(-box.left, 1f - box.right), dy.coerceIn(-box.top, 1f - box.bottom))
    CropHandle.TOP_LEFT -> box.copy(left = (box.left + dx).coerceIn(0f, box.right - MIN), top = (box.top + dy).coerceIn(0f, box.bottom - MIN))
    CropHandle.TOP_RIGHT -> box.copy(right = (box.right + dx).coerceIn(box.left + MIN, 1f), top = (box.top + dy).coerceIn(0f, box.bottom - MIN))
    CropHandle.BOTTOM_RIGHT -> box.copy(right = (box.right + dx).coerceIn(box.left + MIN, 1f), bottom = (box.bottom + dy).coerceIn(box.top + MIN, 1f))
    CropHandle.BOTTOM_LEFT -> box.copy(left = (box.left + dx).coerceIn(0f, box.right - MIN), bottom = (box.bottom + dy).coerceIn(box.top + MIN, 1f))
}

private fun DrawScope.drawBox(box: Rect, accent: Color) {
    val dim = Color.Black.copy(alpha = 0.45f)
    val r = Rect(box.left * size.width, box.top * size.height, box.right * size.width, box.bottom * size.height)
    drawRect(dim, Offset.Zero, Size(size.width, r.top))
    drawRect(dim, Offset(0f, r.bottom), Size(size.width, size.height - r.bottom))
    drawRect(dim, Offset(0f, r.top), Size(r.left, r.height))
    drawRect(dim, Offset(r.right, r.top), Size(size.width - r.right, r.height))
    drawRect(accent, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
    listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft).forEach {
        drawCircle(Color.White, 9.dp.toPx(), it)
        drawCircle(accent, 9.dp.toPx(), it, style = Stroke(2.dp.toPx()))
    }
}

private fun DrawScope.drawFreeform(path: List<Offset>, accent: Color) {
    if (path.size < 2) {
        drawRect(Color.Black.copy(alpha = 0.25f))
        return
    }
    val line = Path().apply {
        moveTo(path[0].x * size.width, path[0].y * size.height)
        path.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
        close()
    }
    drawPath(line, accent, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))))
}
