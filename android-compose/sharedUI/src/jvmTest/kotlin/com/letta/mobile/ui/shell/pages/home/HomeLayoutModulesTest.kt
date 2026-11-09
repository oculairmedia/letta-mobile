@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.home.FleetAgentStat
import com.letta.mobile.data.home.FleetOverview
import com.letta.mobile.data.home.FleetRecentConversation
import com.letta.mobile.data.home.FleetSortKey
import com.letta.mobile.data.home.FleetSummary
import com.letta.mobile.data.home.HomePageActions
import com.letta.mobile.data.home.HomePageReducer
import com.letta.mobile.data.home.HomePageState
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.home.HomeStats
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.HomeViews
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.shell.modules.LocalShellLayout
import com.letta.mobile.ui.theme.SharedMaterialTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Home's sections drawn from the shell layout's home lane: the default lane is the page as it
 * always was, a reordered lane redraws the sections in its order on a wide page, a lane without a
 * section leaves it out, and a view Home does not know draws nothing. A compact page docks the
 * composer below the list wherever the lane puts it and never shows the fleet table.
 */
class HomeLayoutModulesTest {
    private val scout = FleetAgentStat(
        agentId = "a-1",
        name = "Scout",
        model = "openai/gpt-5",
        conversationCount = 3,
        lastActivity = null,
        running = true,
        activityByDay = emptyList(),
    )
    private val recent = FleetRecentConversation("c-1", "a-1", "Scout", "Trip planning", "Booked the train", null, "Queued")
    private val fleetRow = HomePageTags.agentRow(scout)

    private fun state(): HomePageState {
        val fleet = FleetOverview(
            summary = FleetSummary(totalAgents = 1, totalConversations = 3, runningNow = 1),
            agents = listOf(scout),
            recent = listOf(recent),
        )
        val available = setOf(HomeShortcut.TOOLS, HomeShortcut.SCHEDULES)
        val withFleet = HomePageReducer.withFleet(HomePageState(availableShortcuts = available), fleet)
        val withPins = HomePageReducer.withPins(withFleet, listOf("shortcut:SCHEDULES"))
        return HomePageReducer.withStats(withPins, HomeStats(loading = false, toolCount = 12))
    }

    private fun withHomeLane(views: List<RuntimeViewId>): ShellLayoutConfig =
        ShellLayoutConfig.Default.copy(lanes = ShellLayoutConfig.Default.lanes + (ShellLanes.HOME to views.map { ModuleSlot(it) }))

    /** Home at [width] dp; a null [config] leaves the layout local unprovided, as outside a workspace. */
    private fun home(width: Int, config: ShellLayoutConfig?, check: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width, height = HEIGHT) {
            setContent {
                SharedMaterialTheme {
                    ProvideLettaHost(LettaHost.Pointer) {
                        Surface(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalShellLayout provides (config ?: LocalShellLayout.current)) {
                                HomePage(state = state(), callbacks = HomePageCallbacks(NoActions, navigation))
                            }
                        }
                    }
                }
            }
            waitForIdle()
            check()
        }

    private fun ComposeUiTest.assertOrder(vararg tags: String) {
        val tops = tags.map { onNodeWithTag(it).getUnclippedBoundsInRoot().top.value }
        assertEquals(tops.sorted(), tops, "expected top-to-bottom order ${tags.toList()}, got $tops")
    }

    private fun ComposeUiTest.assertAbsent(vararg tags: String) = tags.forEach { onNodeWithTag(it).assertDoesNotExist() }

    @Test
    fun theDefaultLaneIsThePageAsItWasAtEveryWidth() {
        for (config in listOf(null, ShellLayoutConfig.Default)) {
            WIDE_WIDTHS.forEach { width ->
                home(width, config) {
                    assertOrder(
                        HomePageTags.COMPOSER, HomePageTags.NOW_STRIP, HomePageTags.RECENTS, HomePageTags.AGENT_ROW,
                        HomePageTags.STATS_STRIP, HomePageTags.SHORTCUT_ROW, fleetRow,
                    )
                }
            }
            COMPACT_WIDTHS.forEach { width ->
                home(width, config) {
                    assertOrder(
                        HomePageTags.NOW_STRIP, HomePageTags.RECENTS, HomePageTags.AGENT_ROW,
                        HomePageTags.STATS_STRIP, HomePageTags.SHORTCUT_ROW, HomePageTags.COMPOSER,
                    )
                    assertAbsent(fleetRow)
                }
            }
        }
    }

    @Test
    fun aReorderedLaneRedrawsTheSectionsInItsOrderAndAPhoneKeepsTheComposerDocked() {
        val reordered = withHomeLane(
            listOf(
                HomeViews.STATS, HomeViews.RECENTS, HomeViews.FLEET, HomeViews.NOW,
                HomeViews.COMPOSER, HomeViews.PINNED, HomeViews.AGENTS,
            ),
        )
        WIDE_WIDTHS.forEach { width ->
            home(width, reordered) {
                assertOrder(
                    HomePageTags.STATS_STRIP, HomePageTags.RECENTS, fleetRow, HomePageTags.NOW_STRIP,
                    HomePageTags.COMPOSER, HomePageTags.SHORTCUT_ROW, HomePageTags.AGENT_ROW,
                )
            }
        }
        COMPACT_WIDTHS.forEach { width ->
            home(width, reordered) {
                assertOrder(
                    HomePageTags.STATS_STRIP, HomePageTags.RECENTS, HomePageTags.NOW_STRIP,
                    HomePageTags.SHORTCUT_ROW, HomePageTags.AGENT_ROW, HomePageTags.COMPOSER,
                )
                assertAbsent(fleetRow)
            }
        }
    }

    @Test
    fun aLaneWithoutASectionLeavesItOut() {
        val noStatsNoComposer = withHomeLane(
            listOf(HomeViews.NOW, HomeViews.RECENTS, HomeViews.AGENTS, HomeViews.PINNED, HomeViews.FLEET),
        )
        (WIDE_WIDTHS + COMPACT_WIDTHS).forEach { width ->
            home(width, noStatsNoComposer) {
                assertAbsent(HomePageTags.STATS_STRIP, HomePageTags.COMPOSER)
                assertOrder(HomePageTags.NOW_STRIP, HomePageTags.RECENTS, HomePageTags.AGENT_ROW)
            }
        }
    }

    @Test
    fun aViewHomeDoesNotKnowDrawsNothingAndTheRestStillShow() {
        val unknown = withHomeLane(
            listOf(RuntimeViewId("home.weather"), HomeViews.COMPOSER, HomeViews.RECENTS, RuntimeViewId("someone.else"), HomeViews.STATS),
        )
        (WIDE_WIDTHS + COMPACT_WIDTHS).forEach { width ->
            home(width, unknown) {
                onNodeWithTag(HomePageTags.PAGE).assertExists()
                onNodeWithTag(HomePageTags.COMPOSER).assertExists()
                assertOrder(HomePageTags.RECENTS, HomePageTags.STATS_STRIP)
                assertAbsent(HomePageTags.NOW_STRIP, HomePageTags.AGENT_ROW, fleetRow)
            }
        }
    }

    @Test
    fun aSectionNamedTwiceIsDrawnOnce() {
        val repeated = ShellLayoutConfig.Default.copy(
            lanes = ShellLayoutConfig.Default.lanes + (
                ShellLanes.HOME to listOf(
                    ModuleSlot(HomeViews.STATS),
                    ModuleSlot(HomeViews.STATS, objectRef = "again"),
                    ModuleSlot(HomeViews.RECENTS),
                )
            ),
        )
        home(WIDE_WIDTHS.last(), repeated) {
            assertOrder(HomePageTags.STATS_STRIP, HomePageTags.RECENTS)
        }
    }

    @Test
    fun anEmptiedLaneLeavesOnlyTheFrame() {
        home(WIDE_WIDTHS.last(), withHomeLane(emptyList())) {
            onNodeWithTag(HomePageTags.PAGE).assertExists()
            assertAbsent(HomePageTags.COMPOSER, HomePageTags.RECENTS, HomePageTags.STATS_STRIP, fleetRow)
        }
        home(COMPACT_WIDTHS.first(), withHomeLane(emptyList())) {
            onNodeWithTag(HomePageTags.PAGE).assertExists()
            assertAbsent(HomePageTags.COMPOSER, HomePageTags.RECENTS, HomePageTags.STATS_STRIP)
        }
    }

    private val navigation = HomePageNavigation(onSubmitPrompt = {}, onOpenConversation = {}, onOpenAgent = {}, onOpenShortcut = {})

    private object NoActions : HomePageActions {
        override fun loadIfStale() = Unit
        override fun refresh() = Unit
        override fun updateSearchQuery(query: String) = Unit
        override fun clearSearch() = Unit
        override fun selectSort(key: FleetSortKey) = Unit
        override fun reorderPins(keys: List<String>) = Unit
        override fun setShortcutPinned(shortcut: HomeShortcut, pinned: Boolean) = Unit
        override fun setAgentPinned(agentId: String, pinned: Boolean) = Unit
    }

    private companion object {
        val COMPACT_WIDTHS = listOf(360, 700)
        val WIDE_WIDTHS = listOf(760, 1200)
        const val HEIGHT = 2400
    }
}
