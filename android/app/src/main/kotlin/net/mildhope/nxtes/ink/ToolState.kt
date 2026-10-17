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
import net.mildhope.nxtes.data.Pen

@Serializable
data class PenPreset(val pen: Pen, val color: Int, val size: Float) {
    val brush: Brush by lazy { Brushes.brush(pen, color, size) }

    /** Allowed brush sizes in points. */
    val sizeRange: ClosedFloatingPointRange<Float>
        get() = when (pen) {
            Pen.BALLPOINT -> 0.4f..6f
            Pen.FOUNTAIN, Pen.PENCIL -> 0.6f..8f
            Pen.MARKER -> 2f..16f
            Pen.HIGHLIGHTER -> 4f..30f
        }
}

enum class Tool { PEN, ERASER }

/** The drawing tools and input options, remembered across documents and restarts. */
@Stable
class ToolState(private val prefs: SharedPreferences) {
    var presets by mutableStateOf(loadPresets())
        private set
    var selected by mutableIntStateOf(prefs.getInt(KEY_SELECTED, 0).coerceIn(presets.indices))
        private set
    var tool by mutableStateOf(Tool.PEN)
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

    val pen: PenPreset get() = presets[selected]

    fun selectPen(index: Int) {
        selected = index
        tool = Tool.PEN
        prefs.edit { putInt(KEY_SELECTED, index) }
    }

    fun updatePen(index: Int, preset: PenPreset) {
        val size = preset.size.coerceIn(preset.sizeRange)
        presets = presets.toMutableList().also { it[index] = preset.copy(size = size) }
        prefs.edit { putString(KEY_PRESETS, json.encodeToString(presets)) }
    }

    fun selectEraser() {
        tool = Tool.ERASER
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

        val DEFAULT_PRESETS = listOf(
            PenPreset(Pen.BALLPOINT, 0xFF1A1A1A.toInt(), 1.2f),
            PenPreset(Pen.BALLPOINT, 0xFF1E4FD8.toInt(), 1.2f),
            PenPreset(Pen.FOUNTAIN, 0xFF000000.toInt(), 2f),
            PenPreset(Pen.PENCIL, 0xFF5F6368.toInt(), 1.6f),
            PenPreset(Pen.MARKER, 0xFFD93025.toInt(), 4f),
            PenPreset(Pen.HIGHLIGHTER, 0x80FFD600.toInt(), 12f),
        )

        val PALETTE = listOf(
            0xFF000000, 0xFF5F6368, 0xFF1E4FD8, 0xFF4FC3F7, 0xFF188038, 0xFF00897B,
            0xFFD93025, 0xFFF57C00, 0xFFFFD600, 0xFF8E24AA, 0xFFE91E63, 0xFF795548,
        ).map { it.toInt() }
    }
}
