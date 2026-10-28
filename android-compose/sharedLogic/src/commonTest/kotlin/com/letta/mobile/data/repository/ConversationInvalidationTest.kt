package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.util.AgentConversationFleet
import com.letta.mobile.util.AgentConversationFleetPaging
import com.letta.mobile.util.AgentConversationReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationInvalidationTest {
    private val a = AgentId("agent-a")
    private val b = AgentId("agent-b")
    private val roster get() = listOf(Agent(a, name = "A"), Agent(b, name = "B"))
    private fun row(id: String, agent: AgentId = a) = Conversation(ConversationId(id), agent, summary = id)
    private fun push(id: String, agent: AgentId? = a) = ServerFrame.ConversationUpdated(
        id = "push-$id", ts = "2026-10-28T00:00:00Z", conversationId = id, agentId = agent?.value,
    )

    @Test
    fun cachedListScopesPushesCoalescesBurstsAndReconcilesOnReconnect() = runTest {
        val remote = Backend(listOf(row("a"), row("b", b)))
        val transport = remote
        var rosterCalls = 0
        val repository = CachedAllConversationsRepository(
            roster = { rosterCalls++; roster }, remote = RemoteRoute.Absent(SessionRoute.CONVERSATIONS), repositoryScope = backgroundScope,
            irohConversationListSource = remote.source(), transport = transport,
        )
        runCurrent()
        repository.refresh()
        remote.requests.clear()
        remote.rows = listOf(row("a").copy(summary = "updated"), row("b", b))
        transport.events.emit(push("a", agent = null)) // legacy pushes can still use the single-row refetch
        runCurrent()
        assertEquals(listOf(a), remote.requests)
        assertEquals("updated", repository.conversations.value.first { it.id == ConversationId("a") }.summary)
        assertEquals(1, rosterCalls)
        remote.requests.clear()
        repeat(10) { transport.events.tryEmit(push("new-$it")) }
        runCurrent()
        assertEquals(listOf(a), remote.requests)
        transport.state.value = ChannelTransportState.Disconnected(1006, "offline")
        runCurrent()
        remote.requests.clear()
        transport.state.value = ChannelTransportState.Connected("server", "session", "device")
        runCurrent()
        assertEquals(setOf(a, b), remote.requests.toSet())
        assertEquals(2, remote.requests.size)
    }

    @Test
    fun unknownOwnerWithoutTargetedReadReconcilesTheFleet() = runTest {
        val remote = Backend(listOf(row("a"), row("b", b)))
        val transport = remote
        remote.supportsGet = false
        val repository = CachedAllConversationsRepository(
            roster = { roster }, remote = RemoteRoute.Absent(SessionRoute.CONVERSATIONS), repositoryScope = backgroundScope,
            irohConversationListSource = remote.source(), transport = transport,
        )
        runCurrent()
        repository.refresh()
        remote.requests.clear()
        transport.events.emit(push("unknown", agent = null))
        runCurrent()
        assertEquals(setOf(a, b), remote.requests.toSet())
        assertEquals(2, remote.requests.size)
    }

    @Test
    fun pushDuringAReadRemainsDirtyAndRefreshesOnlyThatAgentAgain() = runTest {
        val updates = MutableSharedFlow<Pair<ConversationId, AgentId?>>(extraBufferCapacity = 16)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val batches = mutableListOf<Map<ConversationId, AgentId?>>()
        backgroundScope.launch {
            observeConversationInvalidations(updates) { batch ->
                batches += batch
                if (batches.size == 1) { entered.complete(Unit); release.await() }
            }
        }
        runCurrent()
        updates.emit(ConversationId("a") to a)
        runCurrent()
        entered.await()
        repeat(10) { updates.tryEmit(ConversationId("a") to a) }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(List(2) { mapOf<ConversationId, AgentId?>(ConversationId("a") to a) }, batches)
    }

    @Test
    fun scopedReadPreservesLoadedTailsAndOtherAgentsCursors() = runTest {
        var rows = listOf(row("a1"), row("a2"), row("a3"), row("b1", b), row("b2", b), row("b3", b))
        val reads = mutableListOf<Pair<AgentId, ConversationId?>>()
        val fleet = AgentConversationFleet(AgentConversationReader { agent, after, limit ->
            reads += agent to after
            val own = rows.filter { it.agentId == agent }
            own.drop(after?.let { id -> own.indexOfFirst { it.id == id } + 1 } ?: 0).take(limit)
        }, AgentConversationFleetPaging(pageSize = 2))
        fleet.refresh(roster)
        fleet.loadMore()
        reads.clear()
        rows = listOf(row("new")) + rows
        val listed = fleet.refreshChanged(mapOf(ConversationId("new") to a))
        assertEquals(listOf<Pair<AgentId, ConversationId?>>(a to null), reads)
        assertEquals(rows.map { it.id }.toSet(), listed?.map { it.id }?.toSet())
        assertEquals(false, fleet.hasMore)
    }

    @Test
    fun failedTargetedReadDoesNotDispatchToUnchangedAgents() = runTest {
        val reads = mutableListOf<AgentId>()
        var fail = false
        val fleet = AgentConversationFleet(AgentConversationReader { agent, _, _ ->
            reads += agent
            check(!fail) { "transient" }
            listOf(row(agent.value, agent))
        })
        fleet.refresh(roster)
        reads.clear()
        fail = true
        assertFailsWith<IllegalStateException> { fleet.refreshChanged(mapOf(ConversationId("a") to a)) }
        assertEquals(listOf(a), reads)
        assertEquals(2, fleet.conversations.size)
    }

    @Test
    fun unsupportedTargetedReadRequestsReconciliation() = runTest {
        val fleet = AgentConversationFleet(AgentConversationReader { _, _, _ -> throw UnsupportedOperationException() })
        assertNull(fleet.refreshChanged(mapOf(ConversationId("a") to a)))
    }

    private class Backend(var rows: List<Conversation>) : NoOpChannelTransport() {
        override val events = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 32)
        override val state = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Connected("server", "session", "device"))
        val requests = mutableListOf<AgentId>()
        var supportsGet = true
        fun source() = IrohAdminRpcConversationListSource(
            this,
            ActiveConfigSettingsRepository(LettaConfig(id = "test", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://fake")),
        )
        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            val result = when (method) {
                "conversation.list" -> {
                    val params = LettaJson.wire.parseToJsonElement(body.orEmpty()).jsonObject
                    val agent = AgentId(params.getValue("agent_id").jsonPrimitive.content)
                    requests += agent
                    LettaJson.wire.encodeToJsonElement(rows.filter { it.agentId == agent })
                }
                "conversation.get" -> {
                    if (!supportsGet) throw UnsupportedOperationException("targeted retrieval unavailable")
                    val row = rows.first { it.id.value == path.substringAfterLast('/') }
                    requests += row.agentId
                    LettaJson.wire.encodeToJsonElement(row)
                }
                else -> error("unexpected $method")
            }
            return AppServerInboundFrame.AdminRpcResponse("request", success = true, result = result)
        }
    }
}
