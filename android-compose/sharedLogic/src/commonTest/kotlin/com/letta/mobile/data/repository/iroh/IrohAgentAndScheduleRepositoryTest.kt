package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class IrohAgentAndScheduleRepositoryTest {

    @Test
    fun refreshSchedulesFallsBackToEmptyListOn404() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport()
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
        val transport = FakeIrohAdminTransport()
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
        val transport = FakeIrohAdminTransport()
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
        val transport = FakeIrohAdminTransport()
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

}
