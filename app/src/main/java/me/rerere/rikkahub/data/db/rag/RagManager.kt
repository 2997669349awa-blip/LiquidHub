package me.rerere.rikkahub.data.db.rag

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase

const val RAG_SOURCE_DOCUMENT = "document"
const val RAG_SOURCE_WORKSPACE = "workspace"
const val RAG_SOURCE_MEMORY = "memory"

data class RagSearchHit(
    val sourceType: String,
    val sourceKey: String,
    val title: String,
    val locator: String?,
    val snippet: String,
)

data class RagStats(
    val totalChunks: Int,
    val sourceCount: Int,
    val byType: Map<String, Int>,
    val lastIndexedAt: Long,
)

/**
 * Local full-text index over documents, workspace files and memory entries.
 * Uses the same FTS5 'simple' tokenizer as message search (Chinese segmentation via jieba).
 * Tables are created in AppDatabaseFactory#onOpen, so no Room migration is required.
 */
class RagManager(private val database: AppDatabase) {
    private val db get() = database.openHelper.writableDatabase

    suspend fun isUpToDate(sourceKey: String, fingerprint: String): Boolean = withContext(Dispatchers.IO) {
        db.query(
            "SELECT fingerprint FROM rag_source_meta WHERE source_key = ?",
            arrayOf(sourceKey),
        ).use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == fingerprint
        }
    }

    suspend fun replaceSource(
        sourceType: String,
        sourceKey: String,
        title: String,
        fingerprint: String,
        chunks: List<Pair<String, String?>>,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM rag_chunk WHERE source_key = ?", arrayOf(sourceKey))
            chunks.forEach { (text, locator) ->
                if (text.isBlank()) return@forEach
                db.execSQL(
                    "INSERT INTO rag_chunk(text, source_type, source_key, title, locator, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(text, sourceType, sourceKey, title, locator ?: "", now.toString()),
                )
            }
            db.execSQL(
                """
                INSERT OR REPLACE INTO rag_source_meta(source_key, source_type, title, fingerprint, chunk_count, indexed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(sourceKey, sourceType, title, fingerprint, chunks.size.toString(), now.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i("RagManager", "indexed $sourceType/$sourceKey chunks=${chunks.size}")
    }

    suspend fun deleteSource(sourceKey: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM rag_chunk WHERE source_key = ?", arrayOf(sourceKey))
        db.execSQL("DELETE FROM rag_source_meta WHERE source_key = ?", arrayOf(sourceKey))
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM rag_chunk")
        db.execSQL("DELETE FROM rag_source_meta")
    }

    suspend fun stats(): RagStats = withContext(Dispatchers.IO) {
        var chunks = 0
        var sources = 0
        var lastIndexed = 0L
        val byType = mutableMapOf<String, Int>()
        db.query("SELECT source_type, COUNT(*) FROM rag_chunk GROUP BY source_type").use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getString(0) ?: "unknown"
                val count = cursor.getInt(1)
                byType[type] = count
                chunks += count
            }
        }
        db.query("SELECT COUNT(*), COALESCE(MAX(indexed_at), 0) FROM rag_source_meta").use { cursor ->
            if (cursor.moveToFirst()) {
                sources = cursor.getInt(0)
                lastIndexed = cursor.getLong(1)
            }
        }
        RagStats(totalChunks = chunks, sourceCount = sources, byType = byType, lastIndexedAt = lastIndexed)
    }

    suspend fun search(
        query: String,
        limit: Int = 8,
        sourceTypes: Set<String>? = null,
    ): List<RagSearchHit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val typeFilter = if (sourceTypes.isNullOrEmpty()) {
            ""
        } else {
            "AND source_type IN (${sourceTypes.joinToString(",") { "?" }})"
        }
        val args = buildList {
            add(query)
            sourceTypes?.forEach { add(it) }
            add(limit.toString())
        }.toTypedArray()
        val hits = mutableListOf<RagSearchHit>()
        db.query(
            """
            SELECT source_type, source_key, title, locator,
                   simple_snippet(rag_chunk, 0, '[', ']', '...', 30) AS snippet
            FROM rag_chunk
            WHERE text MATCH jieba_query(?)
            $typeFilter
            ORDER BY rank
            LIMIT ?
            """.trimIndent(),
            args,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                hits += RagSearchHit(
                    sourceType = cursor.getString(0) ?: "",
                    sourceKey = cursor.getString(1) ?: "",
                    title = cursor.getString(2) ?: "",
                    locator = cursor.getString(3)?.takeIf { it.isNotBlank() },
                    snippet = cursor.getString(4) ?: "",
                )
            }
        }
        hits
    }

    suspend fun sources(): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Pair<String, Int>>()
        db.query("SELECT title, chunk_count FROM rag_source_meta ORDER BY indexed_at DESC").use { cursor ->
            while (cursor.moveToNext()) {
                result += (cursor.getString(0) ?: "") to cursor.getInt(1)
            }
        }
        result
    }
}
