@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Home is a fleet-wide destination, so it is reached from the agent rail in
 * both rail modes (auijj) - never from the per-agent sidebar header.
 */
class DesktopAgentRailHomeUiTest {
    private fun state(expanded: Boolean) = DesktopAgentRailState(
        agents = listOf("agent-1" to "Alpha", "agent-2" to "Beta"),
        focus = DesktopAgentRailFocus(
            selectedAgentId = "agent-1",
            thinkingAgentId = null,
            avatarStyleByAgentId = emptyMap(),
        ),
        expanded = expanded,
    )

    @Test
    fun `collapsed rail shows Home under Search and clicking it opens Home`() = runComposeUiTest {
        var homeClicks = 0
        setContent {
            MaterialTheme {
                DesktopAgentRail(
                    state = state(expanded = false),
                    actions = DesktopAgentRailActions(
                        onAgentSelected = {},
                        onNewSession = {},
                        onHome = { homeClicks++ },
                    ),
                )
            }
        }

        onNodeWithContentDescription("Search agents").assertIsDisplayed()
        onNodeWithContentDescription("Home").assertIsDisplayed().performClick()
        assertEquals(1, homeClicks)
    }

    // The expanded rail keeps Home too (same RailHeaderRow, with its label), but it is not
    // composed here: ExpandedAgentLibrary draws a Jewel TextField, whose JDK-25 bytecode the
    // JVM-21 test runtime cannot load (see DesktopExecutionLocationPickerTest, letta-mobile-sixv8.1).
    @Test
    fun `a selected Home is part of the rail state`() {
        assertEquals(true, state(expanded = false).copy(homeSelected = true).homeSelected)
    }
}
