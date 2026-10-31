package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCommandsTest {

    @Test
    fun `parses space separated argument`() {
        assertEquals("B001-1234", parseChatCommandArg("bg B001-1234"))
    }

    @Test
    fun `parses dash separated argument`() {
        assertEquals("B001-1234", parseChatCommandArg("bg-B001-1234"))
    }

    @Test
    fun `returns empty string without argument`() {
        assertEquals("", parseChatCommandArg("bg"))
    }

    @Test
    fun `trims extra whitespace around argument`() {
        assertEquals("abc", parseChatCommandArg("logs   abc   "))
    }

    @Test
    fun `keeps hyphens inside argument`() {
        assertEquals("T001-9999", parseChatCommandArg("kill T001-9999"))
    }

    @Test
    fun `help text lists background commands`() {
        val text = ChatCommandSpecs.helpText()
        assertTrue(text.contains("//bg"))
        assertTrue(text.contains("//logs"))
    }

    @Test
    fun `every command spec has usage and description`() {
        ChatCommandSpecs.commands.forEach { spec ->
            assertTrue(spec.usage.isNotBlank())
            assertTrue(spec.description.isNotBlank())
        }
    }
}
