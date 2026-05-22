package com.letta.mobile.data.session

import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {
    @Test
    fun `active config change rebuilds session graph and cancels previous scope`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = config("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(FakeAgentApi(), FakeAgentDao()),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        advanceUntilIdle()

        val firstGraph = sessionManager.current
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        val secondGraph = sessionManager.current
        assertNotEquals(firstGraph.id, secondGraph.id)
        assertTrue(firstGraph.scope.coroutineContext.job.isCancelled)
        assertTrue(!secondGraph.scope.coroutineContext.job.isCancelled)
    }

    @Test
    fun `agent repository proxy switches state and calls to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeApi = FakeAgentApi().apply {
            agents = mutableListOf(TestData.agent(id = "agent-a", name = "Backend A Agent"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = config("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(fakeApi, FakeAgentDao()),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val proxy = SessionScopedAgentRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        proxy.refreshAgents()
        advanceUntilIdle()
        assertEquals(AgentId("agent-a"), proxy.agents.value.single().id)

        fakeApi.agents = mutableListOf(TestData.agent(id = "agent-b", name = "Backend B Agent"))
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        proxy.refreshAgents()
        advanceUntilIdle()

        assertEquals(AgentId("agent-b"), proxy.agents.value.single().id)
        assertNull(proxy.getCachedAgent("agent-a"))
    }

    private fun config(id: String): LettaConfig = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = "https://$id.example.test",
    )

    private class FakeAgentDao : AgentDao {
        private val agents = MutableStateFlow<List<AgentEntity>>(emptyList())

        override fun getAll(): Flow<List<AgentEntity>> = agents

        override suspend fun getAllOnce(): List<AgentEntity> = agents.value

        override suspend fun insertAll(agents: List<AgentEntity>) {
            this.agents.value = agents
        }

        override suspend fun deleteExcept(keepIds: List<String>) {
            agents.value = agents.value.filter { it.id in keepIds }
        }

        override suspend fun deleteAll() {
            agents.value = emptyList()
        }
    }
}
