package com.letta.mobile.data.memory

import com.letta.mobile.data.backend.SessionCapabilities
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.home.SessionHomePageSourceTest
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryParityControllerTest {
    @Test
    fun changingAgentsImmediatelyHidesThePreviousAgentsMemory() = runTest {
        val agents = object : IAgentRepository by SessionHomePageSourceTest.HomeGraph().agentRepository {
            override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = false
            override suspend fun getFreshAgent(id: AgentId, force: Boolean): Agent {
                check(id == AgentId("agent-a") || id == AgentId("agent-b"))
                return Agent(id = id, name = id.value)
            }
        }
        val graph = object : SessionRepositoryGraph by SessionHomePageSourceTest.HomeGraph() {
            override val agentRepository = agents
            override val capabilities = SessionCapabilities(setOf(SessionRoute.AGENTS))
        }
        val provider = object : SessionRepositoryGraphProvider<SessionRepositoryGraph> {
            override val currentGraph = MutableStateFlow<SessionRepositoryGraph>(graph)
            override val sessionError: StateFlow<Throwable?> = MutableStateFlow(null)
            override val current: SessionRepositoryGraph get() = currentGraph.value
            override fun rebuild(): SessionRepositoryGraph = current
            override suspend fun <T> withCurrentSession(block: suspend (SessionRepositoryGraph) -> T): T = block(current)
        }
        val controller = MemoryParityController(provider, this, sectionReader = MemoryParitySectionReader { _, _ -> })
        controller.selectAgent("agent-a")
        advanceUntilIdle()
        assertEquals("agent-a", controller.state.value.memory.selectedAgentId)
        controller.selectAgent("agent-b")
        val immediate = controller.state.value.memory.selectedAgentId
        advanceUntilIdle()
        assertEquals("agent-b", controller.state.value.memory.selectedAgentId)

        lateinit var oldRequest: Continuation<Agent>
        val delayed = object : IAgentRepository by agents {
            override suspend fun getFreshAgent(id: AgentId, force: Boolean): Agent =
                if (id == AgentId("agent-a")) suspendCoroutine { oldRequest = it } else Agent(id = id, name = id.value)
        }
        provider.currentGraph.value = object : SessionRepositoryGraph by graph { override val agentRepository = delayed }
        val second = MemoryParityController(provider, this, sectionReader = MemoryParitySectionReader { _, _ -> })
        second.selectAgent("agent-a")
        runCurrent()
        second.selectAgent("agent-b")
        advanceUntilIdle()
        oldRequest.resume(Agent(id = AgentId("agent-a"), name = "agent-a"))
        advanceUntilIdle()
        assertEquals(listOf(null, "agent-b"), listOf(immediate, second.state.value.memory.selectedAgentId))
    }

    @Test
    fun failedRosterStillResolvesTheRequestedAgentDirectly() = runTest {
        val reads = mutableListOf<AgentId>()
        val agents = object : IAgentRepository by SessionHomePageSourceTest.HomeGraph().agentRepository {
            override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = error("roster unavailable")
            override suspend fun getFreshAgent(id: AgentId, force: Boolean): Agent {
                reads += id
                if (id != AgentId("agent-b")) error("unknown agent")
                return Agent(id = id, name = "Agent B")
            }
        }
        val graph = object : SessionRepositoryGraph by SessionHomePageSourceTest.HomeGraph() {
            override val agentRepository = agents
            override val capabilities = SessionCapabilities(setOf(SessionRoute.AGENTS))
        }
        val provider = object : SessionRepositoryGraphProvider<SessionRepositoryGraph> {
            override val currentGraph = MutableStateFlow<SessionRepositoryGraph>(graph)
            override val sessionError: StateFlow<Throwable?> = MutableStateFlow(null)
            override val current: SessionRepositoryGraph get() = currentGraph.value
            override fun rebuild(): SessionRepositoryGraph = current
            override suspend fun <T> withCurrentSession(block: suspend (SessionRepositoryGraph) -> T): T = block(current)
        }
        val controller = MemoryParityController(provider, this, sectionReader = MemoryParitySectionReader { _, _ -> })
        controller.selectAgent("agent-b")
        advanceUntilIdle()
        assertEquals(listOf(AgentId("agent-b")), reads)
        assertEquals("agent-b", controller.state.value.memory.selectedAgentId)
    }
}
