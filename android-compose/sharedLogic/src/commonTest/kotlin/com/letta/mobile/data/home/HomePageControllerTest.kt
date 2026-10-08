package com.letta.mobile.data.home

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ParsedSearchMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomePageControllerTest {
    private val scout = Agent(id = AgentId("a-1"), name = "Scout")
    private val planner = Agent(id = AgentId("a-2"), name = "Planner")
    private val hit = ParsedSearchMessage(messageId = "m-1", agentId = "a-1", role = "assistant", content = "the plan", date = null, conversationId = "c-1")

    private fun stat(id: String, name: String, count: Int) = FleetAgentStat(
        agentId = id,
        name = name,
        model = null,
        conversationCount = count,
        lastActivity = null,
        running = false,
        activityByDay = emptyList(),
    )

    private fun TestScope.controller(
        source: FakeSource = FakeSource(),
        pins: FakePins = FakePins(),
        config: HomePageConfig = HomePageConfig(),
    ): HomePageController = HomePageController(source, pins, backgroundScope, config).also {
        it.start()
        runCurrent()
    }

    /** Lets the debounced message search (a background-scope job) run to completion. */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_MS)
        runCurrent()
    }

    @Test
    fun startFollowsTheCatalogStatsAndPins() = runTest {
        val source = FakeSource()
        val pins = FakePins(listOf("shortcut:TOOLS", "agent:a-1"))
        val home = controller(source, pins)
        assertTrue(source.started)

        source.catalogFlow.value = HomeSearchCatalog(agents = listOf(scout), agentsSettled = true)
        source.statsFlow.value = HomeStats(loading = false, toolCount = 7)
        runCurrent()

        val state = home.state.value
        assertTrue(state.pinsLoaded)
        assertEquals(7, state.stats.toolCount)
        assertEquals(
            listOf(HomePinnedItem.Shortcut(HomeShortcut.TOOLS), HomePinnedItem.Agent(HomeAgentRef("a-1", "Scout"))),
            state.pinnedItems,
        )
        assertEquals("7", state.shortcutInfo(HomeShortcut.TOOLS))
    }

    @Test
    fun theHostFleetDrivesTheSortedTableAndPinnedAgentNames() = runTest {
        val home = controller(pins = FakePins(listOf("agent:a-9")))
        home.updateFleet(FleetOverview(agents = listOf(stat("a-9", "Zed", 1), stat("a-8", "Amy", 5))))

        assertEquals(listOf(HomePinnedItem.Agent(HomeAgentRef("a-9", "Zed"))), home.state.value.pinnedItems)
        assertEquals(listOf("Amy", "Zed"), home.state.value.sortedAgents.map { it.name }, "default sort is last activity, then name")

        home.selectSort(FleetSortKey.Conversations)
        assertEquals(listOf("Amy", "Zed"), home.state.value.sortedAgents.map { it.name })
        home.selectSort(FleetSortKey.Conversations)
        assertEquals(listOf("Zed", "Amy"), home.state.value.sortedAgents.map { it.name })
    }

    @Test
    fun searchShowsLocalMatchesAtOnceAndMessagesAfterTheDebounce() = runTest {
        val source = FakeSource(messages = listOf(hit))
        source.catalogFlow.value = HomeSearchCatalog(agents = listOf(scout, planner))
        val home = controller(source)

        home.updateSearchQuery("plan")
        val pending = home.state.value.search
        assertEquals(listOf("Planner"), pending.agents.map { it.name })
        assertTrue(pending.searchingMessages)
        assertTrue(pending.messages.isEmpty())

        advanceTimeBy(100)
        runCurrent()
        assertTrue(source.queries.isEmpty(), "still inside the debounce window")

        settle()
        assertEquals(listOf("plan"), source.queries)
        assertEquals(listOf(hit), home.state.value.search.messages)
        assertFalse(home.state.value.search.searchingMessages)
    }

    @Test
    fun aNewerQueryCancelsTheOlderMessageSearch() = runTest {
        val source = FakeSource(messages = listOf(hit))
        val home = controller(source)

        home.updateSearchQuery("pl")
        home.updateSearchQuery("plan")
        settle()
        assertEquals(listOf("plan"), source.queries)
    }

    @Test
    fun aFailedMessageSearchKeepsTheLocalResults() = runTest {
        val source = FakeSource(fail = true)
        source.catalogFlow.value = HomeSearchCatalog(agents = listOf(planner))
        val home = controller(source)

        home.updateSearchQuery("plan")
        settle()
        val search = home.state.value.search
        assertEquals(listOf("Planner"), search.agents.map { it.name })
        assertFalse(search.searchingMessages)
    }

    @Test
    fun withoutMessageSearchNothingIsPendingAndClearingResets() = runTest {
        val source = FakeSource(supportsMessages = false)
        val home = controller(source)

        home.updateSearchQuery("plan")
        assertFalse(home.state.value.search.searchingMessages)
        settle()
        assertTrue(source.queries.isEmpty())

        home.clearSearch()
        assertFalse(home.state.value.search.isActive)
    }

    @Test
    fun aNewCatalogRerunsTheActiveSearch() = runTest {
        val source = FakeSource(supportsMessages = false)
        val home = controller(source)
        home.updateSearchQuery("plan")
        assertTrue(home.state.value.search.agents.isEmpty())

        source.catalogFlow.value = HomeSearchCatalog(agents = listOf(planner))
        runCurrent()
        assertEquals(listOf("Planner"), home.state.value.search.agents.map { it.name })
    }

    @Test
    fun pinActionsWriteThroughTheStore() = runTest {
        val pins = FakePins(listOf("shortcut:TOOLS"))
        val home = controller(pins = pins)

        home.setShortcutPinned(HomeShortcut.SCHEDULES, pinned = true)
        home.setAgentPinned("a-1", pinned = true)
        runCurrent()
        assertEquals(listOf("shortcut:TOOLS", "shortcut:SCHEDULES", "agent:a-1"), home.state.value.pinKeys)
        assertTrue(home.state.value.isAgentPinned("a-1"))

        home.reorderPins(listOf("agent:a-1", "shortcut:TOOLS", "shortcut:SCHEDULES"))
        home.setShortcutPinned(HomeShortcut.TOOLS, pinned = false)
        runCurrent()
        assertEquals(listOf("agent:a-1", "shortcut:SCHEDULES"), home.state.value.pinKeys)
    }

    @Test
    fun onlyAvailableUnpinnedShortcutsAreOfferedForPinning() = runTest {
        val config = HomePageConfig(availableShortcuts = setOf(HomeShortcut.TOOLS, HomeShortcut.SCHEDULES, HomeShortcut.SETTINGS))
        val home = controller(pins = FakePins(listOf("shortcut:SCHEDULES", "shortcut:RUNS")), config = config)

        assertEquals(listOf(HomeShortcut.TOOLS, HomeShortcut.SETTINGS), home.state.value.unpinnedShortcuts)
        assertEquals(listOf(HomePinnedItem.Shortcut(HomeShortcut.SCHEDULES)), home.state.value.pinnedItems, "RUNS stays stored but hidden")
    }

    @Test
    fun shortcutInfoShowsLiveFiguresAndLoadingDashes() = runTest {
        val home = controller()
        home.updateFleet(FleetOverview(summary = FleetSummary(totalAgents = 3, totalConversations = 50, conversationsTruncated = true)))
        home.updateFavorite(HomeAgentRef("a-1", "Scout"))
        val loading = home.state.value
        assertEquals("50+", loading.shortcutInfo(HomeShortcut.CONVERSATIONS))
        assertEquals("3", loading.shortcutInfo(HomeShortcut.AGENTS))
        assertEquals("—", loading.shortcutInfo(HomeShortcut.TOOLS))
        assertEquals("Scout", loading.shortcutInfo(HomeShortcut.FAVORITE_AGENT))
        assertEquals("Scheduled tasks", loading.shortcutInfo(HomeShortcut.SCHEDULES))

        val settled = HomePageReducer.withStats(loading, HomeStats(loading = false, usage = HomeUsageSummary(12_500, 520, 4)))
        assertNull(settled.shortcutInfo(HomeShortcut.BLOCKS), "a figure the backend lacks is blank, not a dash")
        assertEquals("12,500 tokens", settled.shortcutInfo(HomeShortcut.USAGE))
    }

    @Test
    fun closeStopsFollowingAndClosesTheSource() = runTest {
        val source = FakeSource()
        val home = controller(source)
        home.close()
        assertTrue(source.closed)
        source.statsFlow.value = HomeStats(loading = false, toolCount = 99)
        runCurrent()
        assertNull(home.state.value.stats.toolCount)
    }

    @Test
    fun refreshAsksTheSource() = runTest {
        val source = FakeSource()
        controller(source).refresh()
        assertEquals(1, source.refreshes)
    }

    private companion object {
        const val SETTLE_MS = 1_000L
    }

    private class FakeSource(
        private val messages: List<ParsedSearchMessage> = emptyList(),
        private val supportsMessages: Boolean = true,
        private val fail: Boolean = false,
    ) : HomePageSource {
        val catalogFlow = MutableStateFlow(HomeSearchCatalog())
        val statsFlow = MutableStateFlow(HomeStats())
        val queries = mutableListOf<String>()
        var started = false
        var closed = false
        var refreshes = 0

        override val catalog: StateFlow<HomeSearchCatalog> = catalogFlow
        override val stats: StateFlow<HomeStats> = statsFlow
        override val supportsMessageSearch: Boolean get() = supportsMessages

        override fun start() {
            started = true
        }

        override fun refresh() {
            refreshes++
        }

        override suspend fun searchMessages(query: String): List<ParsedSearchMessage> {
            queries += query
            if (fail) error("search unavailable")
            return messages
        }

        override fun close() {
            closed = true
        }
    }

    private class FakePins(initial: List<String> = emptyList()) : HomePinStore {
        private val keys = MutableStateFlow(initial)
        override val pinnedKeys: StateFlow<List<String>> = keys

        override fun setOrder(keys: List<String>) {
            this.keys.value = keys
        }

        override fun setPinned(key: String, pinned: Boolean) {
            keys.value = if (pinned) (keys.value + key).distinct() else keys.value - key
        }
    }
}
