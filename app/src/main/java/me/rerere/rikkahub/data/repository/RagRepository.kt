package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.rag.RagManager
import me.rerere.rikkahub.data.db.rag.RagSearchHit
import me.rerere.rikkahub.data.db.rag.RagStats
import me.rerere.rikkahub.data.db.rag.RagTextChunker
import me.rerere.rikkahub.data.db.rag.RAG_SOURCE_DOCUMENT
import me.rerere.rikkahub.data.db.rag.RAG_SOURCE_WORKSPACE
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import java.io.File

private const val TAG = "RagRepository"
private const val MAX_TEXT_BYTES = 512 * 1024L
private const val MAX_WORKSPACE_FILES = 300

/**
 * Builds and queries the local knowledge index from uploaded documents and
 * workspace text files. Extraction reuses the document parsers used for chat
 * attachments; results are chunked and stored in the rag_chunk FTS table.
 */
class RagRepository(
    private val ragManager: RagManager,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val textExtensions = setOf(
        "txt", "md", "markdown", "json", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "html", "htm", "css", "scss",
        "sh", "bash", "zsh", "c", "cc", "cpp", "h", "hpp", "rs", "go", "rb", "php",
        "sql", "xml", "csv", "log", "gradle", "properties", "env", "gitignore",
    )

    private val skipDirs = setOf(
        "node_modules", ".git", "build", "dist", ".gradle", "__pycache__", ".cache", "target", "vendor",
    )

    suspend fun stats(): RagStats = ragManager.stats()

    suspend fun search(query: String, limit: Int = 8): List<RagSearchHit> =
        ragManager.search(query, limit)

    suspend fun sources(): List<Pair<String, Int>> = ragManager.sources()

    suspend fun clearAll() {
        ragManager.clearAll()
    }

    /** Indexes every parseable file in the upload folder. Returns number of (re)indexed sources. */
    suspend fun indexUploads(onProgress: (indexed: Int, total: Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val files = runCatching { filesManager.list() }.getOrDefault(emptyList())
            var indexed = 0
            files.forEachIndexed { index, entity ->
                onProgress(index, files.size)
                if (indexManagedFile(entity)) indexed++
            }
            onProgress(files.size, files.size)
            indexed
        }

    private suspend fun indexManagedFile(entity: ManagedFileEntity): Boolean {
        val file = runCatching { filesManager.getFile(entity) }.getOrNull() ?: return false
        if (!file.isFile) return false
        val mime = entity.mimeType
        val fingerprint = "${file.length()}:${file.lastModified()}"
        if (ragManager.isUpToDate(entity.relativePath, fingerprint)) return false
        val text = runCatching { extractText(file, mime) }.getOrElse {
            Log.w(TAG, "extract failed for ${entity.displayName}: ${it.message}")
            return false
        }
        if (text.isBlank()) return false
        val chunks = RagTextChunker.chunk(text).mapIndexed { i, chunk -> chunk to "chunk ${i + 1}" }
        ragManager.replaceSource(
            sourceType = RAG_SOURCE_DOCUMENT,
            sourceKey = entity.relativePath,
            title = entity.displayName,
            fingerprint = fingerprint,
            chunks = chunks,
        )
        return true
    }

    /** Indexes text files under the workspace FILES area. Returns number of (re)indexed sources. */
    suspend fun indexWorkspace(
        workspaceId: String,
        onProgress: (indexed: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val candidates = collectWorkspaceFiles(workspaceId)
        var indexed = 0
        candidates.forEachIndexed { index, entry ->
            onProgress(index, candidates.size)
            if (indexWorkspaceFile(workspaceId, entry)) indexed++
        }
        onProgress(candidates.size, candidates.size)
        indexed
    }

    private suspend fun collectWorkspaceFiles(workspaceId: String): List<WorkspaceFileEntry> {
        val result = mutableListOf<WorkspaceFileEntry>()
        val queue = ArrayDeque<String>()
        queue += ""
        while (queue.isNotEmpty() && result.size < MAX_WORKSPACE_FILES) {
            val path = queue.removeFirst()
            val entries = runCatching {
                workspaceRepository.listFiles(workspaceId, WorkspaceStorageArea.FILES, path)
            }.getOrDefault(emptyList())
            entries.forEach { entry ->
                if (entry.isDirectory) {
                    if (entry.name !in skipDirs) queue += entry.path
                } else if (isIndexable(entry)) {
                    result += entry
                }
            }
        }
        return result
    }

    private fun isIndexable(entry: WorkspaceFileEntry): Boolean {
        if (entry.sizeBytes > MAX_TEXT_BYTES) return false
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        return ext in textExtensions
    }

    private suspend fun indexWorkspaceFile(workspaceId: String, entry: WorkspaceFileEntry): Boolean {
        val sourceKey = "ws:$workspaceId:${entry.path}"
        val fingerprint = "${entry.sizeBytes}:${entry.updatedAt}"
        if (ragManager.isUpToDate(sourceKey, fingerprint)) return false
        val text = runCatching {
            workspaceRepository.readTextForPreview(workspaceId, WorkspaceStorageArea.FILES, entry.path)
        }.getOrElse {
            Log.w(TAG, "workspace read failed for ${entry.path}: ${it.message}")
            return false
        }
        if (text.isBlank()) return false
        val title = "workspace/${entry.path}"
        val chunks = RagTextChunker.chunk(text).mapIndexed { i, chunk -> chunk to "chunk ${i + 1}" }
        ragManager.replaceSource(
            sourceType = RAG_SOURCE_WORKSPACE,
            sourceKey = sourceKey,
            title = title,
            fingerprint = fingerprint,
            chunks = chunks,
        )
        return true
    }

    private fun extractText(file: File, mime: String): String = when (mime) {
        "application/pdf" -> PdfParser.parserPdf(file)
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocxParser.parse(file)
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PptxParser.parse(file)
        "application/epub+zip" -> EpubParser.parse(file)
        else -> {
            if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) {
                ""
            } else {
                val ext = file.extension.lowercase()
                if (ext.isNotBlank() && ext !in textExtensions) "" else file.readText()
            }
        }
    }
}
