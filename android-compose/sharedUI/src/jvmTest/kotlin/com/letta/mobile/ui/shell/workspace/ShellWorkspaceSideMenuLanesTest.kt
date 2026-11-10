@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.sidemenu.PanelViews
import com.letta.mobile.data.shell.sidemenu.RailViews
import com.letta.mobile.ui.shell.rail.ShellAgentRailTags
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelTags
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import kotlin.test.Test

/**
 * The workspace hands its stored layout's rail and panel lanes to the rail and the agent panel, in
 * a wide window (rail beside the sidebar) and in a compact one (the drawer): both read one lane.
 */
class ShellWorkspaceSideMenuLanesTest {
    private val withoutHomeOrNewChat = ShellLayoutConfig.Default.copy(
        lanes = ShellLayoutConfig.Default.lanes +
            (ShellLanes.RAIL to listOf(RailViews.AGENTS, RailViews.NEW).map(::ModuleSlot)) +
            (ShellLanes.PANEL to (PanelViews.Default - PanelViews.NEW_CHAT).map(::ModuleSlot)),
    )

    private fun window(width: Int, config: ShellLayoutConfig, check: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width, height = HEIGHT) {
            val drawer = ShellWorkspaceDrawer().apply { open = true }
            val state = LettaShellWorkspaceState(
                header = ShellWorkspaceHeaderState(title = "Window title"),
                navigation = ShellWorkspaceNavigation(
                    rail = ShellLibraryRailState(agents = listOf("agent-a" to "Ada"), focus = ShellRailFocus(selectedAgentId = "agent-a")),
                    panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = "agent-a")),
                ),
                layout = ShellWorkspaceLayout(lanes = config),
            )
            val host = LettaShellWorkspaceHost(
                actions = ShellWorkspaceNavigationActions(),
                slots = LettaShellWorkspaceSlots(main = { Box(it) }, agentCard = {}, sidePane = {}, overlays = {}),
                chrome = ShellWindowChrome.Hoisted {},
                drawer = drawer,
                events = ShellWorkspaceEvents(),
            )
            setContent { LettaShellWorkspace(state, host, Modifier.size(width.dp, HEIGHT.dp)) }
            waitForIdle()
            onNodeWithTag(ShellAgentRailTags.RAIL).assertExists()
            onNodeWithTag(ShellAgentPanelTags.PANEL).assertExists()
            check()
        }

    @Test
    fun theDefaultLayoutDrawsEveryRailAndPanelPart() = listOf(360, 1200).forEach { width ->
        window(width, ShellLayoutConfig.Default) {
            onNodeWithContentDescription("Home", useUnmergedTree = true).assertExists()
            onNodeWithText("New chat").assertExists()
            onNodeWithText("Settings").assertExists()
        }
    }

    @Test
    fun theStoredLanesReachTheRailAndPanelAtEveryWidth() = listOf(360, 1200).forEach { width ->
        window(width, withoutHomeOrNewChat) {
            onNodeWithContentDescription("Home", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithContentDescription("New", useUnmergedTree = true).assertExists()
            onNodeWithText("New chat").assertDoesNotExist()
            onNodeWithText("Settings").assertExists()
        }
    }

    private companion object {
        const val HEIGHT = 800
    }
}
