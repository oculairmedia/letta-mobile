@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.ui.shell.modules.ShellModuleTags
import com.letta.mobile.ui.shell.modules.ShellModuleView
import com.letta.mobile.ui.shell.modules.ShellModules
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.test.Test
import kotlin.test.assertEquals

private const val Page = "lanes-page"
private const val HostPane = "lanes-host-pane"
private const val Notes = "lanes-notes"
private val HostPaneWidth = 100.dp
private val WindowHeight = 800.dp

private val NotesView = RuntimeViewId("test.notes")
private val NotesSlot = ModuleSlot(NotesView, objectRef = "project-notes")
private val PageSlot = ModuleSlot(ShellViews.PAGE)

/**
 * letta-mobile-n2stx.2: the shell draws its lanes from a layout config. The default config is the
 * shell as it was (the page, then the host's side pane) at every width; other modules share the
 * main lane where there is room and never push the page out of a narrow window.
 */
class ShellWorkspaceLanesTest {
    private val state = LettaShellWorkspaceState(
        header = ShellWorkspaceHeaderState(title = "Window title"),
        navigation = ShellWorkspaceNavigation(
            rail = ShellLibraryRailState(agents = listOf("agent-a" to "Ada"), focus = ShellRailFocus(selectedAgentId = "agent-a")),
            panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = "agent-a")),
        ),
    )

    private val notesView: ShellModuleView = { slot, modifier -> Box(modifier.testTag(Notes + ":" + slot.objectRef)) }

    private fun host(modules: ShellModules = ShellModules()) = LettaShellWorkspaceHost(
        actions = ShellWorkspaceNavigationActions(),
        slots = LettaShellWorkspaceSlots(
            main = { modifier -> Box(modifier.testTag(Page)) },
            agentCard = {},
            sidePane = { Box(Modifier.width(HostPaneWidth).fillMaxHeight().testTag(HostPane)) },
            overlays = {},
        ),
        chrome = ShellWindowChrome.Hoisted {},
        drawer = ShellWorkspaceDrawer(),
        events = ShellWorkspaceEvents(),
        modules = modules,
    )

    private fun lanes(main: List<ModuleSlot> = listOf(PageSlot), side: List<ModuleSlot> = listOf(ModuleSlot(ShellViews.SIDE_PANE))) =
        ShellLayoutConfig(version = ShellLayoutConfig.VERSION, lanes = mapOf(ShellLanes.MAIN to main, ShellLanes.SIDE to side))

    private fun withNotes(config: ShellLayoutConfig) = ShellModules(config, mapOf(NotesView to notesView))

    /** A window exactly [width] wide showing the workspace with [modules]. */
    private fun window(width: Dp, modules: ShellModules = ShellModules(), block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width.value.toInt(), height = WindowHeight.value.toInt()) {
            setContent { LettaShellWorkspace(state, host(modules), Modifier.size(width = width, height = WindowHeight)) }
            block()
        }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun assertDp(expected: Dp, actual: Dp, what: String) =
        assertEquals(expected.value, actual.value, absoluteTolerance = 1f, message = what)

    private fun pageRunsToTheHostPane(width: Dp) = window(width) {
        val page = bounds(Page)
        val pane = bounds(HostPane)
        assertDp(width - HostPaneWidth, page.right, "the page ends where the host pane starts at $width")
        assertDp(width, pane.right, "the host pane ends at the window edge at $width")
        assertDp(WindowHeight, page.bottom, "the page runs to the bottom at $width")
        assertDp(0.dp, page.top, "the page starts at the top at $width")
    }

    @Test
    fun theDefaultIsThePageThenTheHostPaneOnAPhone() = pageRunsToTheHostPane(360.dp)

    @Test
    fun theDefaultIsThePageThenTheHostPaneJustBelowTheWideBreakpoint() = pageRunsToTheHostPane(700.dp)

    @Test
    fun theDefaultIsThePageThenTheHostPaneJustAboveTheWideBreakpoint() = pageRunsToTheHostPane(760.dp)

    @Test
    fun theDefaultIsThePageThenTheHostPaneOnADesktop() = pageRunsToTheHostPane(1200.dp)

    @Test
    fun twoMainModulesShareTheLaneInAWideWindow() = window(1440.dp, withNotes(lanes(main = listOf(PageSlot, NotesSlot)))) {
        val page = bounds(Page)
        val notes = bounds("$Notes:project-notes")
        assertDp(page.width, notes.width, "the two modules are the same width")
        assertEquals(true, notes.left >= page.right, "notes sits after the page")
        assertDp(1440.dp - HostPaneWidth, notes.right, "the lane still ends at the host pane")
    }

    @Test
    fun aWindowWithoutRoomForTwoKeepsThePage() = window(760.dp, withNotes(lanes(main = listOf(NotesSlot, PageSlot)))) {
        onNodeWithTag(Page).assertIsDisplayed()
        onNodeWithTag("$Notes:project-notes").assertDoesNotExist()
    }

    @Test
    fun aPhoneShowsThePageAndLeavesOtherModulesOut() = window(
        360.dp,
        ShellModules(
            lanes(main = listOf(NotesSlot, PageSlot), side = listOf(ModuleSlot(NotesView, "side-notes"))),
            mapOf(NotesView to notesView),
        ),
    ) {
        assertDp(360.dp, bounds(Page).width, "the page has the whole phone")
        onNodeWithTag("$Notes:project-notes").assertDoesNotExist()
        onNodeWithTag("$Notes:side-notes").assertDoesNotExist()
    }

    @Test
    fun aSideModuleSitsAfterThePageAtItsOwnWidth() = window(1440.dp, withNotes(lanes(side = listOf(ModuleSlot(NotesView, "side-notes"))))) {
        val notes = bounds("$Notes:side-notes")
        assertDp(LettaDimens.Pane.sidePanelWidth, notes.width, "a side module is a side panel wide")
        assertDp(1440.dp, notes.right, "it ends at the window edge")
        assertEquals(true, bounds(Page).right <= notes.left, "the page ends before it")
        onNodeWithTag(HostPane).assertDoesNotExist()
    }

    @Test
    fun aViewNoHostSuppliesIsShownAsUnavailableBesideThePage() = window(1440.dp, ShellModules(lanes(main = listOf(PageSlot, NotesSlot)))) {
        onNodeWithTag(Page).assertIsDisplayed()
        onNodeWithTag(ShellModuleTags.UNAVAILABLE).assertIsDisplayed()
    }

    @Test
    fun aHostViewCannotReplaceThePage() = window(
        1440.dp,
        ShellModules(views = mapOf(ShellViews.PAGE to { _, modifier -> Box(modifier.testTag("impostor")) })),
    ) {
        onNodeWithTag(Page).assertIsDisplayed()
        onNodeWithTag("impostor").assertDoesNotExist()
    }
}
