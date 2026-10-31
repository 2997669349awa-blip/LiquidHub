package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.MemoryFile

/**
 * Chooses which stored memories to inject into the prompt, based on lexical
 * overlap with the recent conversation text. Small memory sets are kept whole
 * (preserving the previous behaviour); large sets are ranked and capped.
 */
object MemorySelector {
    private const val SMALL_SET_THRESHOLD = 10
    private const val MAX_MEMORIES = 30
    private const val MAX_TOTAL_CHARS = 4_000

    fun select(
        memories: List<MemoryFile>,
        queryText: String,
        maxMemories: Int = MAX_MEMORIES,
        maxTotalChars: Int = MAX_TOTAL_CHARS,
    ): List<MemoryFile> {
        if (memories.size <= SMALL_SET_THRESHOLD) return memories
        val tokens = tokenize(queryText).toSet()
        if (tokens.isEmpty()) return memories.take(maxMemories)

        val scored = memories.mapIndexed { index, memory ->
            val content = memory.content.lowercase()
            val score = tokens.count { token -> content.contains(token) }
            Triple(memory, score, index)
        }
        val ranked = scored.sortedWith(compareByDescending<Triple<MemoryFile, Int, Int>> { it.second }
            .thenBy { it.third })

        val selected = mutableListOf<MemoryFile>()
        var chars = 0
        for ((memory, score, _) in ranked) {
            if (selected.size >= maxMemories) break
            if (score == 0 && selected.isNotEmpty()) break
            if (chars + memory.content.length > maxTotalChars && selected.isNotEmpty()) break
            selected += memory
            chars += memory.content.length
        }
        return selected
    }

    /** Latin words (>=2 chars) plus CJK bigrams; CJK single-character runs kept as-is. */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        val latin = StringBuilder()
        val cjk = StringBuilder()

        fun flushLatin() {
            if (latin.length >= 2) tokens += latin.toString().lowercase()
            latin.clear()
        }

        fun flushCjk() {
            if (cjk.length == 1) {
                tokens += cjk.toString()
            } else if (cjk.length > 1) {
                for (i in 0 until cjk.length - 1) tokens += cjk.substring(i, i + 2)
            }
            cjk.clear()
        }

        text.forEach { ch ->
            when {
                ch.isLetterOrDigit() && ch.code < 128 -> {
                    flushCjk()
                    latin.append(ch)
                }

                isCjk(ch) -> {
                    flushLatin()
                    cjk.append(ch)
                }

                else -> {
                    flushLatin()
                    flushCjk()
                }
            }
        }
        flushLatin()
        flushCjk()
        return tokens.distinct()
    }

    private fun isCjk(ch: Char): Boolean =
        ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF || ch.code in 0x3040..0x30FF
}
