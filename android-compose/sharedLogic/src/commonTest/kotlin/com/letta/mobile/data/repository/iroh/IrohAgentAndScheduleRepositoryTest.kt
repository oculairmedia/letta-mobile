package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.util.Telemetry
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun refreshSchedulesDecodesProductionEnvelopeAtAgentScopedEndpoint() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport()
        transport.rpcResponder = { call ->
            assertEquals("schedule.list", call.method)
            assertEquals("/v1/agents/agent-1/schedule", call.path)
            assertEquals("{\"agent_id\":\"agent-1\"}", call.body)
            ok(scheduleEnvelope(scheduleJson("sched-1", "agent-1")))
        }
        val repository = IrohScheduleRepository { IrohAdminRpcAgentDirectory(transport) }

        repository.refreshSchedules("agent-1")

        assertEquals(listOf("sched-1"), repository.getSchedules("agent-1").first().map { it.id })
    }

    @Test
    fun refreshSchedulesAcceptsLegacyBareArray() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport()
        transport.rpcResponder = { ok("[${scheduleJson("sched-1", "agent-1")}]") }
        val repository = IrohScheduleRepository { IrohAdminRpcAgentDirectory(transport) }

        repository.refreshSchedules("agent-1")

        assertEquals(listOf("sched-1"), repository.getSchedules("agent-1").first().map { it.id })
    }

    @Test
    fun refreshSchedulesFiltersForeignRowsAndWarnsWithoutPayload() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport()
        transport.rpcResponder = {
            ok(scheduleEnvelope(scheduleJson("sched-1", "agent-1"), scheduleJson("secret-sched", "agent-2")))
        }
        val repository = IrohScheduleRepository { IrohAdminRpcAgentDirectory(transport) }
        val previousLogcatEnabled = Telemetry.logcatEnabled.get()
        try {
            Telemetry.clear()
            Telemetry.logcatEnabled.set(false)

            repository.refreshSchedules("agent-1")

            assertEquals(listOf("sched-1"), repository.getSchedules("agent-1").first().map { it.id })
            val warning = Telemetry.snapshot().single { it.name == "scheduleList.scopeMismatch" }
            assertEquals(Telemetry.Level.WARN, warning.level)
            assertEquals("agent-1", warning.attrs["requestedAgentId"])
            assertEquals(1, warning.attrs["excludedCount"])
            assertTrue(warning.attrs.keys.none { it.contains("url", ignoreCase = true) || it.contains("payload", ignoreCase = true) })
            assertTrue(warning.attrs.values.none { it.toString().contains("secret-sched") })
        } finally {
            Telemetry.clear()
            Telemetry.logcatEnabled.set(previousLogcatEnabled)
        }
    }

    @Test
    fun refreshSchedulesKeepsMissingMalformedAndFailedResponsesLoud() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport()
        val repository = IrohScheduleRepository { IrohAdminRpcAgentDirectory(transport) }

        transport.rpcResponder = {
            AppServerInboundFrame.AdminRpcResponse(requestId = "req-1", success = true, result = null)
        }
        assertFailsWith<Throwable> { repository.refreshSchedules("agent-1") }

        transport.rpcResponder = { ok("{\"unexpected\":true}") }
        assertFailsWith<Throwable> { repository.refreshSchedules("agent-1") }

        transport.rpcResponder = { fail("schedule unavailable") }
        assertFailsWith<Throwable> { repository.refreshSchedules("agent-1") }
    }

    @Test
    fun refreshSchedulesPreservesLegitimateEmptyResults() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport()
        val repository = IrohScheduleRepository { IrohAdminRpcAgentDirectory(transport) }
        for (result in listOf("{\"scheduled_messages\":[]}", "[]")) {
            transport.rpcResponder = { ok(result) }
            repository.refreshSchedules("agent-1")
            assertEquals(emptyList(), repository.getSchedules("agent-1").first())
        }
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
    fun getAgentPropagatesCancellationInsteadOfReturningCachedAgent() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport()
        transport.rpcResponder = { call ->
            when (call.method) {
                "agent.list" -> ok("""[{"id":"agent-1","name":"Cached"}]""")
                "agent.get" -> throw CancellationException("cancelled")
                else -> error("unexpected rpc ${call.method}")
            }
        }
        val repository = IrohAgentRepository { IrohAdminRpcAgentDirectory(transport) }
        repository.refreshAgents()

        kotlin.test.assertFailsWith<CancellationException> {
            repository.getAgent(AgentId("agent-1")).first()
        }
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

    private fun scheduleEnvelope(vararg schedules: String): String =
        "{\"has_next_page\":false,\"scheduled_messages\":[${schedules.joinToString()}]}"

    private fun scheduleJson(id: String, agentId: String): String =
        """{"id":"$id","agent_id":"$agentId","message":{"messages":[{"content":"hello","role":"user"}]},"schedule":{"type":"once","scheduled_at":1.0}}"""

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
