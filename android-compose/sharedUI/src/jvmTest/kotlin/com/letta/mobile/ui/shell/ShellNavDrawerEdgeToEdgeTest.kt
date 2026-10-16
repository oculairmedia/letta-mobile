@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The phone's navigation drawer draws edge to edge: the rail's tinted surface and the panel's surface
 * run the whole window height, under the status bar and the gesture bar, while Home, the rail's New
 * button and the panel's Settings row stay inside the inset-padded area. Before the fix the drawer
 * sheet padded for the system bars around the whole drawer, so the rail's tint stopped short of both
 * bars and floated as a box. Desktop windows report no insets, so fake phone ones stand in.
 */
class ShellNavDrawerEdgeToEdgeTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val phone = WindowInsets(left = 0.dp, top = STATUS, right = 0.dp, bottom = NAV)

    private val input = ShellNavDrawerInput(
        agent = ShellPanelAgent(name = "Meridian", agentId = "meridian"),
        agents = listOf("meridian" to "Meridian", "lester" to "lester"),
    )

    private fun ComposeUiTest.drawer(insets: WindowInsets) = setContent {
        MaterialTheme {
            Box(Modifier.size(WIDTH, HEIGHT)) {
                ShellNavDrawerSheet(
                    state = ShellNavDrawerMapping.state(input, now),
                    actions = ShellNavDrawerActions(),
                    insets = insets,
                )
            }
        }
    }

    @Test
    fun railAndPanelSurfacesFillTheWindowUnderTheSystemBars() = runComposeUiTest {
        drawer(phone)
        listOf(ShellNavDrawerTags.RAIL_SURFACE, ShellNavDrawerTags.PANEL_SURFACE).forEach { tag ->
            val surface = onNodeWithTag(tag).bounds()
            assertEquals(0.dp, surface.top, "$tag must start under the status bar")
            assertEquals(HEIGHT, surface.bottom, "$tag must reach under the navigation bar")
        }
    }

    @Test
    fun railAndPanelContentStaysClearOfTheSystemBars() = runComposeUiTest {
        drawer(phone)
        val home = onNodeWithContentDescription("Home").bounds()
        val newButton = onNodeWithContentDescription("New").bounds()
        val agentName = onNodeWithText("Meridian").bounds()
        val settings = onNodeWithText("Settings").bounds()
        assertTrue(home.top >= STATUS, "Home sits under the status bar: ${home.top}")
        assertTrue(agentName.top >= STATUS, "The agent's name sits under the status bar: ${agentName.top}")
        assertTrue(newButton.bottom <= HEIGHT - NAV, "New sits under the navigation bar: ${newButton.bottom}")
        assertTrue(settings.bottom <= HEIGHT - NAV, "Settings sits under the navigation bar: ${settings.bottom}")
    }

    @Test
    fun withoutInsetsNothingMoves() = runComposeUiTest {
        drawer(WindowInsets(0, 0, 0, 0))
        val rail = onNodeWithTag(ShellNavDrawerTags.RAIL_SURFACE).bounds()
        assertEquals(0.dp, rail.top)
        assertEquals(HEIGHT, rail.bottom)
        assertTrue(onNodeWithContentDescription("Home").bounds().top < STATUS, "desktop/web keep the rail at the top")
    }

    private fun SemanticsNodeInteraction.bounds(): DpRect = getBoundsInRoot()

    private companion object {
        val WIDTH = 400.dp
        val HEIGHT = 700.dp
        val STATUS = 48.dp
        val NAV = 32.dp
    }
}
