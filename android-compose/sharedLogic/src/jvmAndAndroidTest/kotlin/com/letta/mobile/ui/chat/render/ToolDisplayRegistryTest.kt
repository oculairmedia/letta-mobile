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

    @Test
    fun `shell tools prefer a trimmed provided description`() {
        val registry = ToolDisplayRegistry()
        val bashInfo = registry.resolve(
            "Bash",
            """{"command":"rm -rf build", "description":"  Remove build output  "}""",
        )
        val execInfo = registry.resolve(
            "exec_command",
            """{"cmd":"rm -rf build", "description":"  Remove build output  "}""",
        )

        assertEquals("Remove build output", bashInfo.label)
        assertEquals("Remove build output", execInfo.label)
    }

    @Test
    fun `namespaced exec command uses provided description`() {
        val registry = ToolDisplayRegistry()
        val info = registry.resolve(
            "functions.exec_command",
            """{"cmd":"git status", "description":"Show working tree status"}""",
        )

        assertEquals("Show working tree status", info.label)
    }

    @Test
    fun `shell tools fall back when description is absent or invalid`() {
        val registry = ToolDisplayRegistry()
        val cases = listOf(
            """{"command":"pwd"}""",
            """{"command":"pwd", "description":"   "}""",
            """{"command":"pwd", "description":null}""",
            """{"command":"pwd", "description":{"text":"List files"}}""",
            """{"command":"pwd", "description":["List files"]}""",
            """{"command":"pwd", "description":"List files"""",
        )

        cases.forEach { args ->
            assertEquals("Running command", registry.resolve("Bash", args).label)
        }
    }

    @Test
    fun `shell purpose stays on one line`() {
        assertEquals(
            "Build dev APK",
            ToolDisplayRegistry().resolve("exec_command", """{"description":"Build\n  dev\tAPK"}""").label,
        )
    }

    @Test
    fun `shell descriptions are bounded`() {
        val registry = ToolDisplayRegistry()
        val description = "a".repeat(100)
        val info = registry.resolve("Bash", """{"command":"pwd", "description":"$description"}""")

        assertEquals("a".repeat(80) + "…", info.label)
    }

    @Test
    fun `file and search tools retain extracted details`() {
        val registry = ToolDisplayRegistry()

        assertEquals("/tmp/report.txt", registry.resolve("Read", """{"file_path":"/tmp/report.txt"}""").label)
        assertEquals("TODO", registry.resolve("Grep", """{"pattern":"TODO"}""").label)
    }
}
