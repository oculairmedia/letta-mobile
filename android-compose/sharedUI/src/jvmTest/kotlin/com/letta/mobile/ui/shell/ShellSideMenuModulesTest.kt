@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.sidemenu.PanelViews
import com.letta.mobile.data.shell.sidemenu.RailViews
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.shell.rail.LocalRailLane
import com.letta.mobile.ui.shell.rail.ShellAgentRail
import com.letta.mobile.ui.shell.rail.ShellAgentRailTags
import com.letta.mobile.ui.shell.sidebar.LocalPanelLane
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanel
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelTags
import com.letta.mobile.ui.shell.sidebar.ShellChatOptionsTags
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.SharedMaterialTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The side menu drawn from the shell layout's rail and panel lanes: the default lanes are the rail
 * and panel as they always were, a reordered lane redraws in its order with Settings kept at the
 * bottom, a lane without a part leaves it out, and an unknown or repeated view never breaks either.
 * A wide window draws the rail beside the sidebar; a compact one draws the drawer; one lane drives both.
 */
class ShellSideMenuModulesTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val input = ShellNavDrawerInput(
        agent = ShellPanelAgent(name = "Meridian", agentId = "meridian"),
        agents = listOf("meridian" to "Meridian", "lester" to "lester"),
        conversations = listOf(
            Conversation(
                id = ConversationId("c-1"),
                agentId = AgentId("meridian"),
                summary = "Handoff",
                lastMessageAt = "2026-10-07T11:56:00Z",
            ),
        ),
    )

    /** The rail's and the panel's parts in order, as the workspace provides them from its layout. */
    private class Lanes(val rail: List<RuntimeViewId> = RailViews.Default, val panel: List<RuntimeViewId> = PanelViews.Default)

    /** The side menu at [width] dp: the rail and sidebar when wide, the drawer when compact. */
    private fun side(width: Int, config: Lanes, check: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width, height = HEIGHT) {
            val state = ShellNavDrawerMapping.state(input, now)
            setContent {
                SharedMaterialTheme {
                    ProvideLettaHost(LettaHost.Pointer) {
                        Surface(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalRailLane provides config.rail, LocalPanelLane provides config.panel) {
                                if (width.dp >= LettaDimens.Pane.wideBreakpoint) {
                                    Row(Modifier.fillMaxSize()) {
                                        ShellAgentRail(state.rail, ShellNavDrawerActions().rail)
                                        ShellAgentPanel(state.panel, ShellNavDrawerActions().panel, Modifier.width(280.dp).fillMaxHeight())
                                    }
                                } else {
                                    ShellNavDrawer(state, ShellNavDrawerActions(), Modifier.width(width.dp))
                                }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            check()
        }

    private fun SemanticsNodeInteraction.top() = getUnclippedBoundsInRoot().top.value

    private fun ComposeUiTest.assertOrder(vararg nodes: SemanticsNodeInteraction) {
        val tops = nodes.map { it.top() }
        assertEquals(tops.sorted(), tops, "expected top-to-bottom order, got $tops")
    }

    private fun ComposeUiTest.home() = onNodeWithContentDescription("Home", useUnmergedTree = true)
    private fun ComposeUiTest.newAgent() = onNodeWithContentDescription("New", useUnmergedTree = true)
    private fun ComposeUiTest.orb() = onNodeWithTag(ShellAgentRailTags.orb("lester"), useUnmergedTree = true)
    private fun ComposeUiTest.header() = onNodeWithTag(ShellAgentPanelTags.AGENT_MENU)
    private fun ComposeUiTest.sections() = onNodeWithText("Memory")
    private fun ComposeUiTest.newChat() = onNodeWithText("New chat")
    private fun ComposeUiTest.library() = onNodeWithTag(ShellAgentPanelTags.LIBRARY)
    private fun ComposeUiTest.settings() = onNodeWithText("Settings")

    /** Settings sits at the panel's bottom, below every other part. */
    private fun ComposeUiTest.assertSettingsAtTheBottom() {
        val panel = onNodeWithTag(ShellAgentPanelTags.PANEL).getUnclippedBoundsInRoot()
        val settings = settings().getUnclippedBoundsInRoot()
        val gap = panel.bottom.value - settings.bottom.value
        assertTrue(gap < BOTTOM_SLACK, "Settings ends at ${settings.bottom}, panel at ${panel.bottom}")
    }

    @Test
    fun theDefaultLanesAreTodaysOrderAtEveryWidth() = WIDTHS.forEach { width ->
        side(width, Lanes()) {
            assertOrder(home(), orb(), newAgent())
            assertOrder(header(), sections(), newChat(), library(), settings())
            assertSettingsAtTheBottom()
        }
    }

    @Test
    fun aReorderedPanelLaneRedrawsInItsOrderWithSettingsStillAtTheBottom() = listOf(360, 1200).forEach { width ->
        val reordered = listOf(PanelViews.SETTINGS, PanelViews.NEW_CHAT, PanelViews.HEADER, PanelViews.LIBRARY, PanelViews.SECTIONS)
        side(width, Lanes(panel = reordered)) {
            assertOrder(newChat(), header(), library(), sections(), settings())
            assertSettingsAtTheBottom()
        }
    }

    @Test
    fun aLaneWithoutAPartLeavesItOut() = listOf(360, 1200).forEach { width ->
        val panel = listOf(PanelViews.HEADER, PanelViews.SECTIONS, PanelViews.LIBRARY, PanelViews.SETTINGS)
        side(width, Lanes(rail = listOf(RailViews.AGENTS, RailViews.NEW), panel = panel)) {
            home().assertDoesNotExist()
            newChat().assertDoesNotExist()
            assertOrder(orb(), newAgent())
            assertOrder(header(), sections(), library(), settings())
            assertSettingsAtTheBottom()
        }
    }

    @Test
    fun withoutTheLibrarySettingsStaysAtTheBottom() = listOf(360, 1200).forEach { width ->
        side(width, Lanes(panel = listOf(PanelViews.HEADER, PanelViews.SETTINGS))) {
            library().assertDoesNotExist()
            sections().assertDoesNotExist()
            assertSettingsAtTheBottom()
        }
    }

    @Test
    fun theAgentMenusDialogsOpenWhereverTheHeaderSits() = listOf(360, 1200).forEach { width ->
        val panel = listOf(PanelViews.SECTIONS, PanelViews.LIBRARY, PanelViews.HEADER, PanelViews.SETTINGS)
        side(width, Lanes(panel = panel)) {
            assertOrder(sections(), library(), header())
            header().performClick()
            onNodeWithText("Archived chats").performClick()
            waitForIdle()
            onNodeWithTag(ShellChatOptionsTags.ARCHIVED).assertExists()
            onNodeWithText("No archived chats").assertExists()
        }
    }

    @Test
    fun anUnknownOrRepeatedViewDoesNotBreakTheRailOrPanel() = listOf(360, 1200).forEach { width ->
        val unknown = RuntimeViewId("someone.else")
        val rail = listOf(unknown, RailViews.HOME, RailViews.HOME, RailViews.AGENTS, RailViews.NEW, RailViews.NEW)
        val panel = listOf(PanelViews.HEADER, unknown, PanelViews.HEADER, PanelViews.SECTIONS, PanelViews.LIBRARY, PanelViews.SETTINGS)
        side(width, Lanes(rail = rail, panel = panel)) {
            onAllNodesWithContentDescription("Home", useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithContentDescription("New", useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithTag(ShellAgentPanelTags.AGENT_MENU).assertCountEquals(1)
            onAllNodesWithText("Memory").assertCountEquals(1)
            assertOrder(home(), orb(), newAgent())
            assertOrder(header(), sections(), library(), settings())
        }
    }

    @Test
    fun emptiedLanesDrawAnEmptyRailAndPanel() = listOf(360, 1200).forEach { width ->
        side(width, Lanes(rail = emptyList(), panel = emptyList())) {
            onNodeWithTag(ShellAgentRailTags.RAIL).assertExists()
            onNodeWithTag(ShellAgentPanelTags.PANEL).assertExists()
            home().assertDoesNotExist()
            settings().assertDoesNotExist()
        }
    }

    private companion object {
        const val HEIGHT = 900
        const val BOTTOM_SLACK = 40f
        val WIDTHS = listOf(360, 700, 760, 1200)
    }
}
