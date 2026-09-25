package com.letta.mobile.data.repository.appserver

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.AgentBlockTarget
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    @Test
    fun `default transport follows active client rotation`() = runTest {
        val first = FakeClient { okBlocks("[]") }
        val second = FakeClient { okBlocks("[]") }
        var active: AppServerClient = first
        val transport = DefaultAppServerLocalRepositoryTransport(
            clientProvider = { active },
            requestId = { it },
        )

        transport.listAgentBlocks("agent-1")
        active = second
        transport.listAgentBlocks("agent-1")

        assertEquals(1, first.adminRpcCalls.size)
        assertEquals(1, second.adminRpcCalls.size)
    }

    @Test
    fun `default transport merges paged block envelopes`() = runTest {
        val offsets = mutableListOf<String>()
        val client = FakeClient { command ->
            val offset = command.params?.get("offset")?.jsonPrimitive?.content.orEmpty()
            offsets += offset
            if (offset == "0") {
                okBlocks("""{"blocks":[{"id":"block-1","label":"human","value":"one"}],"has_more":true}""")
            } else {
                okBlocks("""{"blocks":[{"id":"block-2","label":"persona","value":"two"}],"has_more":false}""")
            }
        }
        val transport = DefaultAppServerLocalRepositoryTransport({ client }) { it }

        val blocks = transport.listAgentBlocks("agent-1")

        assertEquals(listOf("0", "1"), offsets)
        assertEquals(listOf("block-1", "block-2"), blocks.map { it.jsonObject["id"]?.jsonPrimitive?.content })
    }

    @Test
    fun `default transport rejects quoted pagination flag`() = runTest {
        val client = FakeClient { okBlocks("""{"blocks":[],"has_more":"false"}""") }
        val transport = DefaultAppServerLocalRepositoryTransport({ client }) { it }

        assertFailsWith<IllegalStateException> { transport.listAgentBlocks("agent-1") }
    }

    @Test
    fun blockRepositoryWritesByAgentAndLabel() = runTest {
        val transport = FakeTransport()
        val write = BlockWrite(AgentBlockTarget("agent-1", "human"), BlockUpdateParams(value = "Prefers tea"))

        val saved = AppServerAgentBlockRepository(transport).writeAgentBlock(write.target, write.params)

        assertEquals(write, transport.lastUpdate)
        assertEquals(write.params.value, saved.value)
    }

    @Test
    fun defaultTransportSendsBlockUpdateAgent() = runTest {
        val client = FakeClient { okBlocks("""{"id":"block-1","label":"human","value":"new"}""") }
        val transport = DefaultAppServerLocalRepositoryTransport({ client }) { it }

        val write = BlockWrite(AgentBlockTarget("agent-1", "human"), BlockUpdateParams(value = "new"))

        transport.updateAgentBlock(write.target, write.params)

        val call = client.adminRpcCalls.single()
        assertEquals("block.update_agent", call.method)
        assertEquals(write.target.label, call.params?.get("label")?.jsonPrimitive?.content)
        assertEquals(write.params.value, call.params?.get("value")?.jsonPrimitive?.content)
    }

    @Test
    fun blockRepositoryCreatesAndDeletesByAgentAndLabel() = runTest {
        val transport = FakeTransport()
        val repository = AppServerAgentBlockRepository(transport)
        val target = AgentBlockTarget("agent-1", "notes")

        val created = repository.createAgentBlock(target, "fresh")
        repository.deleteAgentBlock(target)

        assertEquals(target to "fresh", transport.lastCreate)
        assertEquals("notes", created.label)
        assertEquals("fresh", created.value)
        assertEquals(target, transport.lastDelete)
    }

    @Test
    fun defaultTransportSendsBlockCreateAgentAndDeleteAgent() = runTest {
        val client = FakeClient { okBlocks("""{"id":"block-1","label":"notes","value":"fresh"}""") }
        val transport = DefaultAppServerLocalRepositoryTransport({ client }) { it }
        val target = AgentBlockTarget("agent-1", "notes")

        transport.createAgentBlock(target, "fresh")
        transport.deleteAgentBlock(target)

        val (create, delete) = client.adminRpcCalls
        assertEquals("block.create_agent", create.method)
        assertEquals("agent-1", create.params?.get("agent_id")?.jsonPrimitive?.content)
        assertEquals("notes", create.params?.get("label")?.jsonPrimitive?.content)
        assertEquals("fresh", create.params?.get("value")?.jsonPrimitive?.content)
        assertEquals("block.delete_agent", delete.method)
        assertEquals("notes", delete.params?.get("label")?.jsonPrimitive?.content)
        assertEquals(null, delete.params?.get("value"))
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

        var lastUpdate: BlockWrite? = null

        override suspend fun updateAgentBlock(target: AgentBlockTarget, params: BlockUpdateParams): JsonElement {
            lastUpdate = BlockWrite(target, params)
            return AppServerProtocol.json.encodeToJsonElement(
                Block.serializer(),
                Block(id = BlockId("block-${target.label}"), label = target.label, value = params.value.orEmpty()),
            )
        }

        var lastCreate: Pair<AgentBlockTarget, String>? = null
        var lastDelete: AgentBlockTarget? = null

        override suspend fun createAgentBlock(target: AgentBlockTarget, value: String): JsonElement {
            lastCreate = target to value
            return AppServerProtocol.json.encodeToJsonElement(
                Block.serializer(),
                Block(id = BlockId("block-${target.label}"), label = target.label, value = value),
            )
        }

        override suspend fun deleteAgentBlock(target: AgentBlockTarget) {
            lastDelete = target
        }
    }

    /** One agent-block write as the transport sees it. */
    private data class BlockWrite(val target: AgentBlockTarget, val params: BlockUpdateParams)

    private class FakeClient(
        private val responder: (AppServerCommand.AdminRpc) -> AppServerInboundFrame.AdminRpcResponse,
    ) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        val adminRpcCalls = mutableListOf<AppServerCommand.AdminRpc>()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = unsupported()
        override suspend fun input(command: AppServerCommand.Input): Unit = unsupported()
        override suspend fun sync(command: AppServerCommand.Sync) = unsupported()
        override suspend fun abort(command: AppServerCommand.AbortMessage) = unsupported()
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unsupported()
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse {
            adminRpcCalls += command
            return responder(command)
        }

        private fun unsupported(): Nothing = error("Unexpected App Server operation")
    }

    private fun okBlocks(json: String) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "request",
        success = true,
        result = AppServerProtocol.json.parseToJsonElement(json),
    )
}
