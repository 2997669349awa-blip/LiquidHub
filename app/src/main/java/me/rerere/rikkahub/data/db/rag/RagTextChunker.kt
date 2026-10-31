package me.rerere.rikkahub.data.db.rag

/**
 * Splits plain text into overlapping chunks suitable for full-text indexing.
 * Chunk boundaries prefer paragraph/line breaks so snippets stay readable.
 */
object RagTextChunker {
    const val DEFAULT_MAX_CHARS = 800
    const val DEFAULT_OVERLAP = 100

    fun chunk(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        overlap: Int = DEFAULT_OVERLAP,
    ): List<String> {
        require(maxChars > 0)
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= maxChars) return listOf(normalized)

        val effectiveOverlap = overlap.coerceIn(0, maxChars / 2)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < normalized.length) {
            var end = (start + maxChars).coerceAtMost(normalized.length)
            if (end < normalized.length) {
                val window = normalized.substring(start, end)
                val breakAt = listOf(window.lastIndexOf("\n\n"), window.lastIndexOf('\n'), window.lastIndexOf("。 "), window.lastIndexOf(". "))
                    .maxOrNull() ?: -1
                // Only honour a break if it does not shrink the chunk too much.
                if (breakAt > maxChars / 3) {
                    end = start + breakAt + 1
                }
            }
            val piece = normalized.substring(start, end).trim()
            if (piece.isNotEmpty()) chunks += piece
            if (end >= normalized.length) break
            start = (end - effectiveOverlap).coerceAtLeast(start + 1)
        }
        return chunks
    }
}
