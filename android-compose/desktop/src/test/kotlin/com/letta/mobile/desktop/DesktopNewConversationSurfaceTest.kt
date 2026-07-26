package com.letta.mobile.desktop

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopNewConversationSurfaceTest {
    private fun row(id: String, name: String) =
        NewConversationAgentRow(id = id, name = name, subtitle = null, orbIndex = 0)

    @Test
    fun buildsRowsWithRosterSubtitleAndAvatarOverride() {
        val rail = listOf("a1" to "Meridian", "a2" to "Scout")
        val roster = listOf(
            Agent(id = AgentId("a1"), name = "Meridian", description = "Ops copilot", model = "gpt-x"),
            Agent(id = AgentId("a2"), name = "Scout", model = "haiku"),
        )
        val rows = buildNewConversationRows(rail, roster, avatarStyleByAgentId = mapOf("a2" to 7))

        assertEquals("Ops copilot", rows[0].subtitle)
        assertEquals(0, rows[0].orbIndex)
        assertEquals("haiku", rows[1].subtitle)
        assertEquals(7, rows[1].orbIndex)
    }

    @Test
    fun filtersByNameCaseInsensitively() {
        val rows = listOf(row("a", "Meridian"), row("b", "Scout"), row("c", "merlin"))
        assertEquals(listOf("Meridian", "merlin"), filterAgentDirectory(rows, "mer").map { it.name })
        assertEquals(rows, filterAgentDirectory(rows, "  "))
    }

    @Test
    fun groupsAlphabeticallyWithNonLettersLast() {
        val rows = listOf(row("1", "zeta"), row("2", "Alpha"), row("3", "42-bot"), row("4", "apex"))
        val sections = groupAgentDirectory(rows)

        assertEquals(listOf("A", "Z", "#"), sections.map { it.first })
        assertEquals(listOf("Alpha", "apex"), sections[0].second.map { it.name })
        assertEquals(listOf("42-bot"), sections[2].second.map { it.name })
    }
}
