package com.letta.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolDisplayRegistryTest {

    @Test
    fun `resolves known tool - web_search`() {
        val info = ToolDisplayRegistry.resolve("web_search", """{"query": "test search"}""")
        assertEquals("🔍", info.emoji)
        assertEquals("test search", info.label)
    }

    @Test
    fun `resolves known tool - Bash with command`() {
        val info = ToolDisplayRegistry.resolve("Bash", """{"command": "echo hello"}""")
        assertEquals("⚡", info.emoji)
        assertEquals("echo hello", info.label)
    }

    @Test
    fun `resolves known tool - Read with file path`() {
        val info = ToolDisplayRegistry.resolve("Read", """{"file_path": "/tmp/test.txt"}""")
        assertEquals("📖", info.emoji)
        assertEquals("/tmp/test.txt", info.label)
    }

    @Test
    fun `resolves unknown tool uses name as label`() {
        val info = ToolDisplayRegistry.resolve("unknown_tool", """{"arg": "value"}""")
        assertEquals("🔧", info.emoji)
        assertEquals("unknown_tool", info.label)
    }

    @Test
    fun `resolves unknown tool with args as detail`() {
        val info = ToolDisplayRegistry.resolve("unknown_tool", """{"some_arg": "some_value"}""")
        assertEquals("🔧", info.emoji)
        assertEquals("unknown_tool", info.label)
        assertEquals("""{"some_arg": "some_value"}""", info.detailLine)
    }

    @Test
    fun `truncates long args in detail`() {
        val longArgs = "x".repeat(100)
        val info = ToolDisplayRegistry.resolve("unknown_tool", longArgs)
        assertEquals(61, info.detailLine?.length)
        assertEquals('…', info.detailLine?.last())
    }

    @Test
    fun `returns null detail for blank args`() {
        val info = ToolDisplayRegistry.resolve("web_search", null)
        assertEquals("Searching the web", info.label)
        assertNull(info.detailLine)
    }

    @Test
    fun `resolves memory tools with content extraction`() {
        val info = ToolDisplayRegistry.resolve(
            "memory_insert",
            """{"value": "important data"}"""
        )
        assertEquals("📝", info.emoji)
        assertEquals("important data", info.label)
    }

    @Test
    fun `resolves Grep with pattern extraction`() {
        val info = ToolDisplayRegistry.resolve("Grep", """{"pattern": "TODO"}""")
        assertEquals("🔍", info.emoji)
        assertEquals("TODO", info.label)
    }

    @Test
    fun `resolves Glob with pattern extraction`() {
        val info = ToolDisplayRegistry.resolve("Glob", """{"pattern": "*.kt"}""")
        assertEquals("📁", info.emoji)
        assertEquals("*.kt", info.label)
    }
}
