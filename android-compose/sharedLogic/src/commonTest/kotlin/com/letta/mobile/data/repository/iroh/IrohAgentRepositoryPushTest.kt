package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/** Desktop's roster follows Meridian's `agent_updated` pushes, like Android's CachedAgentRepository. */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohAgentRepositoryPushTest {
    private class PushingTransport : NoOpChannelTransport() {
        val pushes = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 8)
        val connection = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Connected("server", "session", "device"))
        val calls = mutableListOf<String>()
        var agents = mutableMapOf("agent-1" to "Old", "agent-2" to "Two")

        override val events = pushes
        override val state = connection

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            calls += "$method $path"
            val result = when (method) {
                "agent.get" -> path.substringAfterLast('/').let { id -> agents[id]?.let { """{"id":"$id","name":"$it"}""" } }
                "agent.list" -> agents.entries.joinToString(",", "[", "]") { """{"id":"${it.key}","name":"${it.value}"}""" }
                else -> null
            }
            return AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = result != null,
                result = result?.let { Json.parseToJsonElement(it) },
                error = if (result == null) "not found" else null,
            )
        }
    }

    private fun push(id: String, reason: String) =
        ServerFrame.AgentUpdated(id = "agent-updated-$id", ts = "2026-09-15T21:00:00Z", agentId = id, reason = reason)

    @Test
    fun anUpdatePushRefetchesOnlyThatAgent() = runTest(UnconfinedTestDispatcher()) {
        val transport = PushingTransport()
        val repository = IrohAgentRepository(directoryProvider = { IrohAdminRpcAgentDirectory(transport) }, transport = transport, scope = backgroundScope)
        repository.refreshAgents()
        transport.calls.clear()

        transport.agents["agent-1"] = "Renamed on another device"
        transport.pushes.emit(push("agent-1", "updated"))

        assertEquals("Renamed on another device", repository.getCachedAgent(AgentId("agent-1"))?.name)
        assertEquals(listOf("agent.get /v1/agents/agent-1"), transport.calls)
    }

    @Test
    fun aDeletePushDropsTheAgentWithoutAFetch() = runTest(UnconfinedTestDispatcher()) {
        val transport = PushingTransport()
        val repository = IrohAgentRepository(directoryProvider = { IrohAdminRpcAgentDirectory(transport) }, transport = transport, scope = backgroundScope)
        repository.refreshAgents()
        transport.calls.clear()

        transport.pushes.emit(push("agent-2", "deleted"))

        assertNull(repository.getCachedAgent(AgentId("agent-2")))
        assertEquals(emptyList(), transport.calls)
    }

    @Test
    fun reconnectingRefreshesTheWholeRosterSincePushesWereMissed() = runTest(UnconfinedTestDispatcher()) {
        val transport = PushingTransport()
        val repository = IrohAgentRepository(directoryProvider = { IrohAdminRpcAgentDirectory(transport) }, transport = transport, scope = backgroundScope)
        repository.refreshAgents()

        transport.connection.value = ChannelTransportState.Disconnected(code = 1006, reason = "network")
        transport.agents["agent-3"] = "Created while offline"
        transport.connection.value = ChannelTransportState.Connected("server", "session", "device")

        assertEquals("Created while offline", repository.getCachedAgent(AgentId("agent-3"))?.name)
    }

    @Test
    fun withoutATransportTheRepositoryStaysOnDemandOnly() = runTest(UnconfinedTestDispatcher()) {
        val transport = PushingTransport()
        val repository = IrohAgentRepository(directoryProvider = { IrohAdminRpcAgentDirectory(transport) })
        repository.refreshAgents()
        transport.calls.clear()

        transport.pushes.emit(push("agent-1", "updated"))

        assertEquals(emptyList(), transport.calls)
    }
}
