package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.home.SessionHomePageSourceTest
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentMemory
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.repository.AgentRepositoryTestSource
import com.letta.mobile.data.repository.CachedAgentRepository
import com.letta.mobile.data.repository.cachedAgentRepository
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `agent.list` rows carry no memory. A roster refresh must not erase the memory an earlier detail read
 * cached, and an agent known only from the list has its blocks read from its detail, once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryBlockSlimRosterTest {
    private val persona = Block(id = BlockId("block-persona"), label = "persona", value = "I keep the roadmap.")
    private val listRow = Agent(id = ID, name = "Ada", model = "anthropic/claude-sonnet-5-5")
    private val detail = listRow.copy(system = "Full system prompt", memory = AgentMemory(blocks = persistentListOf(persona)))
    private val ref = MemoryBlockRef(agentId = ID.value, label = "persona", blockId = "block-persona")

    @Test
    fun aSlimRosterRefreshKeepsTheCachedAgentsMemory() = runTest {
        val remote = AgentRepositoryTestSource().apply { roster = listOf(listRow); read = { detail } }
        val repository = cachedAgentRepository(remote)
        repository.getFreshAgent(ID)

        repository.refreshAgents()

        val cached = repository.agents.value.single()
        assertEquals("Full system prompt", cached.system, "a list row never erases cached detail")
        assertEquals(listOf(persona), cached.coreBlocks)
        assertEquals(persona, port(repository).load(ref))
        assertEquals(listOf(ID), remote.getCalls, "the cached detail serves the memory read")
    }

    @Test
    fun anAgentKnownOnlyFromTheListReadsItsDetailOnce() = runTest {
        val remote = AgentRepositoryTestSource().apply { roster = listOf(listRow); read = { detail } }
        val repository = cachedAgentRepository(remote)
        repository.refreshAgents()

        assertEquals(persona, port(repository).load(ref))
        assertEquals(listOf(ID), remote.getCalls)
    }

    @Test
    fun aFullRowFromAnOlderHostStillReplacesTheCachedRecord() = runTest {
        val renamed = detail.copy(name = "Ada Two", memory = AgentMemory())
        val remote = AgentRepositoryTestSource().apply { roster = listOf(renamed); read = { detail } }
        val repository = cachedAgentRepository(remote)
        repository.getFreshAgent(ID)

        repository.refreshAgents()

        assertEquals(renamed, repository.agents.value.single())
    }

    /** A session with no block repository, so the port reads the agent's embedded memory. */
    private fun port(repository: CachedAgentRepository): MemoryBlockContentPort {
        val graph = object : SessionRepositoryGraph by SessionHomePageSourceTest.HomeGraph() {
            override val agentRepository = repository
            override val blockRepository = null
        }
        return SessionGraphMemoryBlockPort(
            object : SessionRepositoryGraphProvider<SessionRepositoryGraph> {
                override val currentGraph = MutableStateFlow<SessionRepositoryGraph>(graph)
                override val sessionError: StateFlow<Throwable?> = MutableStateFlow(null)
                override val current: SessionRepositoryGraph get() = currentGraph.value
                override fun rebuild(): SessionRepositoryGraph = current
                override suspend fun <T> withCurrentSession(block: suspend (SessionRepositoryGraph) -> T): T = block(current)
            },
        )
    }

    private companion object {
        val ID = AgentId("agent-ada")
    }
}
