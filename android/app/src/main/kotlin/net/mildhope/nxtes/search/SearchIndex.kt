package net.mildhope.nxtes.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

/** A page whose text matches a search, with a snippet where [MATCH_START] and [MATCH_END] wrap each match. */
class ContentHit(val documentId: String, val pageIndex: Int, val snippet: String)

const val MATCH_START = '\u0001'
const val MATCH_END = '\u0002'

/**
 * The words in [query] as an FTS match expression: every word must appear, each as a prefix.
 * Punctuation is dropped, so nothing the user types can break the query. Null when there are no words.
 */
fun matchExpression(query: String): String? {
    val words = query.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    return if (words.isEmpty()) null else words.joinToString(" ") { "\"$it*\"" }
}

/**
 * Full-text index of every page's words: PDF text, text boxes, sticky notes and marked text. It's
 * derived data, rebuilt from the library whenever it's missing or out of date. Android's SQLite has
 * FTS4 but not FTS5, so it uses FTS4.
 */
class SearchIndex(context: Context) : SQLiteOpenHelper(context, "search.db", null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE pages USING fts4(document_id, page_index, body, " +
                "notindexed=document_id, notindexed=page_index, tokenize=unicode61 \"remove_diacritics=1\")",
        )
        db.execSQL("CREATE TABLE documents (id TEXT PRIMARY KEY, modified INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS pages")
        db.execSQL("DROP TABLE IF EXISTS documents")
        onCreate(db)
    }

    /** When each indexed document was last modified, as it was when indexed. */
    fun stamps(): Map<String, Long> = readableDatabase.rawQuery("SELECT id, modified FROM documents", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
    }

    /** Replaces a document's pages with [pages], one text per page in order. */
    fun replace(documentId: String, modified: Long, pages: List<String>) {
        writableDatabase.transaction {
            delete("pages", "document_id = ?", arrayOf(documentId))
            pages.forEachIndexed { i, text ->
                if (text.isBlank()) return@forEachIndexed
                insert("pages", null, ContentValues().apply {
                    put("document_id", documentId)
                    put("page_index", i)
                    put("body", text)
                })
            }
            insertWithOnConflict("documents", null, ContentValues().apply {
                put("id", documentId)
                put("modified", modified)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun remove(documentId: String) {
        val db = writableDatabase
        db.delete("pages", "document_id = ?", arrayOf(documentId))
        db.delete("documents", "id = ?", arrayOf(documentId))
    }

    /** Pages containing every word of [query], in page order within each document. */
    fun search(query: String, limit: Int = MAX_HITS): List<ContentHit> {
        val match = matchExpression(query) ?: return emptyList()
        val sql = "SELECT document_id, page_index, snippet(pages, char(1), char(2), '…', 2, 12) FROM pages " +
            "WHERE body MATCH ? ORDER BY document_id, CAST(page_index AS INTEGER) LIMIT $limit"
        return readableDatabase.rawQuery(sql, arrayOf(match)).use { c ->
            buildList { while (c.moveToNext()) add(ContentHit(c.getString(0), c.getInt(1), c.getString(2))) }
        }
    }

    private companion object {
        const val VERSION = 1
        const val MAX_HITS = 500
    }
}
