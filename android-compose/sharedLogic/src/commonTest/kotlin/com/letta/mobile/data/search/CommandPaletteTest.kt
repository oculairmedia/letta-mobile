package com.letta.mobile.data.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandPaletteTest {
    @Test
    fun testSectionTitle() {
        assertEquals("Conversations", CommandPalette.sectionTitle(PaletteItemKind.Conversation))
        assertEquals("Agents", CommandPalette.sectionTitle(PaletteItemKind.Agent))
        assertEquals("Go to", CommandPalette.sectionTitle(PaletteItemKind.Destination))
    }

    @Test
    fun testGroupedEmptyQuery() {
        val items = listOf(
            PaletteItem("1", "Alice", null, PaletteItemKind.Agent),
            PaletteItem("2", "Chat", "sub", PaletteItemKind.Conversation),
            PaletteItem("3", "Settings", null, PaletteItemKind.Destination)
        )
        val result = CommandPalette.grouped(items, "")

        assertEquals(3, result.size)
        assertEquals(PaletteItemKind.Conversation, result[0].first)
        assertEquals("2", result[0].second[0].id)

        assertEquals(PaletteItemKind.Agent, result[1].first)
        assertEquals("1", result[1].second[0].id)

        assertEquals(PaletteItemKind.Destination, result[2].first)
        assertEquals("3", result[2].second[0].id)
    }

    @Test
    fun testGroupedCaseInsensitiveQuery() {
        val items = listOf(
            PaletteItem("1", "Alice", null, PaletteItemKind.Agent),
            PaletteItem("2", "Chat with Bob", "sub", PaletteItemKind.Conversation),
            PaletteItem("3", "Bob's Agent", null, PaletteItemKind.Agent),
            PaletteItem("4", "Settings", null, PaletteItemKind.Destination)
        )
        val result = CommandPalette.grouped(items, "bOb")

        assertEquals(2, result.size)

        assertEquals(PaletteItemKind.Conversation, result[0].first)
        assertEquals(1, result[0].second.size)
        assertEquals("2", result[0].second[0].id)

        assertEquals(PaletteItemKind.Agent, result[1].first)
        assertEquals(1, result[1].second.size)
        assertEquals("3", result[1].second[0].id)
    }

    @Test
    fun testGroupedNoMatch() {
        val items = listOf(
            PaletteItem("1", "Alice", null, PaletteItemKind.Agent),
            PaletteItem("2", "Chat with Bob", "sub", PaletteItemKind.Conversation)
        )
        val result = CommandPalette.grouped(items, "xyz")
        assertTrue(result.isEmpty())
    }
}
