@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PageTag = "workspace-page"

private val Navigation = ShellWorkspaceNavigation(
    rail = ShellLibraryRailState(
        agents = listOf("agent-a" to "Ada", "agent-b" to "Bo"),
        focus = ShellRailFocus(selectedAgentId = "agent-a"),
    ),
    panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = "agent-a")),
)

private fun workspaceState(title: String = "Window title", forceCompact: Boolean = false) = LettaShellWorkspaceState(
    header = ShellWorkspaceHeaderState(title = title, sidebar = ShellSidebarControl(collapsed = false, onToggle = {})),
    navigation = Navigation,
    layout = ShellWorkspaceLayout(forceCompact = forceCompact),
)

private fun workspaceHost(
    chrome: ShellWindowChrome = ShellWindowChrome.Inline,
    drawer: ShellWorkspaceDrawer? = null,
) = LettaShellWorkspaceHost(
    actions = ShellWorkspaceNavigationActions(),
    slots = LettaShellWorkspaceSlots(main = { modifier -> Text("The page", modifier.testTag(PageTag)) }),
    chrome = chrome,
    drawer = drawer,
)

/** letta-mobile-ckmub.17.4: the one workspace desktop and web mount, wide and compact. */
class LettaShellWorkspaceTest {
    private fun sized(width: Dp) = Modifier.size(width = width, height = 800.dp)

    @Test
    fun wideWindowShowsRailPanelAndPageWithTheHeaderInline() = runComposeUiTest {
        setContent { LettaShellWorkspace(workspaceState(), workspaceHost(), sized(1440.dp)) }

        onNodeWithTag(LettaShellWorkspaceTags.WIDE).assertExists()
        onNodeWithTag(PageTag).assertExists()
        onNodeWithText("Window title").assertExists()
        onNodeWithTag(ShellWorkspaceHeaderTags.SIDEBAR_TOGGLE).assertExists()
    }

    @Test
    fun narrowWindowFoldsTheNavigationIntoTheDrawerTheHeaderOpens() = runComposeUiTest {
        val drawer = ShellWorkspaceDrawer()
        setContent { LettaShellWorkspace(workspaceState(), workspaceHost(drawer = drawer), sized(500.dp)) }

        onNodeWithTag(LettaShellWorkspaceTags.COMPACT).assertExists()
        onNodeWithTag(LettaShellWorkspaceTags.WIDE).assertDoesNotExist()
        onNodeWithTag(ShellWorkspaceHeaderTags.SIDEBAR_TOGGLE).performClick()
        waitForIdle()
        assertTrue(drawer.open)
    }

    @Test
    fun forcedCompactFoldsAWideWindowToo() = runComposeUiTest {
        setContent { LettaShellWorkspace(workspaceState(forceCompact = true), workspaceHost(), sized(1440.dp)) }

        onNodeWithTag(LettaShellWorkspaceTags.COMPACT).assertExists()
    }

    @Test
    fun hoistedChromeHandsTheHeaderToTheHostInsteadOfDrawingIt() = runComposeUiTest {
        var hoisted: ShellWorkspaceHeaderState? = null
        setContent {
            val host = workspaceHost(chrome = ShellWindowChrome.Hoisted { hoisted = it })
            LettaShellWorkspace(workspaceState(title = "Hoisted title"), host, sized(1440.dp))
        }

        waitForIdle()
        assertEquals("Hoisted title", hoisted?.title)
        onNodeWithText("Hoisted title").assertDoesNotExist()
        onNodeWithTag(PageTag).assertExists()
    }

    @Test
    fun aCompactHoistedHeaderOpensTheDrawer() = runComposeUiTest {
        var hoisted: ShellWorkspaceHeaderState? = null
        val drawer = ShellWorkspaceDrawer()
        setContent {
            LettaShellWorkspace(workspaceState(), workspaceHost(ShellWindowChrome.Hoisted { hoisted = it }, drawer), sized(500.dp))
        }

        waitForIdle()
        val control = hoisted?.sidebar
        assertTrue(control?.opensDrawer == true)
        control?.onToggle?.invoke()
        assertTrue(drawer.open)
    }
}
