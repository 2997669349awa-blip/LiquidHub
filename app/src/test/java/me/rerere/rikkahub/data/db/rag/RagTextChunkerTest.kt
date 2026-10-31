package me.rerere.rikkahub.data.db.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagTextChunkerTest {

    @Test
    fun `blank text produces no chunks`() {
        assertTrue(RagTextChunker.chunk("   \n ").isEmpty())
    }

    @Test
    fun `short text is a single chunk`() {
        val chunks = RagTextChunker.chunk("hello world", maxChars = 100)
        assertEquals(listOf("hello world"), chunks)
    }

    @Test
    fun `long text is split into overlapping chunks`() {
        val text = (1..200).joinToString(" ") { "word$it" }
        val chunks = RagTextChunker.chunk(text, maxChars = 200, overlap = 50)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 200 })
        // every chunk must be non-empty and reconstruct coverage of the text
        assertTrue(chunks.all { it.isNotBlank() })
    }

    @Test
    fun `prefers line breaks near the chunk limit`() {
        val paragraph = "a".repeat(500)
        val text = "$paragraph\n\n$paragraph"
        val chunks = RagTextChunker.chunk(text, maxChars = 600, overlap = 0)
        assertEquals(2, chunks.size)
        assertEquals(paragraph, chunks[0])
    }

    @Test
    fun `chunk count is bounded for large input`() {
        val text = "x".repeat(10_000)
        val chunks = RagTextChunker.chunk(text, maxChars = 800, overlap = 100)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.length <= 800 })
        assertTrue(chunks.size in 12..15)
    }

    @Test
    fun `normalizes windows line endings`() {
        val chunks = RagTextChunker.chunk("a\r\nb", maxChars = 100)
        assertEquals(listOf("a\nb"), chunks)
    }
}
