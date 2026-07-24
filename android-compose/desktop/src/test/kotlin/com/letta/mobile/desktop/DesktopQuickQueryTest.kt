package com.letta.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopQuickQueryTest {
    @Test
    fun promptWithoutContextIsJustTrimmedText() {
        assertEquals("what is this", quickQueryPrompt("  what is this  ", null))
        assertEquals("what is this", quickQueryPrompt("what is this", "  "))
    }

    @Test
    fun promptWithContextPrefixesTheAmbientWindowTitle() {
        val prompt = quickQueryPrompt("summarize this", "AppNavGraph.kt — VS Code")
        assertEquals(
            "[Context: the user is currently looking at \"AppNavGraph.kt — VS Code\"]\n\nsummarize this",
            prompt,
        )
    }
}
