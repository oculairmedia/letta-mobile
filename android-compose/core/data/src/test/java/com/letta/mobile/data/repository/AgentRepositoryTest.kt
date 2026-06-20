package com.letta.mobile.data.repository

import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentRuntimeBinding
import com.letta.mobile.data.model.LocalAgentRuntimeMetadata
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class AgentRepositoryTest {

    private lateinit var fakeApi: FakeAgentApi
    private lateinit var fakeDao: FakeAgentDao
    private lateinit var repository: AgentRepository

    @Before
    fun setup() {
        fakeApi = FakeAgentApi()
        fakeDao = FakeAgentDao()
        repository = AgentRepository(fakeApi, fakeDao)
    }

    // letta-mobile-y5c9u / yfo86 / 6oglt: local-runtime mode routes agent
    // reads/writes to the on-device store instead of the remote API.
    private class FakeLocalAgentSource : com.letta.mobile.data.repository.api.LocalRuntimeAgentSource {
        val persisted = mutableListOf<com.letta.mobile.data.model.Agent>()
        var stored = mutableListOf<com.letta.mobile.data.model.Agent>()
        var overview: com.letta.mobile.data.model.ContextWindowOverview? = null

        override suspend fun listAgents(): List<com.letta.mobile.data.model.Agent> = stored.toList()

        override suspend fun persistAgent(agent: com.letta.mobile.data.model.Agent) {
            persisted += agent
            stored.removeAll { it.id == agent.id }
            stored += agent
        }

        override suspend fun contextWindowOverview(agentId: AgentId) = overview
    }

    private fun localRepository(source: FakeLocalAgentSource): AgentRepository = AgentRepository(
        agentApi = fakeApi,
        agentDao = fakeDao,
        localAgentSource = source,
        settingsRepository = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(
                id = "local-1",
                mode = com.letta.mobile.data.model.LettaConfig.Mode.LOCAL,
                serverUrl = "local-lettacode://device",
            ),
        ),
    )

    @Test
    fun `refreshAgents lists from local source when local runtime active`() = runTest {
        val source = FakeLocalAgentSource().apply {
            stored += TestData.agent(id = "local-agent-1", name = "My Local Agent")
        }
        val localRepo = localRepository(source)

        localRepo.refreshAgents()

        assertEquals(listOf("My Local Agent"), localRepo.agents.value.map { it.name })
        assertEquals(0, fakeApi.calls.count { it == "listAgents" })
    }

    @Test
    fun `createLocalAgent persists to local source`() = runTest {
        val source = FakeLocalAgentSource()
        val localRepo = localRepository(source)

        val agent = localRepo.createLocalAgent(AgentCreateParams(name = "Persisted Agent"))

        assertEquals(listOf(agent.id), source.persisted.map { it.id })
    }

    @Test
    fun `getContextWindow uses local estimate when local runtime active`() = runTest {
        val source = FakeLocalAgentSource().apply {
            overview = com.letta.mobile.data.model.ContextWindowOverview(
                contextWindowSizeMax = 128_000,
                contextWindowSizeCurrent = 420,
            )
        }
        val localRepo = localRepository(source)

        val overview = localRepo.getContextWindow(AgentId("local-agent-1"), null)

        assertEquals(420, overview.contextWindowSizeCurrent)
        assertTrue(fakeApi.calls.isEmpty())
    }

    @Test
    fun `updateAgent on local-bound agent persists locally without remote call`() = runTest {
        val source = FakeLocalAgentSource().apply {
            stored += TestData.agent(id = "local-agent-1", name = "Before", model = "google/gemma-3n-E2B-it-litert-lm")
        }
        val localRepo = localRepository(source)
        localRepo.refreshAgents()

        val updated = localRepo.updateAgent(
            AgentId("local-agent-1"),
            com.letta.mobile.data.model.AgentUpdateParams(name = "After"),
        )

        assertEquals("After", updated.name)
        assertEquals("After", source.stored.single().name)
        assertTrue(fakeApi.calls.none { it.startsWith("updateAgent") })
    }

    @Test
    fun `local-agent id with cloud model is not local-bound`() {
        val agent = TestData.agent(
            id = "local-agent-1",
            model = "openai/gpt-4o-mini",
        )

        assertFalse(AgentRuntimeBinding.isLocalBound(agent))
    }

    @Test
    fun `local-agent id with local model remains local-bound`() {
        val agent = TestData.agent(
            id = "local-agent-1",
            model = "google/gemma-3n-E2B-it-litert-lm",
        )

        assertTrue(AgentRuntimeBinding.isLocalBound(agent))
    }

    @Test
    fun `local runtime metadata with cloud model is not local-bound`() {
        val agent = TestData.agent(
            id = "local-agent-1",
            model = "anthropic/claude-3-5-sonnet",
        ).copy(
            metadata = mapOf(LocalAgentRuntimeMetadata.RuntimeProviderKey to JsonPrimitive(LocalAgentRuntimeMetadata.LocalLettaCodeRuntime)),
        )

        assertFalse(AgentRuntimeBinding.isLocalBound(agent))
    }

    @Test
    fun `switching local agent to cloud model updates remote instead of persisting locally`() = runTest {
        val source = FakeLocalAgentSource().apply {
            stored += TestData.agent(
                id = "local-agent-1",
                name = "Before",
                model = "google/gemma-3n-E2B-it-litert-lm",
            )
        }
        fakeApi.agents.add(
            TestData.agent(
                id = "local-agent-1",
                name = "Before",
                model = "google/gemma-3n-E2B-it-litert-lm",
            ),
        )
        val localRepo = localRepository(source)
        localRepo.refreshAgents()

        val updated = localRepo.updateAgent(
            AgentId("local-agent-1"),
            com.letta.mobile.data.model.AgentUpdateParams(model = "openai/gpt-4o-mini"),
        )

        assertEquals("openai/gpt-4o-mini", updated.model)
        assertTrue(fakeApi.calls.any { it == "updateAgent:local-agent-1" })
        assertTrue(source.persisted.none { it.model == "openai/gpt-4o-mini" })
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
    fun `createLocalAgent inserts local-bound row and emits cache without api`() = runTest {
        val agent = repository.createLocalAgent(
            AgentCreateParams(
                name = "Local",
                model = "lmstudio/local-model",
                metadata = mapOf(LocalAgentRuntimeMetadata.RuntimeKey to kotlinx.serialization.json.JsonPrimitive(LocalAgentRuntimeMetadata.LocalLettaCodeRuntime)),
                toolIds = listOf(com.letta.mobile.data.model.ToolId("tool-1")),
                includeBaseTools = true,
            )
        )

        assertTrue(agent.id.value.startsWith("local-agent-"))
        assertTrue(AgentRuntimeBinding.isLocalBound(agent))
        assertEquals("lmstudio/local-model", agent.model)
        assertTrue(agent.tools.isEmpty())
        assertEquals(listOf(agent.id), repository.agents.value.map { it.id })
        assertEquals(listOf(agent.id.value), fakeDao.getAllOnce().map { it.id })
        assertEquals(LocalAgentRuntimeMetadata.LocalLettaCodeRuntime, fakeDao.getAllOnce().single().toAgent().metadata[LocalAgentRuntimeMetadata.RuntimeKey]?.jsonPrimitive?.contentOrNull)
        assertFalse(fakeApi.calls.any { it.startsWith("createAgent") })
    }

    @Test
    fun `createAgent remains remote api backed`() = runTest {
        val agent = repository.createAgent(AgentCreateParams(name = "Remote"))

        assertEquals("Remote", agent.name)
        assertEquals(listOf("createAgent:Remote", "listAgents"), fakeApi.calls)
        assertEquals(listOf(agent.id), repository.agents.value.map { it.id })
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
}
