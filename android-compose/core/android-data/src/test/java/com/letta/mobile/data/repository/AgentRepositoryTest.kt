package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

// letta-mobile-g2ff0: tests must wrap DAOs in dagger.Lazy because the
// production constructor now takes Lazy<AgentDao> / Lazy<ConversationDao>.
private fun <T> lazyOf(value: T): dagger.Lazy<T> = dagger.Lazy { value }

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class AgentRepositoryTest {

    private lateinit var fakeApi: FakeAgentApi
    private lateinit var fakeDao: dagger.Lazy<AgentDao>
    private lateinit var repository: AgentRepository

    @Before
    fun setup() {
        fakeApi = FakeAgentApi()
        fakeDao = lazyOf(FakeAgentDao())
        repository = AgentRepository(fakeApi, fakeDao)
    }

    @Test
    fun `getContextWindow routes through admin rpc in iroh mode`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                    "req",
                    true,
                    Json.parseToJsonElement("{\"context_window_size_current\":420,\"context_window_size_max\":128000}"),
                )
            }
        }
        val irohRepo = AgentRepository(
            remote = fakeApi,
            agentDao = fakeDao,
            settingsRepository = com.letta.mobile.testutil.FakeSettingsRepository(
                initialActiveConfig = LettaConfig(
                    id = "iroh",
                    mode = LettaConfig.Mode.SELF_HOSTED,
                    serverUrl = "iroh://EndpointTicket",
                ),
            ),
            transport = transport,
        )

        val overview = irohRepo.getContextWindow(AgentId("agent-1"), com.letta.mobile.data.model.ConversationId("conversation-1"))

        assertTrue(fakeApi.calls.none { it.startsWith("getContextWindow") })
        assertEquals("agent.context", transport.adminRpcCalls.single().method)
        assertEquals("/v1/agents/agent-1/context?conversation_id=conversation-1", transport.adminRpcCalls.single().path)
        assertEquals(420, overview.contextWindowSizeCurrent)
    }

    @Test
    fun `listAgentSummaries uses slim endpoint and does not touch shared full-agent cache`() = runTest {
        fakeApi.agents.addAll(
            listOf(
                TestData.agent(id = "a1", name = "Agent One", description = "first"),
                TestData.agent(id = "a2", name = "Agent Two", description = null),
            )
        )

        val summaries = repository.listAgentSummaries()

        assertEquals(listOf("a1", "a2"), summaries.map { it.id.value })
        assertEquals(listOf("Agent One", "Agent Two"), summaries.map { it.name })
        assertEquals("first", summaries[0].description)
        // Slim path only — never the full listAgents, and the shared full
        // agents StateFlow stays untouched (other screens still own it).
        assertTrue(fakeApi.calls.contains("listAgentsSlim"))
        assertFalse(fakeApi.calls.contains("listAgents"))
        assertTrue(repository.agents.value.isEmpty())
    }

    @Test
    fun `listAgentSummaries pages through slim endpoint`() = runTest {
        repeat(125) { index ->
            fakeApi.agents.add(TestData.agent(id = "a$index", name = "Agent $index"))
        }

        val summaries = repository.listAgentSummaries()

        assertEquals(125, summaries.size)
        assertEquals(listOf(50, 50, 50), fakeApi.slimLimits)
        assertEquals(listOf(0, 50, 100), fakeApi.slimOffsets)
    }

    @Test
    fun `concurrent refreshAgentsIfStale callers share one list request`() = runTest {
        fakeApi.agents.add(TestData.agent(id = "a1", name = "Agent One"))
        fakeApi.listDelayMillis = 1L

        List(8) {
            launch { repository.refreshAgentsIfStale(maxAgeMs = 60_000) }
        }.joinAll()

        assertEquals(1, fakeApi.calls.count { it == "listAgents" })
        assertEquals(listOf(AgentId("a1")), repository.agents.value.map { it.id })
    }

    @Test
    fun `refreshAgents hydrates cache with paged offset requests`() = runTest {
        repeat(125) { index ->
            fakeApi.agents.add(TestData.agent(id = "a$index", name = "Agent $index"))
        }

        repository.refreshAgents()

        assertEquals(125, repository.agents.value.size)
        assertEquals(List(3) { 50 }, fakeApi.listLimits)
        assertEquals(listOf(0, 50, 100), fakeApi.listOffsets)
    }

    @Test
    fun `refreshAgents hydrates beyond previous cache page ceiling`() = runTest {
        repeat(425) { index ->
            fakeApi.agents.add(TestData.agent(id = "a$index", name = "Agent $index"))
        }

        repository.refreshAgents()

        assertEquals(425, repository.agents.value.size)
        assertEquals(9, fakeApi.listLimits.size)
        assertEquals((0..400 step 50).toList(), fakeApi.listOffsets)
    }

    @Test
    fun `refreshAgents falls back to full fetch if offset pagination stops making progress`() = runTest {
        repeat(125) { index ->
            fakeApi.agents.add(TestData.agent(id = "a$index", name = "Agent $index"))
        }
        fakeApi.agents.add(TestData.agent(id = "meridian", name = "Meridian"))
        fakeApi.ignoreListOffset = true

        repository.refreshAgents()

        assertEquals(126, repository.agents.value.size)
        assertEquals("Meridian", repository.agents.value.single { it.id == AgentId("meridian") }.name)
        assertEquals(listOf(50, 50, 126), fakeApi.listLimits)
        assertEquals(listOf(0, 50, null), fakeApi.listOffsets)
        assertEquals(1, fakeApi.calls.count { it == "countAgents" })
    }

    @Test
    fun `createAgent remains remote api backed`() = runTest {
        val agent = repository.createAgent(AgentCreateParams(name = "Remote"))

        assertEquals("Remote", agent.name)
        assertEquals(listOf("createAgent:Remote", "listAgents"), fakeApi.calls)
        assertEquals(listOf(agent.id), repository.agents.value.map { it.id })
    }

    @Test
    fun `reconnect refreshes cache with one bulk list instead of per-agent GETs`() = runTest {
        val transport = FakeChannelTransport()
        repeat(3) { index ->
            fakeApi.agents.add(TestData.agent(id = "a$index", name = "Agent $index"))
        }
        val repo = AgentRepository(
            remote = fakeApi,
            agentDao = fakeDao,
            repositoryScope = backgroundScope,
            transport = transport,
        )
        repo.refreshAgents()
        runCurrent()
        val listCallsBefore = fakeApi.calls.count { it == "listAgents" }

        transport.state.value = ChannelTransportState.Disconnected(1006, "network drop")
        runCurrent()
        transport.state.value = ChannelTransportState.Connected(
            serverId = "srv",
            sessionId = "sess-2",
            deviceId = "dev",
        )
        runCurrent()

        // letta-mobile-vcmin: one paged list call, never a GET per cached agent.
        assertTrue(fakeApi.calls.none { it.startsWith("getAgent:") })
        assertEquals(listCallsBefore + 1, fakeApi.calls.count { it == "listAgents" })
        assertEquals(3, repo.agents.value.size)
    }

    @Test
    fun `agent_updated for ephemeral letta-code subagent skips per-agent GET`() = runTest {
        val transport = FakeChannelTransport()
        val repo = AgentRepository(
            remote = fakeApi,
            agentDao = fakeDao,
            repositoryScope = backgroundScope,
            transport = transport,
        )
        runCurrent()

        transport.events.emit(
            ServerFrame.AgentUpdated(
                id = "evt-1",
                ts = "2026-07-03T00:00:00Z",
                agentId = "agent-local-worker-1",
            )
        )
        runCurrent()

        assertTrue(fakeApi.calls.none { it.startsWith("getAgent:") })
        assertTrue(repo.agents.value.isEmpty())
    }

    @Test
    fun `agent_updated for persistent agent still refreshes it individually`() = runTest {
        fakeApi.agents.add(TestData.agent(id = "agent-1", name = "Persistent"))
        val transport = FakeChannelTransport()
        val repo = AgentRepository(
            remote = fakeApi,
            agentDao = fakeDao,
            repositoryScope = backgroundScope,
            transport = transport,
        )
        runCurrent()

        transport.events.emit(
            ServerFrame.AgentUpdated(
                id = "evt-1",
                ts = "2026-07-03T00:00:00Z",
                agentId = "agent-1",
            )
        )
        runCurrent()

        assertTrue(fakeApi.calls.any { it.startsWith("getAgent:") })
        assertEquals(listOf("Persistent"), repo.agents.value.map { it.name })
    }

    @Test
    fun `deleteAgent removes deleted agent from cached agents immediately`() = runTest {
        fakeApi.agents.addAll(
            listOf(
                TestData.agent(id = "a1", name = "Agent One"),
                TestData.agent(id = "a2", name = "Agent Two"),
            )
        )
        repository.refreshAgents()

        repository.deleteAgent("a1")

        assertEquals(listOf("a2"), repository.agents.value.map { it.id.value })
        assertFalse(fakeApi.agents.any { it.id == AgentId("a1") })
    }

    // H5 (data-efficiency-audit): getAgent should suppress the per-agent GET
    // when the bulk agent list was refreshed recently.
    @Test
    fun `getAgent suppresses per-agent GET when bulk refresh is fresh`() = runTest {
        fakeApi.agents.add(TestData.agent(id = "agent-1", name = "Fresh"))
        val repo = AgentRepository(
            remote = fakeApi,
            agentDao = fakeDao,
            repositoryScope = backgroundScope,
        )
        repo.refreshAgents()
        fakeApi.calls.clear()

        val collected = repo.getAgent(AgentId("agent-1")).toList()
        runCurrent()

        assertEquals(listOf("Fresh"), collected.map { it.name })
        assertTrue(
            "expected no getAgent: call when bulk cache is fresh; got ${fakeApi.calls}",
            fakeApi.calls.none { it.startsWith("getAgent:") },
        )
    }

    @Test
    fun `getAgent still fetches remote when bulk cache is stale`() = runTest {
        fakeApi.agents.add(TestData.agent(id = "agent-1", name = "Cached"))
        val repo = AgentRepository(
            remote = fakeApi,
            agentDao = fakeDao,
            repositoryScope = backgroundScope,
        )
        repo.refreshAgents()
        // Force the cache to look stale by rewinding the lastRefresh timestamp
        // past the fresh-window. No need to sleep the test dispatcher.
        repo.setLastRefreshForTest(0L)
        fakeApi.calls.clear()

        val collected = repo.getAgent(AgentId("agent-1")).toList()
        runCurrent()

        assertTrue(
            "expected getAgent: call when bulk cache is stale; got ${fakeApi.calls}",
            fakeApi.calls.any { it.startsWith("getAgent:") },
        )
        assertEquals(listOf("Cached", "Cached"), collected.map { it.name })
    }

    private class FakeAgentDao : AgentDao {
        private val agents = MutableStateFlow<List<AgentEntity>>(emptyList())

        override fun getAll(): Flow<List<AgentEntity>> = agents

        override suspend fun getAllOnce(): List<AgentEntity> = agents.value

        override suspend fun insertAll(agents: List<AgentEntity>) {
            this.agents.value = agents
        }

        override suspend fun upsert(agent: AgentEntity) {
            agents.value = agents.value.filterNot { it.id == agent.id } + agent
        }

        override suspend fun deleteExcept(keepIds: List<String>) {
            agents.value = agents.value.filter { it.id in keepIds }
        }

        override suspend fun deleteById(id: String) {
            agents.value = agents.value.filterNot { it.id == id }
        }

        override suspend fun deleteAll() {
            agents.value = emptyList()
        }
    }

    // ─── Iroh Purity Tests (letta-mobile client batch) ────────────────────────

    @Test
    fun `createAgent in iroh mode without source throws AppServerRouteUnavailable`() = runTest {
        val failure = runCatching {
            val apiThatThrows = object : FakeAgentApi() {
                override suspend fun createAgent(params: com.letta.mobile.data.model.AgentCreateParams): com.letta.mobile.data.model.Agent {
                    throw AppServerRouteUnavailable(SessionRoute.AGENTS, "Raw HTTP forbidden in iroh:// mode")
                }
            }
            val repo = AgentRepository(apiThatThrows, lazyOf(FakeAgentDao()))
            repo.createAgent(com.letta.mobile.data.model.AgentCreateParams(name = "Test Agent"))
        }.exceptionOrNull()
        org.junit.Assert.assertTrue("expected AppServerRouteUnavailable, got $failure", failure is AppServerRouteUnavailable)
    }

    @Test
    fun `countAgents in iroh mode routes via agent_count admin_rpc`() = runTest {
        val settings = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(
                id = "test",
                mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test",
                accessToken = "t",
            ),
        )
        val transport = com.letta.mobile.testutil.FakeChannelTransport()
        transport.adminRpcHandler = { method, _, _ ->
            when (method) {
                "agent.count" -> com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                    requestId = "req",
                    success = true,
                    result = kotlinx.serialization.json.JsonPrimitive(131),
                    error = null,
                )
                "agent.list" -> com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                    requestId = "req",
                    success = true,
                    result = kotlinx.serialization.json.JsonArray(emptyList()),
                    error = null,
                )
                else -> error("unexpected admin_rpc method: $method")
            }
        }
        val irohSource = IrohAdminRpcAgentSource(transport, settings)
        val apiThatThrows = object : FakeAgentApi() {
            override suspend fun countAgents(): Int {
                throw AppServerRouteUnavailable(SessionRoute.AGENTS, "Raw HTTP forbidden")
            }
        }
        val repo = AgentRepository(
            apiThatThrows,
            lazyOf(FakeAgentDao()),
            settingsRepository = settings,
            transport = transport,
            irohAgentSource = irohSource,
        )
        assertEquals(131, repo.countAgents())
        assertTrue(transport.adminRpcCalls.any { it.method == "agent.count" })
    }

    @Test
    fun `createAgent in iroh mode routes via admin_rpc`() = runTest {
        val settings = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(id = "test", mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t")
        )
        val transport = com.letta.mobile.testutil.FakeChannelTransport()
        val createdAgent = TestData.agent(id = "a1", name = "Created Agent")
        transport.adminRpcHandler = { method, _, _ ->
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            when (method) {
                "agent.create" -> com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                    requestId = "req", success = true, result = json.encodeToJsonElement(com.letta.mobile.data.model.Agent.serializer(), createdAgent), error = null
                )
                // The repository init/refresh path pages agent.list in the
                // background; answer it benignly instead of failing the create
                // assertion on an unrelated concurrent call.
                "agent.list" -> com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                    requestId = "req", success = true, result = kotlinx.serialization.json.JsonArray(emptyList()), error = null
                )
                else -> error("unexpected admin_rpc method: $method")
            }
        }
        val irohSource = IrohAdminRpcAgentSource(transport, settings)
        val apiThatThrows = object : FakeAgentApi() {
            override suspend fun createAgent(params: com.letta.mobile.data.model.AgentCreateParams): com.letta.mobile.data.model.Agent {
                throw AppServerRouteUnavailable(SessionRoute.AGENTS, "Raw HTTP forbidden")
            }
            override suspend fun listAgents(limit: Int?, offset: Int?, tags: List<String>?): List<com.letta.mobile.data.model.Agent> = emptyList()
        }
        val repo = AgentRepository(apiThatThrows, lazyOf(FakeAgentDao()), settingsRepository = settings, transport = transport, irohAgentSource = irohSource)
        val result = repo.createAgent(com.letta.mobile.data.model.AgentCreateParams(name = "Test Agent"))
        assertEquals("Created Agent", result.name)
        assertTrue(transport.adminRpcCalls.any { it.method == "agent.create" })
    }

    @Test
    fun `deleteAgent in iroh mode routes via admin_rpc`() = runTest {
        val settings = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(id = "test", mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t")
        )
        val transport = com.letta.mobile.testutil.FakeChannelTransport()
        transport.adminRpcHandler = { method, path, _ ->
            assertEquals("agent.delete", method)
            assertEquals("/v1/agents/a1", path)
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(requestId = "req", success = true, result = kotlinx.serialization.json.JsonNull, error = null)
        }
        val irohSource = IrohAdminRpcAgentSource(transport, settings)
        val apiThatThrows = object : FakeAgentApi() {
            override suspend fun deleteAgent(id: AgentId) {
                throw AppServerRouteUnavailable(SessionRoute.AGENTS, "Raw HTTP forbidden")
            }
        }
        val repo = AgentRepository(apiThatThrows, lazyOf(FakeAgentDao()), settingsRepository = settings, transport = transport, irohAgentSource = irohSource)
        repo.deleteAgent(AgentId("a1"))
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `refreshAgents pages past small server page sizes`() = runTest {
        val totalAgents = (1..60).map { TestData.agent(id = "agent-$it", name = "Agent $it") }
        val apiWithSmallPageCap = object : FakeAgentApi() {
            override suspend fun listAgents(limit: Int?, offset: Int?, tags: List<String>?): List<com.letta.mobile.data.model.Agent> {
                val currentOffset = offset ?: 0
                val serverCap = 20
                if (currentOffset >= totalAgents.size) return emptyList()
                return totalAgents.drop(currentOffset).take(serverCap)
            }
        }
        val repo = AgentRepository(apiWithSmallPageCap, lazyOf(FakeAgentDao()))
        repo.refreshAgents()
        assertEquals(60, repo.agents.value.size)
    }
}
