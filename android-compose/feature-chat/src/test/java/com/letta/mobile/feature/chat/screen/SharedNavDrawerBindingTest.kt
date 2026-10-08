package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.FeatureFlag
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.MainDispatcherRule
import com.letta.mobile.ui.shell.ShellNavDrawerInput
import com.letta.mobile.ui.shell.ShellNavDrawerMapping
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag

/** The Android binding of the shared navigation drawer (letta-mobile-c3np7.5.5). */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class SharedNavDrawerBindingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val settings = FakeSettingsRepository()
    private val canvasStore: CanvasDocumentStore = mockk(relaxed = true)
    private val conversations: IConversationRepository = mockk(relaxed = true)
    private val fleetConversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val allConversations: IAllConversationsRepository = mockk(relaxed = true) {
        every { this@mockk.conversations } returns fleetConversations
    }

    private val now = Instant.parse("2026-10-17T12:00:00Z")

    private fun viewModel() = SharedNavDrawerViewModel(settings, canvasStore, conversations, allConversations)

    @Test
    fun followsTheSettingsFlag() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.enabled.collect {} }
        advanceUntilIdle()
        assertFalse(vm.enabled.value)
        settings.setFeatureFlag(FeatureFlag.SharedNavDrawer, true)
        advanceUntilIdle()
        assertTrue(vm.enabled.value)
    }

    @Test
    fun refreshReadsTheCanvasLibraryAndKeepsItOnFailure() = runTest(mainDispatcherRule.dispatcher) {
        val board = CanvasDocument(id = CanvasId("k1"), title = "Board")
        coEvery { canvasStore.listAll() } returns listOf(board)
        val vm = viewModel()
        vm.refreshCanvases()
        advanceUntilIdle()
        assertEquals(listOf(board), vm.canvases.value)

        coEvery { canvasStore.listAll() } throws IllegalStateException("offline")
        vm.refreshCanvases()
        advanceUntilIdle()
        assertEquals(listOf(board), vm.canvases.value)
    }

    @Test
    fun rowActionsReachTheConversationRepository() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.setArchiveFilter(ShellArchiveFilter.Archived)
        vm.setConversationArchived("c1", "agent-1", archived = true)
        vm.deleteConversation("c2", "agent-1")
        advanceUntilIdle()
        assertEquals(ShellArchiveFilter.Archived, vm.archiveFilter.value)
        coVerify { conversations.setConversationArchived("c1", "agent-1", true) }
        coVerify { conversations.deleteConversation("c2", "agent-1") }
    }

    @Test
    fun railPinsReachTheSettingsRepository() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.pinnedAgentIds.collect {} }
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.pinnedAgentIds.value)
        vm.setAgentPinned("agent-2", pinned = true)
        advanceUntilIdle()
        assertEquals(setOf("agent-2"), vm.pinnedAgentIds.value)
        vm.setAgentPinned("agent-2", pinned = false)
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.pinnedAgentIds.value)
    }

    @Test
    fun theRailIsTheDesktopsRecentsStripNotTheWholeRoster() {
        // Twelve agents; a1..a10 active an hour apart (a1 newest), a11 stale, a12 never ran.
        val agents = (1..12).map { n ->
            val updatedAt = when {
                n <= 10 -> "2026-10-17T${(12 - n).toString().padStart(2, '0')}:00:00Z"
                n == 11 -> "2026-08-01T12:00:00Z"
                else -> null
            }
            Agent(id = AgentId("a$n"), name = "Agent $n", updatedAt = updatedAt)
        }
        val input = ShellNavDrawerInput(agent = ShellPanelAgent(name = "Agent 1", agentId = "a1"))
            .withRoster(SharedDrawerRoster(agents = agents, favoriteAgentId = "a12", pinnedAgentIds = setOf("a11")))
        val state = ShellNavDrawerMapping.state(input, now)
        // Pins and the favourite first, then the eight newest; the focused agent heads the panel, not the rail.
        assertEquals(
            listOf("Agent 11", "Agent 12") + (2..9).map { "Agent $it" },
            state.rail.entries.map { it.name },
        )
        // Only a10 fell off the strip; the rail's "All agents" reaches it through the agent switcher.
        assertEquals(1, state.rail.hiddenAgentCount)
    }

    @Test
    fun aRosterWithNoTimestampsStillFillsTheRail() {
        // The device report: 139 agents and no activity on their records gave the rail one orb and "99+".
        val agents = (1..139).map { Agent(id = AgentId("a$it"), name = "Agent $it") }
        val input = ShellNavDrawerInput(agent = ShellPanelAgent(name = "Agent 1", agentId = "a1"))
            .withRoster(SharedDrawerRoster(agents = agents, favoriteAgentId = "a50"))
        val rail = ShellNavDrawerMapping.state(input, now).rail
        assertEquals(listOf("Agent 50") + (2..9).map { "Agent $it" }, rail.entries.map { it.name })
        assertEquals(138 - 9, rail.hiddenAgentCount)
    }

    @Test
    fun conversationActivityOrdersTheRailAheadOfTheAgentRecords() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.agentActivity.collect {} }
        fleetConversations.value = listOf(
            conversation("c1", "a7", lastMessageAt = "2026-10-17T11:00:00Z"),
            conversation("c2", "a5", lastMessageAt = "2026-10-17T10:00:00Z"),
            conversation("c3", "a7", lastMessageAt = "2026-10-10T10:00:00Z"),
        )
        advanceUntilIdle()
        assertEquals(
            mapOf("a7" to Instant.parse("2026-10-17T11:00:00Z"), "a5" to Instant.parse("2026-10-17T10:00:00Z")),
            vm.agentActivity.value,
        )
        // a3's record is newer than a5's conversation but older than a7's.
        val agents = (1..10).map { n ->
            Agent(id = AgentId("a$n"), name = "Agent $n", updatedAt = "2026-10-17T10:30:00Z".takeIf { n == 3 })
        }
        val input = ShellNavDrawerInput(agent = ShellPanelAgent(name = "Agent 1", agentId = "a1"))
            .withRoster(SharedDrawerRoster(agents = agents, conversationActivity = vm.agentActivity.value))
        val rail = ShellNavDrawerMapping.state(input, now).rail
        assertEquals(listOf("Agent 7", "Agent 3", "Agent 5", "Agent 2", "Agent 4", "Agent 6", "Agent 8", "Agent 9"), rail.entries.map { it.name })
        assertEquals(1, rail.hiddenAgentCount)
    }

    @Test
    fun openingTheDrawerRefreshesTheFleetConversationsAndSurvivesFailure() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { allConversations.refreshIfStale(any()) } throws IllegalStateException("offline")
        val vm = viewModel()
        backgroundScope.launch { vm.agentActivity.collect {} }
        vm.refreshAgentActivity()
        advanceUntilIdle()
        coVerify { allConversations.refreshIfStale(any()) }
        // The failed refresh leaves the rail's activity as it was.
        assertEquals(emptyMap<String, Instant>(), vm.agentActivity.value)
    }

    private fun conversation(id: String, agentId: String, lastMessageAt: String) =
        Conversation(id = ConversationId(id), agentId = AgentId(agentId), lastMessageAt = lastMessageAt)
    @Test
    fun sectionsNavigateToTheAndroidPages() {
        val calls = mutableListOf<String>()
        val navigation = AgentScaffoldNavigationCallbacks(
            onNavigateBack = {},
            onNavigateToSettings = {},
            onNavigateToMemory = { calls += "memory:$it" },
            onNavigateToSchedules = { calls += "schedules:$it" },
            onNavigateToTools = { calls += "tools" },
            onNavigateToConversationList = { calls += "conversations" },
            onNavigateToChannels = { calls += "channels" },
        )
        LensDestination.entries.forEach { openSection(navigation, "agent-1", it) }
        assertEquals(listOf("memory:agent-1", "schedules:agent-1", "channels", "tools", "conversations"), calls)
        assertEquals(emptySet<LensDestination>(), androidHiddenDrawerSections(navigation))
    }

    @Test
    fun channelsStaysHiddenWithoutAChannelsPage() {
        val navigation = AgentScaffoldNavigationCallbacks(onNavigateBack = {}, onNavigateToSettings = {})
        assertEquals(setOf(LensDestination.Channels), androidHiddenDrawerSections(navigation))
    }
}
