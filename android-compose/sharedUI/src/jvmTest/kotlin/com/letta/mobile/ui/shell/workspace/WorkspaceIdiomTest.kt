@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.host.currentPaneWindowClass
import com.letta.mobile.ui.host.lettaIdiom
import com.letta.mobile.ui.shell.header.ShellHeaderDefaults
import com.letta.mobile.ui.shell.header.ShellHeaderTags
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the page saw of the idiom: the shell's flag, the chat's, and how a modal resolves. */
private class SeenIdiom {
    var input: LettaInput? = null
    var chatTouch: Boolean? = null
    var modal: LettaPaneKind? = null
}

/** The phone idiom wherever the pointer is coarse or the window compact, on every platform. */
class WorkspaceIdiomTest {
    @Test
    fun aCoarsePointerIsTouchAtAnyWidth() {
        assertEquals(LettaInput.Touch, lettaIdiom(LettaInput.Touch, compact = false))
        assertEquals(LettaInput.Touch, lettaIdiom(LettaInput.Touch, compact = true))
    }

    @Test
    fun aCompactWindowIsTouchEvenWithAMouse() {
        assertEquals(LettaInput.Touch, lettaIdiom(LettaInput.Pointer, compact = true))
    }

    @Test
    fun onlyAWideWindowWithAFinePointerIsPointer() {
        assertEquals(LettaInput.Pointer, lettaIdiom(LettaInput.Pointer, compact = false))
    }

    @Test
    fun aWideMouseWindowDrawsThePointerIdiomInTheShellAndTheChat() = runComposeUiTest {
        val seen = show(LettaHost.Pointer, 1440.dp)

        assertEquals(LettaInput.Pointer, seen.input)
        assertEquals(false, seen.chatTouch)
        assertEquals(LettaPaneKind.Dialog, seen.modal)
    }

    @Test
    fun aNarrowMouseWindowDrawsThePhoneIdiomInTheShellAndTheChat() = runComposeUiTest {
        val seen = show(LettaHost.Pointer, 390.dp)

        assertEquals(LettaInput.Touch, seen.input)
        assertEquals(true, seen.chatTouch)
        // The modal resolves against the workspace's own fold: the phone's sheet.
        assertEquals(LettaPaneKind.Sheet, seen.modal)
    }

    @Test
    fun aForcedCompactWideWindowResolvesItsPanesAsCompactToo() = runComposeUiTest {
        val seen = show(LettaHost.Pointer, 1440.dp, forceCompact = true)

        assertEquals(LettaInput.Touch, seen.input)
        assertEquals(LettaPaneKind.Sheet, seen.modal)
    }

    @Test
    fun aWideTouchWindowStaysTouch() = runComposeUiTest {
        val seen = show(LettaHost.Touch, 1440.dp)

        assertEquals(LettaInput.Touch, seen.input)
        assertEquals(true, seen.chatTouch)
        // A tablet's wide window still opens a modal as a dialog.
        assertEquals(LettaPaneKind.Dialog, seen.modal)
    }

    @Test
    fun aCompactHeaderHoistedOutOfTheWorkspaceIsDrawnAsThePhonesHeader() = runComposeUiTest {
        setContent {
            ProvideLettaHost(LettaHost.Pointer) {
                val minimal = ShellMinimalHeader(identity = null, onOpenNavigation = {})
                ShellWorkspaceHeader(ShellWorkspaceHeaderState(title = "Hoisted", minimal = minimal))
            }
        }

        onNodeWithTag(ShellHeaderTags.HEADER).assertHeightIsAtLeast(ShellHeaderDefaults.TouchMinHeight)
    }

    private fun ComposeUiTest.show(root: LettaHost, width: Dp, forceCompact: Boolean = false): SeenIdiom {
        val seen = SeenIdiom()
        setContent {
            ProvideLettaHost(root) {
                LettaShellWorkspace(
                    LettaShellWorkspaceState(
                        header = ShellWorkspaceHeaderState(title = "Idiom"),
                        navigation = ShellWorkspaceNavigation(
                            rail = ShellLibraryRailState(agents = emptyList(), focus = ShellRailFocus()),
                            panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = "agent-a")),
                        ),
                        layout = ShellWorkspaceLayout(forceCompact = forceCompact),
                    ),
                    host(main = { modifier -> Record(seen, modifier) }),
                    Modifier.size(width = width, height = 800.dp),
                )
            }
        }
        waitForIdle()
        return seen
    }

    private fun host(main: @Composable (Modifier) -> Unit) = LettaShellWorkspaceHost(
        actions = ShellWorkspaceNavigationActions(),
        slots = LettaShellWorkspaceSlots(main = main, agentCard = {}, sidePane = {}, overlays = {}),
        chrome = ShellWindowChrome.Inline,
        drawer = ShellWorkspaceDrawer(),
        events = ShellWorkspaceEvents(),
    )
}

@Composable
private fun Record(seen: SeenIdiom, modifier: Modifier) {
    val host = LocalLettaHost.current
    seen.input = host.input
    seen.chatTouch = touchStyle()
    seen.modal = host.resolve(LettaPaneKind.Modal, currentPaneWindowClass())
    Box(modifier)
}
