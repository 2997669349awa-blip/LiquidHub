package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySelectorTest {

    private fun memory(id: Int, content: String) = MemoryFile(path = "memory$id.md", content = content, updatedAt = 0L)

    @Test
    fun `tokenize splits latin words and cjk bigrams`() {
        val tokens = MemorySelector.tokenize("使用 Kotlin 开发 RAG")
        assertTrue(tokens.contains("kotlin"))
        assertTrue(tokens.contains("rag"))
        assertTrue(tokens.contains("使用"))
        assertTrue(tokens.contains("开发"))
    }

    @Test
    fun `tokenize ignores single latin characters`() {
        val tokens = MemorySelector.tokenize("a b c ab")
        assertEquals(listOf("ab"), tokens)
    }

    @Test
    fun `small memory sets are kept whole`() {
        val memories = (1..5).map { memory(it, "random fact $it") }
        val selected = MemorySelector.select(memories, queryText = "unrelated")
        assertEquals(memories, selected)
    }

    @Test
    fun `large sets are ranked by lexical overlap`() {
        val memories = (1..20).map { memory(it, "unrelated memory number $it") } +
            memory(100, "user prefers dark mode python")
        val selected = MemorySelector.select(memories, queryText = "which python editor do I like")
        assertTrue(selected.isNotEmpty())
        assertEquals("memory100.md", selected.first().path)
    }

    @Test
    fun `selection respects memory count cap`() {
        val memories = (1..200).map { memory(it, "python tip $it") }
        val selected = MemorySelector.select(memories, queryText = "python", maxMemories = 5)
        assertTrue(selected.size <= 5)
    }

    @Test
    fun `selection respects char budget`() {
        val memories = (1..100).map { memory(it, "python " + "x".repeat(200)) }
        val selected = MemorySelector.select(memories, queryText = "python", maxTotalChars = 1_000)
        assertTrue(selected.sumOf { it.content.length } <= 1_200)
    }

    @Test
    fun `empty query falls back to first memories`() {
        val memories = (1..50).map { memory(it, "fact $it") }
        val selected = MemorySelector.select(memories, queryText = "")
        assertEquals(30, selected.size)
    }
}
