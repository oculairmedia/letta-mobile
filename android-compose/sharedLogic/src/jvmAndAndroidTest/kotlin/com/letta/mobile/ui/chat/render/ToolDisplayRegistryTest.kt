package com.letta.mobile.ui.chat.render

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolDisplayRegistryTest {
    @Test
    fun `extract valid json`() {
        val registry = ToolDisplayRegistry()
        val info = registry.resolve("web_search", """{"query": "hello world"}""")
        assertEquals("hello world", info.label)
    }

    @Test
    fun `fallback for invalid json`() {
        val registry = ToolDisplayRegistry()
        val info = registry.resolve("web_search", "invalid json: {\"query\": \"fallback test\"}")
        assertEquals("fallback test", info.label)
    }

    @Test
    fun `truncate long output`() {
        val registry = ToolDisplayRegistry()
        val longText = "a".repeat(100)
        val info = registry.resolve("web_search", """{"query": "$longText"}""")
        assertEquals("a".repeat(60) + "…", info.label)
    }
}
