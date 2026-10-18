package net.mildhope.nxtes.viewer

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class ScrollAxis { VERTICAL, HORIZONTAL }

/** How pages follow each other: scroll direction, continuous or a page at a time, alone or in pairs. */
data class PageFlow(
    val axis: ScrollAxis = ScrollAxis.VERTICAL,
    val continuous: Boolean = true,
    val twoPage: Boolean = false,
    /** With two pages side by side, the first page stands alone like a book cover. */
    val coverAlone: Boolean = true,
)

/** How the writing view looks, remembered across documents and restarts. */
@Stable
class ViewSettings(private val prefs: SharedPreferences) {
    var flow by mutableStateOf(
        PageFlow(
            axis = runCatching { ScrollAxis.valueOf(prefs.getString(KEY_AXIS, null)!!) }.getOrDefault(ScrollAxis.VERTICAL),
            continuous = prefs.getBoolean(KEY_CONTINUOUS, true),
            twoPage = prefs.getBoolean(KEY_TWO_PAGE, false),
            coverAlone = prefs.getBoolean(KEY_COVER_ALONE, true),
        ),
    )
        private set

    /** Pages drawn with light and dark swapped, for reading at night. */
    var darkPages by mutableStateOf(prefs.getBoolean(KEY_DARK, false))
        private set
    var keepScreenOn by mutableStateOf(prefs.getBoolean(KEY_AWAKE, false))
        private set

    /** The status and navigation bars hidden while writing. */
    var fullScreen by mutableStateOf(prefs.getBoolean(KEY_FULL_SCREEN, false))
        private set
    var hideTabs by mutableStateOf(prefs.getBoolean(KEY_HIDE_TABS, false))
        private set

    /** Nothing on the pages can be changed; touches only scroll, zoom and reveal masks. */
    var readOnly by mutableStateOf(prefs.getBoolean(KEY_READ_ONLY, false))
        private set

    fun updateFlow(new: PageFlow) {
        flow = new
        prefs.edit {
            putString(KEY_AXIS, new.axis.name)
            putBoolean(KEY_CONTINUOUS, new.continuous)
            putBoolean(KEY_TWO_PAGE, new.twoPage)
            putBoolean(KEY_COVER_ALONE, new.coverAlone)
        }
    }

    fun toggleDarkPages() = save(KEY_DARK, !darkPages) { darkPages = it }

    fun toggleKeepScreenOn() = save(KEY_AWAKE, !keepScreenOn) { keepScreenOn = it }

    fun toggleFullScreen() = save(KEY_FULL_SCREEN, !fullScreen) { fullScreen = it }

    fun toggleHideTabs() = save(KEY_HIDE_TABS, !hideTabs) { hideTabs = it }

    fun toggleReadOnly() = save(KEY_READ_ONLY, !readOnly) { readOnly = it }

    private fun save(key: String, value: Boolean, apply: (Boolean) -> Unit) {
        apply(value)
        prefs.edit { putBoolean(key, value) }
    }

    private companion object {
        const val KEY_AXIS = "axis"
        const val KEY_CONTINUOUS = "continuous"
        const val KEY_TWO_PAGE = "two_page"
        const val KEY_COVER_ALONE = "cover_alone"
        const val KEY_DARK = "dark_pages"
        const val KEY_AWAKE = "keep_screen_on"
        const val KEY_FULL_SCREEN = "full_screen"
        const val KEY_HIDE_TABS = "hide_tabs"
        const val KEY_READ_ONLY = "read_only"
    }
}
