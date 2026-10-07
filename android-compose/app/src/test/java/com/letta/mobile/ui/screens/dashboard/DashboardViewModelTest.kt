package com.letta.mobile.ui.screens.dashboard

import com.letta.mobile.data.home.HomeAgentRef
import com.letta.mobile.data.home.HomePinnedItem
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.home.conversationsLabel
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.RunRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.data.session.SessionGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import com.letta.mobile.testutil.FakeRunApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.TestData
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

/** The Android binding of the shared Home page (letta-mobile-c3np7.3.11.1). */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class DashboardViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var settings: FakeSettingsRepository
    private lateinit var agentRepository: AgentRepository
    private lateinit var conversations: AllConversationsRepository
    private lateinit var messages: MessageRepository
    private lateinit var fakeRunApi: FakeRunApi
    private lateinit var graph: SessionGraph

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settings = FakeSettingsRepository()
        agentRepository = mockk(relaxed = true)
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(TestData.agent(id = "agent-1", name = "Agent One"), TestData.agent(id = "agent-2", name = "Agent Two")),
        )
        every { agentRepository.isRefreshing } returns MutableStateFlow(false)
        conversations = mockk(relaxed = true)
        every { conversations.conversations } returns MutableStateFlow(listOf(TestData.conversation(id = "conv-1", agentId = "agent-1")))
        every { conversations.hasMore } returns MutableStateFlow(true)
        val tools: ToolRepository = mockk(relaxed = true)
        every { tools.getTools() } returns MutableStateFlow(listOf(TestData.tool(id = "tool-1"), TestData.tool(id = "tool-2")))
        coEvery { tools.countTools() } returns 2
        messages = mockk(relaxed = true)
        coEvery { messages.searchMessages(any()) } returns emptyList()
        fakeRunApi = FakeRunApi()
        graph = mockk(relaxed = true)
        every { graph.agentRepository } returns agentRepository
        every { graph.toolRepository } returns tools
        every { graph.runRepository } returns RunRepository(fakeRunApi)
        every { graph.blockRepository } returns null
        every { graph.localRuntimeBackend } returns null
    }

    @After
    fun tearDown() {
        clearAllMocks()
        Dispatchers.resetMain()
    }

    private fun viewModel() = DashboardViewModel(
        sessionGraphs = SingleGraph(graph),
        repositories = DashboardRepositories(agentRepository, conversations, messages, settings),
    )

    @Test
    fun `the fleet is folded from the conversation list and marks more pages as a lower bound`() = runTest {
        val state = viewModel().state.value
        assertEquals(2, state.fleet.summary.totalAgents)
        assertEquals("1+", state.fleet.summary.conversationsLabel)
        assertEquals(listOf("conv-1"), state.fleet.recent.map { it.conversationId })
        coVerify(exactly = 1) { conversations.refresh() }
    }

    @Test
    fun `stats come from the session graph including the day of token usage`() = runTest {
        val now = Instant.now()
        fakeRunApi.runs.add(sampleRun(id = "run-1", createdAt = now.minus(2, ChronoUnit.HOURS).toString()))
        fakeRunApi.runSteps["run-1"] = listOf(sampleStep(id = "s-1", model = "gpt-4.1", totalTokens = 1_200))

        val stats = viewModel().state.value.stats
        assertFalse(stats.loading)
        assertEquals(2, stats.toolCount)
        assertEquals(null, stats.blockCount)
        assertEquals(1_200, stats.usage?.totalTokens)
    }

    @Test
    fun `existing pins and the favorite agent carry over from settings`() = runTest {
        settings.pinnedItemsOrder.value = listOf("shortcut:TOOLS", "agent:agent-2")
        settings.setFavoriteAgentId("agent-1")

        val state = viewModel().state.value
        assertEquals(
            listOf(HomePinnedItem.Shortcut(HomeShortcut.TOOLS), HomePinnedItem.Agent(HomeAgentRef("agent-2", "Agent Two"))),
            state.pinnedItems,
        )
        assertEquals(HomeAgentRef("agent-1", "Agent One"), state.favorite)
        assertEquals("pinned names are cached for backend switches", "Agent Two", settings.pinnedAgentNames.value["agent-2"])
    }

    @Test
    fun `pin actions write through to settings`() = runTest {
        val vm = viewModel()
        vm.actions.setShortcutPinned(HomeShortcut.SCHEDULES, pinned = true)
        vm.actions.setAgentPinned("agent-1", pinned = true)
        assertTrue(settings.getPinnedShortcutOrder().first().contains("SCHEDULES"))
        assertTrue(settings.getPinnedAgentIds().first().contains("agent-1"))
    }

    @Test
    fun `message search runs through the message repository`() = runTest {
        val vm = viewModel()
        vm.actions.updateSearchQuery("plan")
        advanceTimeBy(1_000)
        coVerify { messages.searchMessages(match { it.query == "plan" && it.searchMode == "fts" }) }
    }

    private fun sampleRun(id: String, createdAt: String) = Run(id = id, agentId = "agent-1", createdAt = createdAt, status = "completed")

    private fun sampleStep(id: String, model: String, totalTokens: Int) = Step(id = id, agentId = "agent-1", model = model, totalTokens = totalTokens)

    private class SingleGraph(graph: SessionGraph) : SessionRepositoryGraphProvider<SessionGraph> {
        override val currentGraph: StateFlow<SessionGraph> = MutableStateFlow(graph)
        override val sessionError: StateFlow<Throwable?> = MutableStateFlow(null)
        override val current: SessionGraph get() = currentGraph.value

        override fun rebuild(): SessionGraph = current

        override suspend fun <T> withCurrentSession(block: suspend (SessionGraph) -> T): T = block(current)
    }
}
