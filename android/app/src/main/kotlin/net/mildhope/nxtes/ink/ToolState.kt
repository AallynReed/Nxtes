package net.mildhope.nxtes.ink

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.ink.brush.Brush
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.mildhope.nxtes.data.LineStyle
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.data.ShapeKind
import net.mildhope.nxtes.edit.LassoType

/** A pen on the toolbar. Shape pens also carry the shape they draw and its style. */
@Serializable
data class PenPreset(
    val pen: Pen,
    val color: Int,
    val size: Float,
    /** The shape a shape pen draws, or null to draw freely and recognise it. */
    val shape: ShapeKind? = ShapeKind.RECTANGLE,
    val fill: Int = 0,
    val line: LineStyle = LineStyle.SOLID,
    val sides: Int = 5,
) {
    val brush: Brush by lazy { Brushes.brush(pen, color, size) }

    /** Allowed brush sizes in points. */
    val sizeRange: ClosedFloatingPointRange<Float>
        get() = when (pen) {
            Pen.BALLPOINT -> 0.4f..6f
            Pen.FOUNTAIN, Pen.PENCIL -> 0.6f..8f
            Pen.MARKER -> 2f..16f
            Pen.HIGHLIGHTER -> 4f..30f
            Pen.SHAPE -> 0.5f..12f
        }
}

enum class Tool { PEN, ERASER, LASSO, TEXT, NOTE, STICKER, LASER }

/** The laser pointer shows a dot, or a line that fades behind it. */
enum class LaserMode { DOT, LINE }

/** A sticker to stamp: one Nxtes draws, by name, or an image from a pack, with its size in pixels. */
@Serializable
data class StickerRef(val basic: String? = null, val blob: String? = null, val width: Int = 1, val height: Int = 1)

/** Font, size and colours for text boxes. */
@Serializable
data class TextStyle(
    val font: String = "sans-serif",
    val size: Float = 14f,
    val color: Int = 0xFF000000.toInt(),
    val background: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
)

/** A lasso is drawn freehand or as a box. */
enum class LassoShape { FREE, RECT }

/** Pen mode draws; gesture mode only navigates. */
enum class InputMode { PEN, GESTURE }

/** The drawing tools and input options, remembered across documents and restarts. */
@Stable
class ToolState(private val prefs: SharedPreferences) {
    var presets by mutableStateOf(loadPresets())
        private set
    var selected by mutableIntStateOf(prefs.getInt(KEY_SELECTED, 0).coerceIn(presets.indices))
        private set
    var tool by mutableStateOf(Tool.PEN)
        private set
    var mode by mutableStateOf(runCatching { InputMode.valueOf(prefs.getString(KEY_MODE, null)!!) }.getOrDefault(InputMode.PEN))
        private set
    var eraserMode by mutableStateOf(
        runCatching { EraserMode.valueOf(prefs.getString(KEY_ERASER_MODE, null)!!) }.getOrDefault(EraserMode.STROKE),
    )
        private set

    /** Eraser radius on screen, so it feels the same at any zoom. */
    var eraserRadiusDp by mutableFloatStateOf(prefs.getFloat(KEY_ERASER_RADIUS, 10f))
        private set
    var drawWithFinger by mutableStateOf(prefs.getBoolean(KEY_FINGER, false))
        private set
    var lassoShape by mutableStateOf(runCatching { LassoShape.valueOf(prefs.getString(KEY_LASSO_SHAPE, null)!!) }.getOrDefault(LassoShape.FREE))
        private set
    var lassoTypes by mutableStateOf(
        prefs.getStringSet(KEY_LASSO_TYPES, null)?.mapNotNull { runCatching { LassoType.valueOf(it) }.getOrNull() }?.toSet()
            ?: LassoType.entries.toSet(),
    )
        private set

    /** Clear the selection after moving or changing it. */
    var autoDeselect by mutableStateOf(prefs.getBoolean(KEY_AUTO_DESELECT, false))
        private set

    /** The style new text boxes start with: the last one used. */
    var textStyle by mutableStateOf(
        runCatching { json.decodeFromString<TextStyle>(prefs.getString(KEY_TEXT_STYLE, null)!!) }.getOrDefault(TextStyle()),
    )
        private set

    fun updateTextStyle(style: TextStyle) {
        if (style == textStyle) return
        textStyle = style
        prefs.edit { putString(KEY_TEXT_STYLE, json.encodeToString(style)) }
    }

    var laserMode by mutableStateOf(runCatching { LaserMode.valueOf(prefs.getString(KEY_LASER_MODE, null)!!) }.getOrDefault(LaserMode.LINE))
        private set
    var laserColor by mutableIntStateOf(prefs.getInt(KEY_LASER_COLOR, LASER_COLORS[0]))
        private set

    fun updateLaser(mode: LaserMode, colour: Int) {
        laserMode = mode
        laserColor = colour
        prefs.edit {
            putString(KEY_LASER_MODE, mode.name)
            putInt(KEY_LASER_COLOR, colour)
        }
    }

    /** Hides everything written on the pages, for reading or presenting; only the laser still draws. Not kept across restarts. */
    var annotationsHidden by mutableStateOf(false)
        private set

    fun toggleAnnotations() {
        annotationsHidden = !annotationsHidden
    }

    /** The colour new sticky notes get. */
    var noteColor by mutableIntStateOf(prefs.getInt(KEY_NOTE_COLOR, NOTE_COLORS[0]))
        private set

    fun updateNoteColor(colour: Int) {
        noteColor = colour
        prefs.edit { putInt(KEY_NOTE_COLOR, colour) }
    }

    /** Stickers stamped lately, newest first; the first is the one the sticker tool stamps. */
    var recentStickers by mutableStateOf(
        runCatching { json.decodeFromString<List<StickerRef>>(prefs.getString(KEY_STICKERS, null)!!) }.getOrDefault(emptyList()),
    )
        private set

    val sticker: StickerRef get() = recentStickers.firstOrNull() ?: StickerRef(basic = "star")

    /** Picks the sticker to stamp and selects the sticker tool. */
    fun armSticker(ref: StickerRef) {
        saveStickers(listOf(ref) + recentStickers.filter { it != ref })
        selectTool(Tool.STICKER)
    }

    /** Drops a deleted sticker from the recent ones. */
    fun forgetSticker(blob: String) = saveStickers(recentStickers.filter { it.blob != blob })

    private fun saveStickers(list: List<StickerRef>) {
        recentStickers = list.take(MAX_RECENT_STICKERS)
        prefs.edit { putString(KEY_STICKERS, json.encodeToString(recentStickers)) }
    }

    /** Shapes a pen stroke turns into when the pen is held still at its end. */
    var convertShapes by mutableStateOf(
        prefs.getStringSet(KEY_CONVERT, null)?.mapNotNull { runCatching { ShapeKind.valueOf(it) }.getOrNull() }?.toSet() ?: CONVERTIBLE,
    )
        private set

    fun toggleConvert(kind: ShapeKind) {
        convertShapes = if (kind in convertShapes) convertShapes - kind else convertShapes + kind
        prefs.edit { putStringSet(KEY_CONVERT, convertShapes.map { it.name }.toSet()) }
    }

    val pen: PenPreset get() = presets[selected]

    fun selectPen(index: Int) {
        selected = index
        tool = Tool.PEN
        applyMode(InputMode.PEN)
        prefs.edit { putInt(KEY_SELECTED, index) }
    }

    fun updatePen(index: Int, preset: PenPreset) {
        val size = preset.size.coerceIn(preset.sizeRange)
        presets = presets.toMutableList().also { it[index] = preset.copy(size = size) }
        prefs.edit { putString(KEY_PRESETS, json.encodeToString(presets)) }
    }

    fun selectEraser() = selectTool(Tool.ERASER)

    fun selectTool(newTool: Tool) {
        tool = newTool
        applyMode(InputMode.PEN)
    }

    fun updateLasso(shape: LassoShape, types: Set<LassoType>, deselect: Boolean) {
        lassoShape = shape
        lassoTypes = types
        autoDeselect = deselect
        prefs.edit {
            putString(KEY_LASSO_SHAPE, shape.name)
            putStringSet(KEY_LASSO_TYPES, types.map { it.name }.toSet())
            putBoolean(KEY_AUTO_DESELECT, deselect)
        }
    }

    fun toggleMode() = applyMode(if (mode == InputMode.PEN) InputMode.GESTURE else InputMode.PEN)

    private fun applyMode(newMode: InputMode) {
        mode = newMode
        prefs.edit { putString(KEY_MODE, newMode.name) }
    }

    /** Adds a copy of the selected pen at the end of the toolbar and selects it. */
    fun addPen() {
        if (presets.size >= MAX_PENS) return
        presets = presets + pen
        prefs.edit { putString(KEY_PRESETS, json.encodeToString(presets)) }
        selectPen(presets.lastIndex)
    }

    fun removePen(index: Int) {
        if (presets.size <= 1) return
        presets = presets.filterIndexed { i, _ -> i != index }
        prefs.edit { putString(KEY_PRESETS, json.encodeToString(presets)) }
        selectPen(selected.coerceAtMost(presets.lastIndex))
    }

    fun updateEraser(mode: EraserMode, radiusDp: Float) {
        eraserMode = mode
        eraserRadiusDp = radiusDp
        prefs.edit {
            putString(KEY_ERASER_MODE, mode.name)
            putFloat(KEY_ERASER_RADIUS, radiusDp)
        }
    }

    fun toggleDrawWithFinger() {
        drawWithFinger = !drawWithFinger
        prefs.edit { putBoolean(KEY_FINGER, drawWithFinger) }
    }

    private fun loadPresets(): List<PenPreset> =
        prefs.getString(KEY_PRESETS, null)
            ?.let { runCatching { json.decodeFromString<List<PenPreset>>(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PRESETS

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val KEY_PRESETS = "presets"
        private const val KEY_SELECTED = "selected"
        private const val KEY_ERASER_MODE = "eraser_mode"
        private const val KEY_ERASER_RADIUS = "eraser_radius"
        private const val KEY_FINGER = "draw_with_finger"
        private const val KEY_MODE = "mode"
        private const val KEY_LASSO_SHAPE = "lasso_shape"
        private const val KEY_LASSO_TYPES = "lasso_types"
        private const val KEY_AUTO_DESELECT = "auto_deselect"
        private const val KEY_CONVERT = "convert_shapes"
        private const val KEY_TEXT_STYLE = "text_style"
        private const val KEY_NOTE_COLOR = "note_color"
        private const val KEY_LASER_MODE = "laser_mode"
        private const val KEY_LASER_COLOR = "laser_color"
        private const val KEY_STICKERS = "recent_stickers"
        private const val MAX_RECENT_STICKERS = 16

        val LASER_COLORS = listOf(0xFFFF2D2D, 0xFF2DD36F, 0xFF2D7DFF, 0xFFFFC400, 0xFFC04DFF, 0xFFFF6FB5).map { it.toInt() }
        val NOTE_COLORS = listOf(0xFFFFE082, 0xFFFFCC80, 0xFFF8BBD0, 0xFFD1C4E9, 0xFFB3E5FC, 0xFFC5E1A5).map { it.toInt() }

        /** Shapes hold-to-convert can make. */
        val CONVERTIBLE = setOf(ShapeKind.LINE, ShapeKind.ELLIPSE, ShapeKind.TRIANGLE, ShapeKind.RECTANGLE, ShapeKind.POLYGON)
        const val MAX_PENS = 30

        val DEFAULT_PRESETS = listOf(
            PenPreset(Pen.BALLPOINT, 0xFF1A1A1A.toInt(), 1.2f),
            PenPreset(Pen.BALLPOINT, 0xFF1E4FD8.toInt(), 1.2f),
            PenPreset(Pen.FOUNTAIN, 0xFF000000.toInt(), 2f),
            PenPreset(Pen.PENCIL, 0xFF5F6368.toInt(), 1.6f),
            PenPreset(Pen.MARKER, 0xFFD93025.toInt(), 4f),
            PenPreset(Pen.HIGHLIGHTER, 0x80FFD600.toInt(), 12f),
            PenPreset(Pen.SHAPE, 0xFF1E4FD8.toInt(), 1.5f),
        )

        val PALETTE = listOf(
            0xFF000000, 0xFF5F6368, 0xFF1E4FD8, 0xFF4FC3F7, 0xFF188038, 0xFF00897B,
            0xFFD93025, 0xFFF57C00, 0xFFFFD600, 0xFF8E24AA, 0xFFE91E63, 0xFF795548,
        ).map { it.toInt() }
    }
}

/** The style of a text box, to start the next one with. */
val net.mildhope.nxtes.data.TextBox.style get() = TextStyle(font, size, color, background, bold, italic, underline)
