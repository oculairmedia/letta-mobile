package com.letta.mobile.data.composer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MentionableTest {

    @Test
    fun sectionTitle_returnsCorrectStringForEveryMentionKind() {
        assertEquals("Files", MentionCatalog.sectionTitle(MentionKind.File))
        assertEquals("Agents", MentionCatalog.sectionTitle(MentionKind.Agent))
        assertEquals("Memory", MentionCatalog.sectionTitle(MentionKind.Memory))
    }

    @Test
    fun matches_emptyQuery_returnsTrue() {
        val item = Mentionable(
            id = "1",
            label = "My File",
            sublabel = null,
            kind = MentionKind.File
        )
        assertTrue(MentionCatalog.matches(item, ""))
        assertTrue(MentionCatalog.matches(item, "   "))
    }

    @Test
    fun matches_caseInsensitivity_returnsTrue() {
        val item = Mentionable(
            id = "1",
            label = "My File",
            sublabel = null,
            kind = MentionKind.File
        )
        assertTrue(MentionCatalog.matches(item, "my file"))
        assertTrue(MentionCatalog.matches(item, "MY FILE"))
    }

    @Test
    fun matches_prefixSubstringBehavior_returnsTrue() {
        val item = Mentionable(
            id = "1",
            label = "A very specific label",
            sublabel = "And a descriptive sublabel",
            kind = MentionKind.Agent,
            insertText = "inserted_text"
        )
        // Matches label prefix
        assertTrue(MentionCatalog.matches(item, "A very"))
        // Matches label substring
        assertTrue(MentionCatalog.matches(item, "specific"))
        // Matches sublabel substring
        assertTrue(MentionCatalog.matches(item, "descriptive"))
        // Matches insertText substring
        assertTrue(MentionCatalog.matches(item, "inserted"))
    }

    @Test
    fun matches_noMatch_returnsFalse() {
        val item = Mentionable(
            id = "1",
            label = "File A",
            sublabel = "Sub A",
            kind = MentionKind.File,
            insertText = "Insert A"
        )
        assertFalse(MentionCatalog.matches(item, "Agent B"))
    }

    @Test
    fun grouped_ordersBySectionOrderAndDropsEmptySections() {
        val file1 = Mentionable("f1", "File 1", null, MentionKind.File)
        val file2 = Mentionable("f2", "File 2", null, MentionKind.File)
        val memory1 = Mentionable("m1", "Memory 1", null, MentionKind.Memory)

        // No agents included here.
        val items = listOf(file1, memory1, file2)

        // Use empty query to match all items
        val grouped = MentionCatalog.grouped(items, "")

        // Only 2 sections should be present because Agents section is empty
        assertEquals(2, grouped.size)

        // Check order and contents
        // 1st section: Files
        assertEquals(MentionKind.File, grouped[0].first)
        assertEquals(listOf(file1, file2), grouped[0].second)

        // 2nd section: Memory
        assertEquals(MentionKind.Memory, grouped[1].first)
        assertEquals(listOf(memory1), grouped[1].second)
    }

    @Test
    fun grouped_filtersByQueryBeforeGrouping() {
        val file1 = Mentionable("f1", "Apple File", null, MentionKind.File)
        val agent1 = Mentionable("a1", "Apple Agent", null, MentionKind.Agent)
        val memory1 = Mentionable("m1", "Banana Memory", null, MentionKind.Memory)

        val items = listOf(file1, agent1, memory1)

        val grouped = MentionCatalog.grouped(items, "Apple")

        // Only sections with "Apple" should be present (Files and Agents)
        assertEquals(2, grouped.size)

        // 1st section: Files
        assertEquals(MentionKind.File, grouped[0].first)
        assertEquals(listOf(file1), grouped[0].second)

        // 2nd section: Agents
        assertEquals(MentionKind.Agent, grouped[1].first)
        assertEquals(listOf(agent1), grouped[1].second)
    }
}
