@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.rail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.agents.AgentRailGroup
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
}
