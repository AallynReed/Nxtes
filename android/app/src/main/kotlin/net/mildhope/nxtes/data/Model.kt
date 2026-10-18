package net.mildhope.nxtes.data

import kotlinx.serialization.Serializable

sealed interface LibraryItem {
    val id: String
    val displayName: String
    val trashedAt: Long?
    val favorite: Boolean
    val modified: Long
}

@Serializable
data class Folder(
    override val id: String,
    val name: String,
    val parentId: String? = null,
    val created: Long,
    override val modified: Long,
    override val trashedAt: Long? = null,
    override val favorite: Boolean = false,
    /** ARGB, or null for the default folder colour. */
    val color: Int? = null,
) : LibraryItem {
    override val displayName get() = name
}

@Serializable
data class Document(
    val format: Int = 1,
    override val id: String,
    val title: String,
    val folderId: String? = null,
    val created: Long,
    override val modified: Long,
    override val trashedAt: Long? = null,
    override val favorite: Boolean = false,
    /** When the document was last opened, for Recents. */
    val opened: Long? = null,
    val pages: List<Page>,
) : LibraryItem {
    override val displayName get() = title
}

/** Size is in PDF points. */
@Serializable
data class Page(
    val id: String,
    val width: Float,
    val height: Float,
    val pdf: PdfSource? = null,
)

@Serializable
data class PdfSource(val blob: String, val index: Int)

data class PageSize(val width: Float, val height: Float)

data class Library(
    val folders: List<Folder> = emptyList(),
    val documents: List<Document> = emptyList(),
    val loaded: Boolean = false,
) {
    private val folderById = folders.associateBy { it.id }

    fun folder(id: String?): Folder? = id?.let(folderById::get)

    fun document(id: String?): Document? = documents.find { it.id == id }

    /** True when the folder or any of its ancestors is in the trash. */
    fun isInTrash(folderId: String?): Boolean {
        var current = folder(folderId)
        while (current != null) {
            if (current.trashedAt != null) return true
            current = folder(current.parentId)
        }
        return false
    }

    fun childFolders(parentId: String?): List<Folder> =
        folders.filter { it.parentId == parentId && it.trashedAt == null }
            .sortedBy { it.name.lowercase() }

    fun childDocuments(folderId: String?): List<Document> =
        documents.filter { it.folderId == folderId && it.trashedAt == null }
            .sortedByDescending { it.modified }

    /** Items trashed directly, not ones hidden because an ancestor is trashed. */
    fun trash(): List<LibraryItem> {
        val trashedFolders = folders.filter { it.trashedAt != null && !isInTrash(it.parentId) }
        val trashedDocs = documents.filter { it.trashedAt != null && !isInTrash(it.folderId) }
        return (trashedFolders + trashedDocs).sortedByDescending { it.trashedAt }
    }

    /** Documents opened at least once, most recent first, not counting anything in the trash. */
    fun recents(): List<Document> =
        documents.filter { it.opened != null && it.trashedAt == null && !isInTrash(it.folderId) }
            .sortedByDescending { it.opened }

    fun favorites(): List<LibraryItem> =
        (folders.filter { it.favorite && it.trashedAt == null && !isInTrash(it.parentId) } +
            documents.filter { it.favorite && it.trashedAt == null && !isInTrash(it.folderId) })
            .sortedBy { it.displayName.lowercase() }

    /** Non-trashed folders and documents whose name contains the query. */
    fun search(query: String): List<LibraryItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return (folders.filter { it.trashedAt == null && !isInTrash(it.parentId) } +
            documents.filter { it.trashedAt == null && !isInTrash(it.folderId) })
            .filter { q in it.displayName.lowercase() }
    }

    fun itemCount(folderId: String): Int =
        folders.count { it.parentId == folderId && it.trashedAt == null } +
            documents.count { it.folderId == folderId && it.trashedAt == null }

    /** The folder and every folder below it. */
    fun subtree(folderId: String): Set<String> {
        val result = mutableSetOf(folderId)
        var frontier = listOf(folderId)
        while (frontier.isNotEmpty()) {
            frontier = folders.filter { it.parentId in frontier }.map { it.id }
            result += frontier
        }
        return result
    }
}
