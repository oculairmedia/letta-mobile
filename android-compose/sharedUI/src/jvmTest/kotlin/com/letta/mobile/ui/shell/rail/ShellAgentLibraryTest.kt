@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.rail

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.agents.AgentRailGroup
import com.letta.mobile.data.agents.RecentAgentsPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-ckmub.3.10: the expanded rail's agent library is a roster of spaces, pinned agents first. */
class ShellAgentLibraryTest {
    private val agents = listOf("a1" to "PM - letta-mobile", "a2" to "PM - letta-web", "a3" to "Scout", "a4" to "Meridian")

    @Test
    fun pinnedAgentsLeadAndEachSpaceHoldsTheRest() {
        val groups = agents.map { (id, name) -> AgentRailGroup(name = name, agentIds = listOf(id)) }
        val entries = ShellRailMapping.entries(groups, ShellRailFocus(pinnedAgentIds = setOf("a3")))
        val sections = librarySections(groups, entries, needle = "")
        assertEquals("pinned", sections.first().key)
        assertEquals(listOf("Scout"), sections.first().items.map { it.name })
        assertEquals(false, sections.drop(1).any { section -> section.items.any { it.name == "Scout" } })
        assertEquals(3, sections.drop(1).sumOf { it.items.size })
    }

    @Test
    fun aQueryNarrowsTheLibraryAndNothingMatchingLeavesNoSections() {
        val groups = agents.map { (id, name) -> AgentRailGroup(name = name, agentIds = listOf(id)) }
        val entries = ShellRailMapping.entries(groups, ShellRailFocus())
        assertEquals(listOf("PM - letta-mobile"), librarySections(groups, entries, "pm letta mobile").flatMap { it.items }.map { it.name })
        assertEquals(emptyList(), librarySections(groups, entries, "zzz"))
    }

    @Test
    fun theExpandedRailSearchesAndOpensAnAgent() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            MaterialTheme {
                ShellLibraryRail(
                    state = ShellLibraryRailState(agents = agents, focus = ShellRailFocus(), expanded = true),
                    actions = ShellAgentRailActions(onAgentSelected = { opened += it }),
                )
            }
        }
        onNodeWithText("Scout").assertExists()
        onNodeWithContentDescription("Search agents").performTextInput("zzz")
        onNodeWithText("No agents match \"zzz\"").assertExists()
        onNodeWithContentDescription("Clear search").performClick()
        onNodeWithText("Meridian").performClick()
        assertEquals(listOf("a4"), opened)
    }

    @Test
    fun collapsedRailIsCappedButExpandedLibraryKeepsAndSearchesTheFullRoster() = runComposeUiTest {
        val roster = (0 until 100).map { index ->
            "agent-$index" to "Agent ${index.toString().padStart(3, '0')}"
        }
        val focus = ShellRailFocus()
        val expectedCollapsed = collapsedRailEntries(roster, focus)
        assertEquals(RecentAgentsPolicy.DEFAULT_MAX_AGENTS, expectedCollapsed.size)
        assertEquals((0..7).map { "Agent ${it.toString().padStart(3, '0')}" }, expectedCollapsed.map { it.name })

        var state by mutableStateOf(ShellLibraryRailState(agents = roster, focus = focus))
        val opened = mutableListOf<String>()
        setContent {
            MaterialTheme {
                ShellLibraryRail(
                    state = state,
                    actions = ShellAgentRailActions(onAgentSelected = { opened += it }),
                )
            }
        }
        onNodeWithTag(ShellAgentRailTags.orb("Agent 000")).assertExists()
        onNodeWithContentDescription("Search agents").assertDoesNotExist()

        state = state.copy(expanded = true)
        waitForIdle()
        val groups = ShellRailMapping.groups(roster, selectedAgentId = null)
        val entries = ShellRailMapping.entries(groups, state.focus)
        assertEquals(100, librarySections(groups, entries, needle = "").sumOf { it.items.size })
        onNodeWithContentDescription("Search agents").performTextInput("Agent 099")
        onAllNodesWithText("Agent 099")[1].performClick()
        assertEquals(listOf("agent-99"), opened)

        // Collapse after selecting the last roster member. Its panel owns focus; the rail returns
        // to its eight-entry recents cut and does not lose the expanded library's roster.
        state = state.copy(expanded = false, focus = focus.copy(selectedAgentId = "agent-99"))
        waitForIdle()
        assertEquals(100, state.agents.size)
        assertEquals(8, collapsedRailEntries(state.agents, state.focus).size)
        onNodeWithContentDescription("Search agents").assertDoesNotExist()
        onNodeWithTag(ShellAgentRailTags.orb("Agent 000")).assertExists()
    }

    @Test
    fun collapsedOverflowKeepsPinsFirstAndCapsEvenWhenPinsOverflow() {
        val roster = (0 until 12).map { index ->
            "agent-$index" to "Agent ${index.toString().padStart(2, '0')}"
        }
        val pinned = (0..9).mapTo(mutableSetOf()) { "agent-$it" }
        val entries = collapsedRailEntries(roster, ShellRailFocus(pinnedAgentIds = pinned))

        assertEquals(8, entries.size)
        assertEquals((0..7).map { "Agent ${it.toString().padStart(2, '0')}" }, entries.map { it.name })
        assertEquals(true, entries.all { it.pinned })
    }
}
