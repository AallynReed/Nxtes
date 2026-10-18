package net.mildhope.nxtes.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** A named set of opaque colors the user saved. */
@Serializable
data class Palette(val id: String, val name: String, val colors: List<Int> = emptyList())

@Serializable
private class PaletteState(val saved: List<Palette> = emptyList(), val shown: String? = null)

/**
 * The palettes every color chooser offers besides its own colors, and the one last shown.
 * [load] and [store] keep them as JSON across restarts.
 */
@Stable
class Palettes(
    private val load: () -> String?,
    private val store: (String) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val initial = load()?.let { runCatching { json.decodeFromString<PaletteState>(it) }.getOrNull() } ?: PaletteState()

    var saved by mutableStateOf(initial.saved)
        private set

    /** The saved palette choosers open on, or null for each chooser's own colors. */
    var shownId by mutableStateOf(initial.shown?.takeIf { id -> initial.saved.any { it.id == id } })
        private set

    val shown: Palette? get() = saved.find { it.id == shownId }

    fun show(id: String?) {
        shownId = id
        save()
    }

    /** Adds a palette holding [colors] and shows it. */
    fun add(name: String, colors: List<Int> = emptyList()): Palette {
        val palette = Palette(newId(), name, colors.map(::opaque).distinct())
        saved = saved + palette
        show(palette.id)
        return palette
    }

    fun rename(id: String, name: String) = change(id) { it.copy(name = name) }

    fun delete(id: String) {
        saved = saved.filter { it.id != id }
        if (shownId == id) shownId = null
        save()
    }

    fun addColor(id: String, color: Int) = change(id) { if (opaque(color) in it.colors) it else it.copy(colors = it.colors + opaque(color)) }

    fun removeColor(id: String, color: Int) = change(id) { it.copy(colors = it.colors - opaque(color)) }

    private fun change(id: String, update: (Palette) -> Palette) {
        saved = saved.map { if (it.id == id) update(it) else it }
        save()
    }

    private fun save() = store(json.encodeToString(PaletteState(saved, shownId)))

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun opaque(color: Int) = color or 0xFF000000.toInt()
    }
}
