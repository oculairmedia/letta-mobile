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

    // letta-mobile-oosow: this used to assert a "Search agents" rail control sat
    // above Home. #1595 added it against a rail that really had one; #1597 (P5)
    // then removed the separate search trigger — the plus menu's "New chat" opens
    // the agent picker, which searches — and left the assertion behind. The only
    // "Search agents" text left is a placeholder inside the EXPANDED library's
    // Jewel field, which this test deliberately does not compose (see below), so
    // the node could never resolve and :desktop:test has been red on main ever
    // since, blocking every PR that touches shared code. Home is what this test
    // is actually for; assert that and nothing else.
    @Test
    fun `collapsed rail shows Home and clicking it opens Home`() = runComposeUiTest {
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
