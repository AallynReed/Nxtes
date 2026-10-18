package net.mildhope.nxtes.library

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.Folder
import net.mildhope.nxtes.data.LibraryItem

enum class SortOrder { NAME_ASC, NAME_DESC, MODIFIED_NEWEST, MODIFIED_OLDEST, CREATED_NEWEST, CREATED_OLDEST }

enum class KindFilter { ALL, PDFS, NOTES }

/** Library view choices, remembered across restarts. */
@Stable
class LibraryPrefs(private val prefs: SharedPreferences) {
    var sort by mutableStateOf(runCatching { SortOrder.valueOf(prefs.getString(KEY_SORT, null)!!) }.getOrDefault(SortOrder.MODIFIED_NEWEST))
        private set
    var listView by mutableStateOf(prefs.getBoolean(KEY_LIST, false))
        private set

    fun updateSort(order: SortOrder) {
        sort = order
        prefs.edit { putString(KEY_SORT, order.name) }
    }

    fun toggleListView() {
        listView = !listView
        prefs.edit { putBoolean(KEY_LIST, listView) }
    }

    /** Folders first, each group in the chosen order. */
    fun arrange(items: List<LibraryItem>, filter: KindFilter = KindFilter.ALL): List<LibraryItem> {
        val visible = items.filter {
            when (filter) {
                KindFilter.ALL -> true
                KindFilter.PDFS -> it is Document && it.isPdf
                KindFilter.NOTES -> it is Document && !it.isPdf
            }
        }
        val order: Comparator<LibraryItem> = when (sort) {
            SortOrder.NAME_ASC -> compareBy { it.displayName.lowercase() }
            SortOrder.NAME_DESC -> compareByDescending { it.displayName.lowercase() }
            SortOrder.MODIFIED_NEWEST -> compareByDescending { it.modified }
            SortOrder.MODIFIED_OLDEST -> compareBy { it.modified }
            SortOrder.CREATED_NEWEST -> compareByDescending { it.created }
            SortOrder.CREATED_OLDEST -> compareBy { it.created }
        }
        return visible.filterIsInstance<Folder>().sortedWith(order) + visible.filterIsInstance<Document>().sortedWith(order)
    }

    private val LibraryItem.created get() = when (this) {
        is Folder -> created
        is Document -> created
    }

    private companion object {
        const val KEY_SORT = "sort"
        const val KEY_LIST = "list_view"
    }
}

val Document.isPdf get() = pages.any { it.pdf != null }
