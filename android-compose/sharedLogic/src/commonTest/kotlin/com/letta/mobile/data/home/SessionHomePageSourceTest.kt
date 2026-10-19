package com.letta.mobile.data.home

import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.SessionCapabilities
import com.letta.mobile.runtime.RunId
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.IPassageRepository
import com.letta.mobile.data.repository.api.IProjectRepository
import com.letta.mobile.data.repository.api.IProjectWorkRepository
import com.letta.mobile.data.repository.api.IProviderRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import com.letta.mobile.data.repository.api.IStepRepository
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.api.IVibesyncEventStreamRepository
import com.letta.mobile.data.repository.iroh.FakeIrohAdminTransport
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.ChannelTransportState
import kotlinx.coroutines.CompletableDeferred
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.RuntimeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-c3np7.3.11: on an iroh:// backend a figure whose route has no admin_rpc path fails
 * with the iroh guard's error. That must cost only its own tile, never the page-level banner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHomePageSourceTest {

    @Test
    fun reconnectReloadsStatsAndCloseStopsObserving() = runTest {
        val tools = FakeTools(Result.success(4))
        val connection = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Idle)
        val transport = object : com.letta.mobile.data.transport.api.NoOpChannelTransport() {
            override val state: StateFlow<ChannelTransportState> = connection
        }
        val graph = HomeGraph(tools = tools, channelTransport = transport)
        val source = SessionHomePageSource(FixedGraphProvider(graph), backgroundScope)
        source.start()
        runCurrent()
        assertEquals(1, tools.countCalls)
        tools.count = Result.success(7)
        connection.value = ChannelTransportState.Connected("server", "first", "device")
        runCurrent()
        assertEquals(7, source.stats.value.toolCount)
        assertEquals(2, tools.countCalls)
        connection.value = ChannelTransportState.Connected("server", "first", "device", a2uiEnabled = true)
        runCurrent()
        assertEquals(2, tools.countCalls, "metadata changes while connected do not reload")
        connection.value = ChannelTransportState.Connecting(reconnecting = true)
        runCurrent()
        tools.count = Result.success(9)
        connection.value = ChannelTransportState.Connected("server", "second", "device")
        runCurrent()
        assertEquals(9, source.stats.value.toolCount)
        assertEquals(3, tools.countCalls)
        source.close()
        runCurrent()
        connection.value = ChannelTransportState.Idle
        runCurrent()
        connection.value = ChannelTransportState.Connected("server", "third", "device")
        runCurrent()
        assertEquals(3, tools.countCalls)
    }

    @Test
    fun entrySharesAnActiveRequestAndRetryRecoversAFailure() = runTest {
        val pending = CompletableDeferred<Unit>()
        val tools = FakeTools(Result.failure(IrohGuardException()), beforeCount = { pending.await() })
        val source = SessionHomePageSource(
            FixedGraphProvider(HomeGraph(tools = tools, runs = FakeRuns(IrohGuardException()))),
            backgroundScope,
        )
        source.start()
        runCurrent()
        source.refresh()
        runCurrent()
        assertEquals(1, tools.countCalls, "entry must not restart an in-flight initial request")
        pending.complete(Unit)
        runCurrent()
        assertNotNull(source.stats.value.error)
        tools.count = Result.success(8)
        source.refresh()
        runCurrent()
        assertEquals(8, source.stats.value.toolCount)
        assertNull(source.stats.value.error)
        assertEquals(2, tools.countCalls)
        source.close()
    }

    @Test
    fun aFigureWithNoAdminRpcPathDropsItsTileWithoutTheBanner() = runTest {
        val stats = loadedStats(tools = FakeTools(Result.failure(IrohGuardException())))

        assertNull(stats.error, "one failed figure must not raise the page banner")
        assertNull(stats.toolCount)
        assertEquals(0, stats.usage?.totalTokens)
        val state = homeState(stats)
        assertNull(state.shortcutInfo(HomeShortcut.TOOLS), "the Tools tile degrades to its label")
        assertFalse(state.statTiles().any { it.label == "Tools" }, "the stat strip drops the Tools tile")
        assertTrue(state.statTiles().any { it.label.startsWith("Tokens") }, "the figures that loaded stay")
    }

    @Test
    fun aFailedUsageFigureKeepsTheOthers() = runTest {
        val stats = loadedStats(tools = FakeTools(Result.success(4)), runs = FakeRuns(failure = IrohGuardException()))

        assertNull(stats.error)
        assertEquals(4, stats.toolCount)
        assertNull(stats.usage)
        val state = homeState(stats)
        assertEquals(HomeShortcut.USAGE.description, state.shortcutInfo(HomeShortcut.USAGE), "no fake \"0 tokens\" for a failed figure")
        assertFalse(state.statTiles().any { it.label.startsWith("Tokens") })
    }

    @Test
    fun theBannerShowsOnlyWhenEveryFigureFailed() = runTest {
        val stats = loadedStats(
            tools = FakeTools(Result.failure(IrohGuardException())),
            runs = FakeRuns(failure = IrohGuardException()),
        )

        assertNotNull(stats.error)
        assertFalse(stats.loading)
    }

    /**
     * letta-mobile-by4vm.13: a figure whose route the session does not serve is never asked for, so
     * it neither fails nor raises the banner (these fakes would fail every figure if asked).
     */
    @Test
    fun figuresOutsideTheSessionCapabilitiesAreNeverAsked() = runTest {
        val stats = loadedStats(
            tools = FakeTools(Result.failure(IrohGuardException())),
            runs = FakeRuns(failure = IrohGuardException()),
            capabilities = SessionCapabilities(setOf(SessionRoute.AGENTS)),
        )

        assertNull(stats.error, "no figure was asked for, so none failed")
        assertNull(stats.toolCount)
        assertNull(stats.usage)
    }

    /** Stats once every figure has settled, from a source over an iroh-style graph. */
    private fun TestScope.loadedStats(
        tools: IToolRepository,
        runs: IRunRepository = FakeRuns(),
        capabilities: SessionCapabilities = SessionCapabilities(SessionRoute.entries.toSet()),
    ): HomeStats {
        val graph = HomeGraph(tools = tools, runs = runs, capabilities = capabilities)
        val source = SessionHomePageSource(sessionGraphProvider = FixedGraphProvider(graph), scope = backgroundScope)
        source.start()
        runCurrent()
        val stats = source.stats.value
        assertFalse(stats.loading)
        source.close()
        return stats
    }

    private fun homeState(stats: HomeStats): HomePageState = HomePageReducer.withStats(HomePageState(), stats)

    /** Stand-in for a route failure the session's capabilities did not predict (a host-side guard). */
    private class IrohGuardException : IllegalStateException(
        "Admin API call attempted while the active backend is an Iroh endpoint (iroh://node). This route has no admin_rpc path",
    )

    private class FixedGraphProvider(graph: HomeGraph) : SessionRepositoryGraphProvider<HomeGraph> {
        override val currentGraph: StateFlow<HomeGraph> = MutableStateFlow(graph)
        override val sessionError: StateFlow<Throwable?> = MutableStateFlow(null)
        override val current: HomeGraph get() = currentGraph.value
        override fun rebuild(): HomeGraph = current
        override suspend fun <T> withCurrentSession(block: suspend (HomeGraph) -> T): T = block(current)
    }

    private class HomeGraph(
        tools: IToolRepository = FakeTools(),
        runs: IRunRepository = FakeRuns(),
        override val channelTransport: IChannelTransport = FakeIrohAdminTransport(),
        /** Every route by default (an Iroh session); letta-mobile-by4vm.13 tests narrow it. */
        override val capabilities: SessionCapabilities = SessionCapabilities(SessionRoute.entries.toSet()),
    ) : SessionRepositoryGraph {
        override val id: Long = 1
        override val toolRepository: IToolRepository = tools
        override val runRepository: IRunRepository = runs
        override val backendDescriptor = BackendDescriptor(
            backendId = BackendId("iroh:test"),
            runtimeId = RuntimeId("iroh:test"),
            kind = BackendKind.RemoteLetta,
            label = "Iroh",
            capabilities = BackendCapabilities(
                supportsStreaming = false,
                supportsMemFs = false,
                supportsToolEvents = false,
                supportsToolExecution = false,
                supportsApprovals = false,
                supportsAgentFileImport = false,
                supportsAgentFileExport = false,
            ),
        )
        override val agentRepository: IAgentRepository = FakeAgents()
        override val conversationRepository: IConversationRepository get() = unused()
        override val cronRepository: ICronRepository get() = unused()
        override val mcpServerRepository: IMcpServerRepository get() = unused()
        override val modelRepository: IModelRepository get() = unused()
        override val passageRepository: IPassageRepository get() = unused()
        override val projectRepository: IProjectRepository get() = unused()
        override val projectWorkRepository: IProjectWorkRepository get() = unused()
        override val providerRepository: IProviderRepository get() = unused()
        override val scheduleRepository: IScheduleRepository get() = unused()
        override val selfTodoRepository: ISelfTodoRepository get() = unused()
        override val stepRepository: IStepRepository get() = unused()
        override val subagentRepository: ISubagentRepository get() = unused()
        override val vibesyncEventStreamRepository: IVibesyncEventStreamRepository get() = unused()

        override fun close() = Unit

        private fun unused(): Nothing = error("Home does not read this repository")
    }

    private class FakeTools(
        var count: Result<Int> = Result.success(0),
        private val beforeCount: suspend () -> Unit = {},
    ) : IToolRepository {
        var countCalls = 0
        private val tools = MutableStateFlow<List<Tool>>(emptyList())
        override fun getTools(): StateFlow<List<Tool>> = tools
        override fun getAgentTools(agentId: AgentId): Flow<List<Tool>> = flowOf(emptyList())
        override suspend fun countTools(): Int {
            countCalls += 1
            beforeCount()
            return count.getOrThrow()
        }
        override suspend fun refreshTools() = Unit
        override suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean = false
        override suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool> = emptyList()
        override suspend fun attachTool(agentId: AgentId, toolId: ToolId) = Unit
        override suspend fun detachTool(agentId: AgentId, toolId: ToolId) = Unit
        override suspend fun upsertTool(params: ToolCreateParams): Tool = error("unused")
        override suspend fun updateTool(toolId: ToolId, params: ToolUpdateParams): Tool = error("unused")
        override suspend fun deleteTool(toolId: ToolId) = Unit
    }

    private class FakeRuns(private val failure: Throwable? = null) : IRunRepository {
        override val runs: StateFlow<List<Run>> = MutableStateFlow(emptyList())
        override suspend fun refreshRuns(params: RunListParams) = Unit
        override suspend fun getRecentRuns(limit: Int): List<Run> = failure?.let { throw it } ?: emptyList()
        override suspend fun getRun(runId: RunId): Run = error("unused")
        override suspend fun getRunMessages(runId: RunId): List<LettaMessage> = emptyList()
        override suspend fun getRunUsage(runId: RunId): UsageStatistics = error("unused")
        override suspend fun getRunMetrics(runId: RunId): RunMetrics = error("unused")
        override suspend fun getRunSteps(runId: RunId): List<Step> = emptyList()
        override suspend fun cancelRun(run: Run): Run = run
        override suspend fun deleteRun(runId: RunId) = Unit
        override fun upsertRun(run: Run) = Unit
    }

    private class FakeAgents : IAgentRepository {
        override val agents: StateFlow<List<Agent>> = MutableStateFlow(emptyList())
        override val isRefreshing: StateFlow<Boolean> = MutableStateFlow(false)
        override val refreshError: StateFlow<Throwable?> = MutableStateFlow(null)
        override suspend fun countAgents(): Int = 0
        override suspend fun refreshAgents() = Unit
        override suspend fun listAgentSummaries(): List<AgentSummary> = emptyList()
        override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = false
        override fun getCachedAgent(id: AgentId): Agent? = null
        override fun getAgent(id: AgentId): Flow<Agent> = error("unused")
        override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview = error("unused")
        override suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit) = operation()
        override suspend fun createAgent(params: AgentCreateParams): Agent = error("unused")
        override suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent = error("unused")
        override suspend fun deleteAgent(id: AgentId) = Unit
        override suspend fun exportAgent(id: AgentId): String = error("unused")
        override suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse = error("unused")
        override suspend fun attachArchive(agentId: AgentId, archiveId: String) = Unit
        override suspend fun detachArchive(agentId: AgentId, archiveId: String) = Unit
    }
}
