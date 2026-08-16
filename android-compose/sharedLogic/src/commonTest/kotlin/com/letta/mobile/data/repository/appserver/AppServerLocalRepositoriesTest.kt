package com.letta.mobile.data.repository.appserver

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppServerLocalRepositoriesTest {
    @Test
    fun `agent repository caches typed App Server rows`() = runTest {
        val agent = Agent(id = AgentId("agent-1"), name = "Ada", description = "Local")
        val transport = FakeTransport(
            agents = JsonArray(
                listOf(AppServerProtocol.json.encodeToJsonElement(Agent.serializer(), agent)),
            ),
        )
        val repository = AppServerAgentRepository(transport)

        repository.refreshAgents()

        assertEquals(1, repository.countAgents())
        assertEquals(agent, repository.getCachedAgent(agent.id))
        assertEquals("Ada", repository.listAgentSummaries().single().name)
        assertEquals(1, transport.agentListCalls)
    }

    @Test
    fun `agent repository exposes refresh failures`() = runTest {
        val failure = IllegalStateException("local runtime unavailable")
        val repository = AppServerAgentRepository(FakeTransport(agentFailure = failure))

        val thrown = runCatching { repository.refreshAgents() }.exceptionOrNull()

        assertSame(failure, thrown)
        assertSame(failure, repository.refreshError.value)
    }

    @Test
    fun `concurrent stale refreshes share one App Server request`() = runTest {
        val agent = Agent(id = AgentId("agent-1"), name = "Ada")
        val release = CompletableDeferred<Unit>()
        val transport = FakeTransport(
            agents = JsonArray(listOf(AppServerProtocol.json.encodeToJsonElement(Agent.serializer(), agent))),
            agentRelease = release,
        )
        val repository = AppServerAgentRepository(transport)

        val refreshes = listOf(
            async { repository.refreshAgentsIfStale(30_000L) },
            async { repository.refreshAgentsIfStale(30_000L) },
        )
        runCurrent()

        assertEquals(1, transport.agentListCalls)
        assertEquals(true, repository.isRefreshing.value)
        release.complete(Unit)
        assertEquals(listOf(true, false), refreshes.awaitAll())
        assertEquals(false, repository.isRefreshing.value)
    }

    @Test
    fun `block repository decodes authoritative App Server rows`() = runTest {
        val block = Block(id = BlockId("block-1"), label = "human", value = "Prefers concise replies")
        val transport = FakeTransport(
            blocks = JsonArray(
                listOf(AppServerProtocol.json.encodeToJsonElement(Block.serializer(), block)),
            ),
        )

        val loaded = AppServerAgentBlockRepository(transport).getBlocks("agent-1")

        assertEquals(listOf(block), loaded)
        assertEquals("agent-1", transport.lastBlockAgentId)
    }

    private class FakeTransport(
        private val agents: JsonArray = JsonArray(emptyList()),
        private val blocks: JsonArray = JsonArray(emptyList()),
        private val context: JsonObject? = null,
        private val agentFailure: Throwable? = null,
        private val agentRelease: CompletableDeferred<Unit>? = null,
    ) : AppServerLocalRepositoryTransport {
        var agentListCalls = 0
        var lastBlockAgentId: String? = null

        override suspend fun listAgents(): JsonArray {
            agentListCalls += 1
            agentRelease?.await()
            agentFailure?.let { throw it }
            return agents
        }

        override suspend fun getContext(agentId: String, conversationId: String?): JsonObject? = context

        override suspend fun listAgentBlocks(agentId: String): JsonArray {
            lastBlockAgentId = agentId
            return blocks
        }
    }
}
