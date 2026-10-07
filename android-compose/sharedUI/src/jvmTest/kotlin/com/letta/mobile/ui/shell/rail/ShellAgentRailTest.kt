@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.rail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared agent rail (letta-mobile-c3np7.2.11): Home, the agent orbs, New. */
class ShellAgentRailTest {
    private val entries = ShellRailMapping.entries(
        ShellRailMapping.groups(listOf("a1" to "Alpha", "b1" to "Beta", "b2" to "Beta"), selectedAgentId = null),
        ShellRailFocus(selectedAgentId = "b2"),
    )

    @Test
    fun homeOrbsAndNewReportToTheHost() = runComposeUiTest {
        val events = mutableListOf<String>()
        setContent {
            MaterialTheme {
                ShellAgentRail(
                    state = ShellAgentRailState(entries = entries),
                    actions = ShellAgentRailActions(
                        onAgentSelected = { events += "agent:$it" },
                        onHome = { events += "home" },
                        onNewSession = { events += "new" },
                    ),
                )
            }
        }
        onNodeWithContentDescription("Home").performClick()
        onNodeWithText("A").performClick()
        // A stack opens its selected member.
        onNodeWithText("B").performClick()
        onNodeWithContentDescription("New").performClick()
        assertEquals(listOf("home", "agent:a1", "agent:b2", "new"), events)
    }

    @Test
    fun expandedRailShowsTheHostsLibraryInsteadOfTheOrbs() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ShellAgentRail(
                    state = ShellAgentRailState(entries = entries, expanded = true),
                    actions = ShellAgentRailActions(),
                    library = { Column { Text("library") } },
                )
            }
        }
        onNodeWithText("library").assertExists()
        onNodeWithText("Home").assertExists()
        onNodeWithTag(ShellAgentRailTags.orb("Alpha")).assertDoesNotExist()
    }

    @Test
    fun hiddenAgentsAreOneTapAwayBehindAllAgents() = runComposeUiTest {
        var opened = 0
        setContent {
            MaterialTheme {
                ShellAgentRail(
                    state = ShellAgentRailState(entries = entries, hiddenAgentCount = 5),
                    actions = ShellAgentRailActions(onShowAllAgents = { opened++ }),
                )
            }
        }
        onNodeWithText("+5").assertExists()
        onNodeWithContentDescription("All agents").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun noAllAgentsControlWhenNothingIsHiddenOrTheHostHasNoList() = runComposeUiTest {
        var hidden by mutableStateOf(0)
        var action: (() -> Unit)? by mutableStateOf({})
        setContent {
            MaterialTheme {
                ShellAgentRail(
                    state = ShellAgentRailState(entries = entries, hiddenAgentCount = hidden),
                    actions = ShellAgentRailActions(onShowAllAgents = action),
                )
            }
        }
        onNodeWithTag(ShellAgentRailTags.ALL_AGENTS).assertDoesNotExist()
        hidden = 120
        waitForIdle()
        onNodeWithText("99+").assertExists()
        action = null
        waitForIdle()
        onNodeWithTag(ShellAgentRailTags.ALL_AGENTS).assertDoesNotExist()
    }

    @Test
    fun expandedWithoutALibraryKeepsTheOrbs() = runComposeUiTest {
        setContent {
            MaterialTheme { ShellAgentRail(state = ShellAgentRailState(entries = entries, expanded = true), actions = ShellAgentRailActions()) }
        }
        onNodeWithTag(ShellAgentRailTags.orb("Alpha")).assertExists()
    }
}
