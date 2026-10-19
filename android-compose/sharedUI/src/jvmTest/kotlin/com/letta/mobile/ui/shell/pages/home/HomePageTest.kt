@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.home.FleetAgentStat
import com.letta.mobile.data.home.FleetOverview
import com.letta.mobile.data.home.FleetRecentConversation
import com.letta.mobile.data.home.FleetSortKey
import com.letta.mobile.data.home.FleetSummary
import com.letta.mobile.data.home.HomeAgentRef
import com.letta.mobile.data.home.HomePageActions
import com.letta.mobile.data.home.HomePageReducer
import com.letta.mobile.data.home.HomePageState
import com.letta.mobile.data.home.HomePinnedItem
import com.letta.mobile.data.home.HomeSearchCatalog
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.home.HomeStats
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.ui.components.LettaRefreshTags
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.mascot.AgentAvatarTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared Home page on a wide desktop window and a phone (letta-mobile-c3np7.3.11), including
 * the Letta Code mod seam (letta-mobile-2don7): a recognised A2UI document replaces the native page
 * and an unrenderable one falls back to it, never to a blank page.
 */
class HomePageTest {
    private val scout = FleetAgentStat(
        agentId = "a-1",
        name = "Scout",
        model = "openai/gpt-5",
        conversationCount = 3,
        lastActivity = null,
        running = true,
        activityByDay = emptyList(),
    )
    private val recent = FleetRecentConversation(
        conversationId = "c-1",
        agentId = "a-1",
        agentName = "Scout",
        title = "Trip planning",
        preview = "Booked the train",
        updatedAt = null,
        updatedAtLabel = "Queued",
    )
    private val fleet = FleetOverview(
        summary = FleetSummary(totalAgents = 1, totalConversations = 3, runningNow = 1),
        agents = listOf(scout),
        recent = listOf(recent),
    )

    private val toolsPin = HomePinnedItem.Shortcut(HomeShortcut.TOOLS)

    private fun baseState(
        pins: List<String> = listOf("shortcut:TOOLS"),
        available: Set<HomeShortcut> = setOf(HomeShortcut.TOOLS, HomeShortcut.SCHEDULES),
    ): HomePageState {
        val withFleet = HomePageReducer.withFleet(HomePageState(availableShortcuts = available), fleet)
        val withPins = HomePageReducer.withPins(withFleet, pins)
        return HomePageReducer.withStats(withPins, HomeStats(loading = false, toolCount = 12))
    }

    private val actions = RecordingActions()
    private val navigation = RecordingNavigation()

    private fun ComposeUiTest.show(state: HomePageState, width: Dp = WIDE, document: UiGeneratedComponent? = null) {
        show(state, HomePageOptions(document = document), width)
    }

    private fun ComposeUiTest.show(
        state: HomePageState,
        options: HomePageOptions,
        width: Dp = WIDE,
        host: LettaHost = LettaHost.Touch,
    ) {
        setContent {
            MaterialTheme {
                ProvideLettaHost(host) {
                    Box(Modifier.width(width).height(PAGE_HEIGHT)) {
                        HomePage(state = state, callbacks = HomePageCallbacks(actions, navigation.navigation), options = options)
                    }
                }
            }
        }
        waitForIdle()
        assertEquals(listOf("refresh"), actions.events, "entering Home refreshes it once")
        actions.events.clear()
    }

    /** letta-mobile-ckmub.3.22: a pull reloads the page on touch, and there is no refresh button. */
    @Test
    fun onTouchAPullRefreshesTheDashboard() = runComposeUiTest {
        show(baseState(), HomePageOptions(), width = COMPACT)
        onNodeWithTag(HomePageTags.REFRESH).assertDoesNotExist()
        onNodeWithTag(LettaRefreshTags.PULL).performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 600) }
        waitForIdle()
        assertEquals(listOf("refresh"), actions.events)
    }

    /** Automatic entry refresh replaces the persistent pointer-host refresh action. */
    @Test
    fun onPointerHomeHasNoRefreshButton() = runComposeUiTest {
        show(baseState(), HomePageOptions(), host = LettaHost.Pointer)
        onNodeWithTag(LettaRefreshTags.PULL).assertDoesNotExist()
        onNodeWithTag(HomePageTags.REFRESH).assertDoesNotExist()
        onNodeWithText("Retry").assertDoesNotExist()
        assertEquals(emptyList(), actions.events)
    }

    @Test
    fun returningHomeRefreshesButRecompositionDoesNot() = runComposeUiTest {
        val visible = mutableStateOf(true)
        val state = mutableStateOf(baseState())
        setContent {
            MaterialTheme {
                if (visible.value) HomePage(state.value, HomePageCallbacks(actions, navigation.navigation))
            }
        }
        waitForIdle()
        assertEquals(listOf("refresh"), actions.events)
        runOnIdle { state.value = state.value.copy(stats = HomeStats(loading = false, toolCount = 14)) }
        waitForIdle()
        assertEquals(listOf("refresh"), actions.events)
        runOnIdle { visible.value = false }
        waitForIdle()
        runOnIdle { visible.value = true }
        waitForIdle()
        assertEquals(listOf("refresh", "refresh"), actions.events)
    }

    @Test
    fun theWidePageShowsTheFleetDashboardAndPins() = runComposeUiTest {
        show(baseState())
        onNodeWithText("Home").assertExists()
        onNodeWithText("1 agents · 3 conversations").assertExists()
        onNodeWithTag(HomePageTags.pin(toolsPin)).assertExists()
        assertEquals(2, onAllNodesWithText("12").fetchSemanticsNodes().size, "the pinned Tools tile and the Tools stat tile")
        onNodeWithTag(HomePageTags.STATS).assertExists()
        onNodeWithTag(HomePageTags.recent(recent)).assertExists()
        onNodeWithText("Booked the train").assertExists()
    }

    @Test
    fun rowsAndTilesNavigate() = runComposeUiTest {
        show(baseState())
        onNodeWithTag(HomePageTags.pin(toolsPin)).performClick()
        onNodeWithTag(HomePageTags.recent(recent)).performClick()
        scrollTo(HomePageTags.agentRow(scout))
        onNodeWithTag(HomePageTags.agentRow(scout)).performClick()
        assertEquals(listOf("shortcut:TOOLS", "conversation:c-1", "agent:a-1"), navigation.events)
    }

    @Test
    fun theComposerSendsTrimmedText() = runComposeUiTest {
        show(baseState())
        onNodeWithTag(HomePageTags.COMPOSER).performTextInput("  plan my week  ")
        onNodeWithTag(HomePageTags.SEND).performClick()
        assertEquals(listOf("prompt:plan my week"), navigation.events)
    }

    @Test
    fun editingPinsUnpinsAndAddsShortcuts() = runComposeUiTest {
        show(baseState())
        onNodeWithTag(HomePageTags.EDIT_PINS).performClick()
        onNodeWithTag(HomePageTags.unpin(toolsPin)).performClick()
        onNodeWithTag(HomePageTags.ADD_PIN).performClick()
        onNodeWithTag(HomePageTags.addShortcut(HomeShortcut.SCHEDULES)).performClick()
        assertEquals(listOf("pin:TOOLS=false", "pin:SCHEDULES=true"), actions.events)
    }

    @Test
    fun theFleetTableSortsAndPinsAgents() = runComposeUiTest {
        show(baseState())
        scrollTo(HomePageTags.pinAgent(scout))
        onNodeWithTag(HomePageTags.sort(FleetSortKey.Model)).performClick()
        onNodeWithTag(HomePageTags.pinAgent(scout)).performClick()
        assertEquals(listOf("sort:Model", "agent:a-1=true"), actions.events)
    }

    /** letta-mobile-zxk2n.34: running work, recents, agents, then the counters as one strip; composer docked below. */
    @Test
    fun aPhoneLeadsWithRunningWorkAndRecentsThenAgentsThenTheStatsStrip() = runComposeUiTest {
        show(baseState(available = setOf(HomeShortcut.TOOLS, HomeShortcut.CONVERSATIONS, HomeShortcut.AGENTS)), width = COMPACT)
        val order = listOf(
            HomePageTags.NOW_STRIP,
            HomePageTags.RECENTS,
            HomePageTags.recent(recent),
            HomePageTags.AGENT_ROW,
            HomePageTags.STATS_STRIP,
            HomePageTags.COMPOSER,
        ).map { onNodeWithTag(it).getUnclippedBoundsInRoot().top }
        assertEquals(order.sorted(), order, "now, recents, agents, stats, then the docked composer")
        onNodeWithTag(HomePageTags.STATS).assertDoesNotExist()
        onNodeWithTag(HomePageTags.sort(FleetSortKey.Conversations)).assertDoesNotExist()
        onNodeWithText("1 running · Scout").assertExists()

        onNodeWithTag(HomePageTags.seeAll(HomeShortcut.CONVERSATIONS)).performClick()
        onNodeWithTag(HomePageTags.seeAll(HomeShortcut.AGENTS)).performClick()
        onNodeWithTag(HomePageTags.agentAvatar("a-1")).performClick()
        onNodeWithTag(HomePageTags.NOW_STRIP).performClick()
        assertEquals(listOf("shortcut:CONVERSATIONS", "shortcut:AGENTS", "agent:a-1", "agent:a-1"), navigation.events)
    }

    @Test
    fun nothingRunningHidesTheNowStrip() = runComposeUiTest {
        val idle = fleet.copy(summary = fleet.summary.copy(runningNow = 0), agents = listOf(scout.copy(running = false)))
        show(HomePageReducer.withFleet(baseState(), idle), width = COMPACT)
        onNodeWithTag(HomePageTags.NOW_STRIP).assertDoesNotExist()
        onNodeWithTag(HomePageTags.recent(recent)).assertExists()
    }

    @Test
    fun theStatsStripIsOneRowAtPhoneWidth() = runComposeUiTest {
        show(baseState(), width = PHONE)
        val labels = listOf("Agents", "Conversations", "Active today", "Running now", "Tools")
        val tops = labels.map { onNodeWithTag(HomePageTags.stat(it)).getUnclippedBoundsInRoot().top }
        assertEquals(1, tops.distinct().size, "every figure on one line: $tops")
        val strip = onNodeWithTag(HomePageTags.STATS_STRIP).getUnclippedBoundsInRoot()
        assertTrue(strip.bottom - strip.top < STRIP_MAX_HEIGHT, "the strip stays one compact row: ${strip.bottom - strip.top}")
        onNodeWithTag(HomePageTags.STATS_TREND).assertDoesNotExist()
    }

    @Test
    fun theStatsStripOpensPagesExpandsAndCustomizesPins() = runComposeUiTest {
        show(baseState(), width = PHONE)
        // Tools sits past the phone's edge: the strip scrolls sideways instead of wrapping.
        onNodeWithTag(HomePageTags.stat("Tools")).performScrollTo().performClick()
        onNodeWithTag(HomePageTags.stat("Agents")).performScrollTo().performClick()
        assertEquals(listOf("shortcut:TOOLS"), navigation.events, "only figures whose page the host offers link")

        onNodeWithTag(HomePageTags.STATS_EXPAND).performClick()
        onNodeWithTag(HomePageTags.STATS_TREND).assertExists()

        onNodeWithTag(HomePageTags.PINNED_GRID).assertDoesNotExist()
        onNodeWithTag(HomePageTags.STATS_MENU).performClick()
        onNodeWithTag(HomePageTags.CUSTOMIZE).performClick()
        onNodeWithTag(HomePageTags.unpin(toolsPin)).performClick()
        assertEquals(listOf("pin:TOOLS=false"), actions.events)
    }

    @Test
    fun typingSearchesAndHitsNavigate() = runComposeUiTest {
        val catalog = HomeSearchCatalog(agents = listOf(Agent(id = AgentId("a-1"), name = "Planner")))
        val searching = HomePageReducer.withQuery(HomePageReducer.withCatalog(baseState(), catalog), "plan", awaitMessages = true)
        val message = ParsedSearchMessage("m-1", "a-1", "assistant", "the plan", null, "c-9")
        val withHits = HomePageReducer.withMessages(searching, "plan", listOf(message))
        show(withHits)

        onNodeWithTag(HomePageTags.SEARCH_RESULTS).assertExists()
        onNodeWithTag(HomePageTags.COMPOSER).assertDoesNotExist()
        onNodeWithTag(HomePageTags.searchHit("a-1")).performClick()
        onNodeWithTag(HomePageTags.searchHit("m-1")).performClick()
        onNodeWithTag(HomePageTags.SEARCH).performTextInput("!")
        assertEquals(listOf("agent:a-1", "message:c-9"), navigation.events)
        assertEquals(listOf("query:!plan"), actions.events, "typed text lands at the cursor")
    }

    @Test
    fun aBackendErrorShowsABannerEvenWithoutAHeader() = runComposeUiTest {
        val failing = HomePageReducer.withStats(baseState(), HomeStats(loading = false, error = "Backend unreachable"))
        setContent {
            MaterialTheme {
                Box(Modifier.width(COMPACT).height(PAGE_HEIGHT)) {
                    HomePage(
                        state = failing,
                        callbacks = HomePageCallbacks(actions, navigation.navigation),
                        options = HomePageOptions(showTitle = false, showSearch = false, touch = true),
                    )
                }
            }
        }
        onNodeWithTag(HomePageTags.ERROR).assertExists()
        onNodeWithText("Backend unreachable").assertExists()
        onNodeWithTag(HomePageTags.SEARCH).assertDoesNotExist()
        onNodeWithText("Retry").performClick()
        assertEquals(listOf("refresh", "refresh"), actions.events, "entry and error Retry use the shared action")
    }

    /** Desktop: the header's field searches Home (letta-mobile-xuhmy), so the page draws no field of its own. */
    @Test
    fun aHostSearchFieldDrivesTheWidePageWithoutASecondField() = runComposeUiTest {
        val catalog = HomeSearchCatalog(agents = listOf(Agent(id = AgentId("a-1"), name = "Planner")))
        val searching = HomePageReducer.withQuery(HomePageReducer.withCatalog(baseState(), catalog), "plan", awaitMessages = false)
        show(searching, HomePageOptions(showSearch = false))

        onNodeWithText("Home").assertExists()
        onNodeWithTag(HomePageTags.SEARCH).assertDoesNotExist()
        onNodeWithTag(HomePageTags.SEARCH_RESULTS).assertExists()
        onNodeWithTag(HomePageTags.searchHit("a-1")).assertExists()
    }

    /** letta-mobile-zxk2n.28: an agent on Home is pictured by AgentAvatar, never the generic robot icon. */
    @Test
    fun agentSearchHitsArePicturedByTheirAgentAvatar() = runComposeUiTest {
        val catalog = HomeSearchCatalog(agents = listOf(Agent(id = AgentId("a-1"), name = "Planner")))
        show(HomePageReducer.withQuery(HomePageReducer.withCatalog(baseState(), catalog), "plan", awaitMessages = false))
        avatarInside(HomePageTags.searchHit("a-1")).assertExists()
        // No mascot is registered in tests, so the avatar is the agent's initial tile.
        onNode(hasText("P") and hasAnyAncestor(hasTestTag(HomePageTags.searchHit("a-1"))), useUnmergedTree = true).assertExists()
    }

    @Test
    fun fleetRecentAndPinnedAgentRowsUseTheAgentAvatar() = runComposeUiTest {
        val pinned = HomePinnedItem.Agent(HomeAgentRef("a-1", "Scout"))
        val state = HomePageReducer.withPersistedAgentNames(baseState(pins = listOf(HomePinnedItem.agentKey("a-1"))), mapOf("a-1" to "Scout"))
        show(state)
        avatarInside(HomePageTags.pin(pinned)).assertExists()
        avatarInside(HomePageTags.recent(recent)).assertExists()
        scrollTo(HomePageTags.agentRow(scout))
        avatarInside(HomePageTags.agentRow(scout)).assertExists()
    }

    /** The section counts are the query's matches, not the catalog's size (letta-mobile-06whj). */
    @Test
    fun sectionCountsReflectTheQuery() = runComposeUiTest {
        val catalog = HomeSearchCatalog(
            agents = listOf(Agent(id = AgentId("a-1"), name = "Mercury"), Agent(id = AgentId("a-2"), name = "Scout")),
            blocks = listOf(
                Block(id = BlockId("b-1"), label = "merge notes", value = "x"),
                Block(id = BlockId("b-2"), label = "persona", value = "helpful"),
                Block(id = BlockId("b-3"), label = "human", value = "likes summer"),
            ),
        )
        show(HomePageReducer.withQuery(HomePageReducer.withCatalog(baseState(), catalog), "mer", awaitMessages = false))
        onNodeWithText("Agents").assertExists()
        onNodeWithText("(1)").assertExists()
        onNodeWithText("Blocks").assertExists()
        onNodeWithText("(2)").assertExists()
        onNodeWithTag(HomePageTags.searchHit("b-2")).assertDoesNotExist()
        onNodeWithTag(HomePageTags.searchHit("a-2")).assertDoesNotExist()
    }

    private fun ComposeUiTest.avatarInside(tag: String) =
        onNode(hasTestTag(AgentAvatarTestTag) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    @Test
    fun aSearchWithNothingFoundSaysSo() = runComposeUiTest {
        show(HomePageReducer.withQuery(baseState(), "zzz", awaitMessages = false))
        onNodeWithTag(HomePageTags.SEARCH_EMPTY).assertExists()
        onNodeWithText("No results found").assertExists()
    }

    @Test
    fun aRecognizedDocumentReplacesTheNativePage() = runComposeUiTest {
        val document = UiGeneratedComponent(
            name = "Text",
            propsJson = """{"text":"Mod-authored home page"}""",
            fallbackText = "Mod-authored home page (fallback)",
        )
        show(baseState(), document = document)
        onNodeWithText("Mod-authored home page").assertExists()
        onNodeWithText("Home").assertDoesNotExist()
    }

    @Test
    fun aDocumentActionReachesTheHost() = runComposeUiTest {
        val document = UiGeneratedComponent(name = "Button", propsJson = """{"label":"Open issue","action":{"name":"issue.open"}}""")
        show(baseState(), document = document)
        onNodeWithText("Open issue").performClick()
        assertEquals(listOf("a2ui:issue.open"), navigation.events)
    }

    @Test
    fun anUnrenderableDocumentFallsBackToTheNativePage() = runComposeUiTest {
        val document = UiGeneratedComponent(name = "NotACatalogWidget", propsJson = "not valid json", fallbackText = "irrelevant")
        show(baseState(), document = document)
        onNodeWithText("Home").assertExists()
    }

    private fun ComposeUiTest.scrollTo(tag: String) {
        onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasTestTag(tag))
    }

    private class RecordingActions : HomePageActions {
        val events = mutableListOf<String>()

        override fun refresh() {
            events += "refresh"
        }

        override fun updateSearchQuery(query: String) {
            events += "query:$query"
        }

        override fun clearSearch() {
            events += "clear"
        }

        override fun selectSort(key: FleetSortKey) {
            events += "sort:${key.label}"
        }

        override fun reorderPins(keys: List<String>) {
            events += "order:${keys.joinToString()}"
        }

        override fun setShortcutPinned(shortcut: HomeShortcut, pinned: Boolean) {
            events += "pin:${shortcut.name}=$pinned"
        }

        override fun setAgentPinned(agentId: String, pinned: Boolean) {
            events += "agent:$agentId=$pinned"
        }
    }

    private class RecordingNavigation {
        val events = mutableListOf<String>()
        val navigation = HomePageNavigation(
            onSubmitPrompt = { events += "prompt:$it" },
            onOpenConversation = { events += "conversation:${it.conversationId}" },
            onOpenAgent = { events += "agent:$it" },
            onOpenShortcut = { events += HomePinnedItem.shortcutKey(it) },
            onOpenMessage = { events += "message:${it.conversationId}" },
            onA2uiAction = { events += "a2ui:${it.name}" },
        )
    }

    private companion object {
        val WIDE = 1100.dp
        val COMPACT = 380.dp
        val PHONE = 412.dp
        val PAGE_HEIGHT = 900.dp
        val STRIP_MAX_HEIGHT = 72.dp
    }
}
