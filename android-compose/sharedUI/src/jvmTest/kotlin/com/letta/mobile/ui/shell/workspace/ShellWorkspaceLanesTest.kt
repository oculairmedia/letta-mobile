@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.letta.mobile.data.desktopshell.ShellLayoutStore
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellLayoutConfigStore
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
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
 * The shell draws its lanes from the layout config in the workspace state. The default config is
 * the shell as it was (the page, then the host's side pane) at every width; other modules share the
 * main lane where there is room and never push the page out of a narrow window; a stored config is
 * what a restarted shell draws, and a save redraws it in place.
 */
class ShellWorkspaceLanesTest {
    private fun state(lanes: ShellLayoutConfig) = LettaShellWorkspaceState(
        header = ShellWorkspaceHeaderState(title = "Window title"),
        navigation = ShellWorkspaceNavigation(
            rail = ShellLibraryRailState(agents = listOf("agent-a" to "Ada"), focus = ShellRailFocus(selectedAgentId = "agent-a")),
            panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = "agent-a")),
        ),
        layout = ShellWorkspaceLayout(lanes = lanes),
    )

    private val notesView: ShellModuleView = { slot, modifier -> Box(modifier.testTag(Notes + ":" + slot.objectRef)) }

    private val withNotes = ShellModules(mapOf(NotesView to notesView))

    private val host = LettaShellWorkspaceHost(
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
    )

    private fun lanes(main: List<ModuleSlot> = listOf(PageSlot), side: List<ModuleSlot> = listOf(ModuleSlot(ShellViews.SIDE_PANE))) =
        ShellLayoutConfig(version = ShellLayoutConfig.VERSION, lanes = mapOf(ShellLanes.MAIN to main, ShellLanes.SIDE to side))

    /** A window exactly [width] wide showing the workspace with [config] in its state and [views] added. */
    private fun window(
        width: Dp,
        config: ShellLayoutConfig = ShellLayoutConfig.Default,
        views: ShellModules = withNotes,
        block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = width.value.toInt(), height = WindowHeight.value.toInt()) {
        setContent {
            CompositionLocalProvider(LocalShellModules provides views) {
                LettaShellWorkspace(state(config), host, Modifier.size(width = width, height = WindowHeight))
            }
        }
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
    fun twoMainModulesShareTheLaneInAWideWindow() = window(1440.dp, lanes(main = listOf(PageSlot, NotesSlot))) {
        val page = bounds(Page)
        val notes = bounds("$Notes:project-notes")
        assertDp(page.width, notes.width, "the two modules are the same width")
        assertEquals(true, notes.left >= page.right, "notes sits after the page")
        assertDp(1440.dp - HostPaneWidth, notes.right, "the lane still ends at the host pane")
    }

    @Test
    fun aWindowWithoutRoomForTwoKeepsThePage() = window(760.dp, lanes(main = listOf(NotesSlot, PageSlot))) {
        onNodeWithTag(Page).assertIsDisplayed()
        onNodeWithTag("$Notes:project-notes").assertDoesNotExist()
    }

    @Test
    fun aPhoneShowsThePageAndLeavesOtherModulesOut() = window(
        360.dp,
        lanes(main = listOf(NotesSlot, PageSlot), side = listOf(ModuleSlot(NotesView, "side-notes"))),
    ) {
        assertDp(360.dp, bounds(Page).width, "the page has the whole phone")
        onNodeWithTag("$Notes:project-notes").assertDoesNotExist()
        onNodeWithTag("$Notes:side-notes").assertDoesNotExist()
    }

    @Test
    fun aSideModuleSitsAfterThePageAtItsOwnWidth() = window(1440.dp, lanes(side = listOf(ModuleSlot(NotesView, "side-notes")))) {
        val notes = bounds("$Notes:side-notes")
        assertDp(LettaDimens.Pane.sidePanelWidth, notes.width, "a side module is a side panel wide")
        assertDp(1440.dp, notes.right, "it ends at the window edge")
        assertEquals(true, bounds(Page).right <= notes.left, "the page ends before it")
        onNodeWithTag(HostPane).assertDoesNotExist()
    }

    @Test
    fun aViewNoHostSuppliesIsShownAsUnavailableBesideThePage() =
        window(1440.dp, lanes(main = listOf(PageSlot, NotesSlot)), views = ShellModules()) {
            onNodeWithTag(Page).assertIsDisplayed()
            onNodeWithTag(ShellModuleTags.UNAVAILABLE).assertIsDisplayed()
        }

    @Test
    fun aHostViewCannotReplaceThePage() = window(
        1440.dp,
        views = ShellModules(mapOf(ShellViews.PAGE to { _, modifier -> Box(modifier.testTag("impostor")) })),
    ) {
        onNodeWithTag(Page).assertIsDisplayed()
        onNodeWithTag("impostor").assertDoesNotExist()
    }

    @Test
    fun aModuleKeepsItsStateWhenTheLaneIsReordered() = runDesktopComposeUiTest(width = 1800, height = WindowHeight.value.toInt()) {
        var births = 0
        val stateful: ShellModuleView = { slot, modifier ->
            val born = remember { ++births }
            Box(modifier.testTag("stateful:" + slot.objectRef + ":" + born))
        }
        val first = ModuleSlot(NotesView, "first")
        val second = ModuleSlot(NotesView, "second")
        var order by mutableStateOf(listOf(PageSlot, first, second))
        setContent {
            CompositionLocalProvider(LocalShellModules provides ShellModules(mapOf(NotesView to stateful))) {
                LettaShellWorkspace(state(lanes(main = order)), host, Modifier.size(width = 1800.dp, height = WindowHeight))
            }
        }
        onNodeWithTag("stateful:first:1").assertIsDisplayed()
        onNodeWithTag("stateful:second:2").assertIsDisplayed()

        order = listOf(PageSlot, second, first)
        waitForIdle()

        onNodeWithTag("stateful:first:1").assertIsDisplayed()
        onNodeWithTag("stateful:second:2").assertIsDisplayed()
        assertEquals(true, bounds("stateful:second:2").left < bounds("stateful:first:1").left, "the order did change")
    }

    /**
     * The path a host takes: a [ShellLayoutController] over settings that a previous launch saved
     * [saved] into, its lanes collected as state into the workspace layout, [width] wide.
     */
    private fun restartedShell(width: Dp, saved: ShellLayoutConfig?, block: ComposeUiTest.(ShellLayoutController) -> Unit) {
        val settings = InMemorySecureSettingsStore()
        saved?.let { ShellLayoutConfigStore.forBackend(settings, null).save(it) }
        val controller = ShellLayoutController(
            ShellLayoutStore(settings, "layout"),
            ShellLayoutConfigStore.forBackend(settings, null),
        )
        runDesktopComposeUiTest(width = width.value.toInt(), height = WindowHeight.value.toInt()) {
            setContent {
                val lanes by controller.lanes.config.collectAsState()
                CompositionLocalProvider(LocalShellModules provides withNotes) {
                    LettaShellWorkspace(state(lanes), host, Modifier.size(width = width, height = WindowHeight))
                }
            }
            block(controller)
        }
    }

    @Test
    fun aStoredLayoutIsWhatTheWorkspaceDrawsAfterARestart() = restartedShell(1200.dp, lanes(main = listOf(PageSlot, NotesSlot))) {
        val page = bounds(Page)
        val notes = bounds("$Notes:project-notes")
        assertEquals(true, notes.left >= page.right, "the stored notes module sits after the page")
        assertDp(1200.dp - HostPaneWidth, notes.right, "the lane still ends at the host pane")
    }

    @Test
    fun aSaveWhileOnScreenRedrawsTheShell() = restartedShell(1200.dp, saved = null) { controller ->
        onNodeWithTag("$Notes:side-notes").assertDoesNotExist()
        onNodeWithTag(HostPane).assertIsDisplayed()

        controller.lanes.save(lanes(side = listOf(ModuleSlot(NotesView, "side-notes"))))
        waitForIdle()

        assertDp(1200.dp, bounds("$Notes:side-notes").right, "the saved side module now ends at the window edge")
        onNodeWithTag(HostPane).assertDoesNotExist()
    }

    private fun storedLayoutKeepsThePage(width: Dp) = restartedShell(width, lanes(main = listOf(PageSlot, NotesSlot))) {
        assertDp(width - HostPaneWidth, bounds(Page).right, "the page runs to the host pane at $width")
        assertDp(0.dp, bounds(Page).top, "the page starts at the top at $width")
        onNodeWithTag("$Notes:project-notes").assertDoesNotExist()
    }

    @Test
    fun aStoredLayoutKeepsThePageOnAPhone() = storedLayoutKeepsThePage(360.dp)

    @Test
    fun aStoredLayoutKeepsThePageJustBelowTheWideBreakpoint() = storedLayoutKeepsThePage(700.dp)

    @Test
    fun aStoredLayoutKeepsThePageJustAboveTheWideBreakpoint() = storedLayoutKeepsThePage(760.dp)
}
