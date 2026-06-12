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

        override suspend fun deleteAll() {
            agents.value = emptyList()
        }
    }
}
