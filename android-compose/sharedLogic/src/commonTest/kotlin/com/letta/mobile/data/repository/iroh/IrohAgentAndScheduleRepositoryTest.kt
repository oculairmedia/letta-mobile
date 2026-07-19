package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class IrohAgentAndScheduleRepositoryTest {

    @Test
    fun refreshSchedulesFallsBackToEmptyListOn404() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohTransport()
        transport.rpcResponder = { call ->
            assertEquals("schedule.list", call.method)
            fail("HTTP 404: agent schedule endpoint not found")
        }
        val repository = IrohScheduleRepository { IrohAdminRpcAgentDirectory(transport) }

        repository.refreshSchedules("agent-1")

        assertEquals(emptyList(), repository.getSchedules("agent-1").first())
    }

    @Test
    fun createScheduleAppendsToCachedSchedules() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohTransport()
        transport.rpcResponder = { call ->
            when (call.method) {
                "schedule.list" -> ok(
                    """
                    {
                      "scheduled_messages": [
                        {
                          "id": "sched-0",
                          "agent_id": "agent-1",
                          "message": {"messages": [{"content": "existing", "role": "user"}]},
                          "schedule": {"type": "once", "scheduled_at": 1.0}
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                "schedule.create" -> ok(
                    """
                    {
                      "id": "sched-1",
                      "agent_id": "agent-1",
                      "message": {"messages": [{"content": "new", "role": "user"}]},
                      "schedule": {"type": "once", "scheduled_at": 2.0}
                    }
                    """.trimIndent(),
                )
                else -> error("unexpected rpc ${call.method}")
            }
        }
        val repository = IrohScheduleRepository { IrohAdminRpcAgentDirectory(transport) }
        repository.refreshSchedules("agent-1")

        repository.createSchedule(
            agentId = "agent-1",
            params = ScheduleCreateParams(
                messages = listOf(ScheduleMessage(content = "new", role = "user")),
                schedule = ScheduleDefinition(type = "once", scheduledAt = 2.0),
            ),
        )

        assertEquals(
            listOf("sched-0", "sched-1"),
            repository.getSchedules("agent-1").first().map { it.id },
        )
    }

    @Test
    fun getAgentUpdatesCache() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohTransport()
        transport.rpcResponder = { call ->
            assertEquals("agent.get", call.method)
            assertEquals("/v1/agents/agent-1", call.path)
            ok("""{"id":"agent-1","name":"Fresh"}""")
        }
        val repository = IrohAgentRepository { IrohAdminRpcAgentDirectory(transport) }

        assertNull(repository.getCachedAgent(AgentId("agent-1")))

        val agent = repository.getAgent(AgentId("agent-1")).first()

        assertEquals("Fresh", agent.name)
        assertEquals("Fresh", repository.getCachedAgent(AgentId("agent-1"))?.name)
    }

    @Test
    fun updateAgentUpsertsCache() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohTransport()
        transport.rpcResponder = { call ->
            when (call.method) {
                "agent.list" -> ok("""[{"id":"agent-1","name":"Original"}]""")
                "agent.update" -> ok("""{"id":"agent-1","name":"Renamed"}""")
                else -> error("unexpected rpc ${call.method}")
            }
        }
        val repository = IrohAgentRepository { IrohAdminRpcAgentDirectory(transport) }
        repository.refreshAgents()
        assertEquals("Original", repository.getCachedAgent(AgentId("agent-1"))?.name)

        val updated = repository.updateAgent(AgentId("agent-1"), AgentUpdateParams(name = "Renamed"))

        assertEquals("Renamed", updated.name)
        assertEquals("Renamed", repository.getCachedAgent(AgentId("agent-1"))?.name)

        transport.rpcResponder = { call ->
            assertEquals("agent.update", call.method)
            ok("""{"id":"agent-2","name":"Inserted"}""")
        }
        val inserted = repository.updateAgent(AgentId("agent-2"), AgentUpdateParams(name = "Inserted"))

        assertEquals("Inserted", inserted.name)
        assertEquals(
            listOf("agent-1", "agent-2"),
            repository.agents.value.map { it.id.value },
        )
        assertEquals("Inserted", repository.getCachedAgent(AgentId("agent-2"))?.name)
    }

    private fun ok(resultJson: String) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "req-1",
        success = true,
        result = Json.parseToJsonElement(resultJson),
    )

    private fun fail(error: String) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "req-1",
        success = false,
        error = error,
    )

    private class FakeIrohTransport : IChannelTransport {
        data class RpcCall(val method: String, val path: String, val body: String?)

        val rpcCalls = mutableListOf<RpcCall>()
        var rpcResponder: (RpcCall) -> AppServerInboundFrame.AdminRpcResponse = { call ->
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = false,
                error = "${call.method} has no responder",
            )
        }

        override val state: StateFlow<ChannelTransportState> =
            MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))
        override val events = MutableSharedFlow<ServerFrame>()
        override val frameEvents = MutableSharedFlow<TransportFrameEvent>()

        override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) = Unit
        override fun send(
            agentId: String,
            conversationId: String,
            text: String,
            otid: String?,
            contentParts: JsonArray?,
            startNewConversation: Boolean,
        ): Boolean = true

        override fun cancel(conversationId: String): Boolean = true
        override fun bye(): Boolean = true
        override suspend fun disconnect() = Unit
        override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = A2uiActionDispatchResult.Sent("frame-1")
        override fun subscribe(runId: String, cursor: Long): Boolean = false
        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            val call = RpcCall(method, path, body)
            rpcCalls += call
            return rpcResponder(call)
        }

        override suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long) = error("unused")
        override suspend fun sendCronAdd(
            agentId: String,
            name: String,
            description: String,
            prompt: String,
            recurring: Boolean,
            cron: String?,
            every: String?,
            at: String?,
            timezone: String?,
            conversationId: String?,
            timeoutMs: Long,
        ) = error("unused")

        override suspend fun sendCronGet(taskId: String, timeoutMs: Long) = error("unused")
        override suspend fun sendCronDelete(taskId: String, timeoutMs: Long) = error("unused")
        override suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long) = error("unused")
        override suspend fun sendSubagentList(all: Boolean, timeoutMs: Long) = error("unused")
        override suspend fun sendSubagentTodos(toolCallId: String, timeoutMs: Long) = error("unused")
    }
}
