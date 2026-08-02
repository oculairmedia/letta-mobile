package com.letta.mobile.data.repository

import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleListResponse
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.testutil.FakeScheduleApi
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ScheduleRepositoryTest {

    private lateinit var fakeApi: FakeScheduleApi
    private lateinit var repository: ScheduleRepository

    @Before
    fun setup() {
        fakeApi = FakeScheduleApi()
        repository = ScheduleRepository(fakeApi)
    }

    @Test
    fun `refreshSchedules updates agent flow`() = runTest {
        fakeApi.schedules["a1"] = mutableListOf(sampleScheduledMessage())

        repository.refreshSchedules("a1")

        assertEquals(1, repository.getSchedules("a1").first().size)
    }

    @Test
    fun `refreshSchedules handles empty scheduled messages successfully`() = runTest {
        fakeApi.schedules["a1"] = mutableListOf()

        repository.refreshSchedules("a1")

        assertTrue(repository.getSchedules("a1").first().isEmpty())
        assertTrue(fakeApi.calls.contains("listSchedules:a1"))
    }

    @Test
    fun `createSchedule refreshes repository cache`() = runTest {
        repository.createSchedule(
            agentId = "a1",
            params = ScheduleCreateParams(
                messages = listOf(ScheduleMessage(content = "hello", role = "user")),
                schedule = ScheduleDefinition(type = "one-time", scheduledAt = 1_700_000_000.0),
            )
        )

        assertTrue(fakeApi.calls.contains("createSchedule:a1"))
        assertEquals(1, repository.getSchedules("a1").first().size)
    }

    @Test
    fun `deleteSchedule removes from cache`() = runTest {
        fakeApi.schedules["a1"] = mutableListOf(sampleScheduledMessage())
        repository.refreshSchedules("a1")

        repository.deleteSchedule("a1", "s1")

        assertTrue(fakeApi.calls.contains("deleteSchedule:a1:s1"))
        assertTrue(repository.getSchedules("a1").first().isEmpty())
    }

    @Test
    fun `getSchedule retrieves single scheduled message`() = runTest {
        fakeApi.schedules["a1"] = mutableListOf(sampleScheduledMessage())

        val result = repository.getSchedule("a1", "s1")

        assertEquals("s1", result.id)
        assertEquals("one-time", result.schedule.type)
    }

    // ─── Iroh Purity Tests (letta-mobile client batch) ────────────────────────

    @Test(expected = com.letta.mobile.data.api.IrohAdminApiUnavailableException::class)
    fun `refreshSchedules in iroh mode without source throws IrohAdminApiUnavailableException`() = runTest {
        val apiThatThrows = object : FakeScheduleApi() {
            override suspend fun listSchedules(agentId: String, limit: Int?, after: String?): com.letta.mobile.data.model.ScheduleListResponse {
                throw com.letta.mobile.data.api.IrohAdminApiUnavailableException("Raw HTTP forbidden in iroh:// mode")
            }
        }
        val repo = ScheduleRepository(apiThatThrows)
        repo.refreshSchedules("a1")
    }

    @Test
    fun `getSchedule in iroh mode routes via admin_rpc`() = runTest {
        val settings = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(
                id = "test", mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t"
            )
        )
        val transport = com.letta.mobile.testutil.FakeChannelTransport()
        val expected = sampleScheduledMessage()
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("schedule.get", method)
            assertEquals("/v1/schedules/s1", path)
            assertEquals("{\"schedule_id\":\"s1\",\"agent_id\":\"a1\"}", body)
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(ScheduledMessage.serializer(), expected),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcScheduleSource(transport, settings)
        val apiThatThrows = object : FakeScheduleApi() {
            override suspend fun retrieveSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage {
                throw com.letta.mobile.data.api.IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = ScheduleRepository(apiThatThrows, irohSource)

        val result = repo.getSchedule("a1", "s1")

        assertEquals(expected, result)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `refreshSchedules in iroh mode routes via admin_rpc`() = runTest {
        val settings = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(
                id = "test", mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t"
            )
        )
        val transport = com.letta.mobile.testutil.FakeChannelTransport()
        val testSchedules = listOf(sampleScheduledMessage())
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("schedule.list", method)
            assertEquals("/v1/agents/a1/schedule", path)
            assertEquals("{\"agent_id\":\"a1\"}", body)
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                requestId = "req", success = true,
                // Matches ScheduleAdminHandlers.page's production envelope.
                result = json.encodeToJsonElement(
                    ScheduleListResponse.serializer(),
                    ScheduleListResponse(hasNextPage = false, scheduledMessages = testSchedules),
                ),
                error = null
            )
        }
        val irohSource = IrohAdminRpcScheduleSource(transport, settings)
        val apiThatThrows = object : FakeScheduleApi() {
            override suspend fun listSchedules(agentId: String, limit: Int?, after: String?): com.letta.mobile.data.model.ScheduleListResponse {
                throw com.letta.mobile.data.api.IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = ScheduleRepository(apiThatThrows, irohSource)
        repo.refreshSchedules("a1")
        assertEquals(1, repo.getSchedules("a1").first().size)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `refreshSchedules accepts legacy bare array result in iroh mode`() = runTest {
        val settings = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(
                id = "test", mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t"
            )
        )
        val transport = com.letta.mobile.testutil.FakeChannelTransport()
        val testSchedules = listOf(sampleScheduledMessage())
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("schedule.list", method)
            assertEquals("/v1/agents/a1/schedule", path)
            assertEquals("{\"agent_id\":\"a1\"}", body)
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(ScheduledMessage.serializer()),
                    testSchedules,
                ),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcScheduleSource(transport, settings)
        val apiThatThrows = object : FakeScheduleApi() {
            override suspend fun listSchedules(agentId: String, limit: Int?, after: String?): ScheduleListResponse {
                throw AssertionError("Raw HTTP must not be called in iroh mode")
            }
        }
        val repo = ScheduleRepository(apiThatThrows, irohSource)

        repo.refreshSchedules("a1")

        assertEquals(testSchedules, repo.getSchedules("a1").first())
    }

    @Test
    fun `refreshSchedules excludes foreign agent schedules and emits warning`() = runTest {
        val settings = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(
                id = "test", mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t"
            )
        )
        val transport = com.letta.mobile.testutil.FakeChannelTransport()
        val schedules = listOf(sampleScheduledMessage(), sampleScheduledMessage(id = "s2", agentId = "a2"))
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("schedule.list", method)
            assertEquals("/v1/agents/a1/schedule", path)
            assertEquals("{\"agent_id\":\"a1\"}", body)
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(
                    ScheduleListResponse.serializer(),
                    ScheduleListResponse(hasNextPage = false, scheduledMessages = schedules),
                ),
                error = null,
            )
        }
        val apiThatThrows = object : FakeScheduleApi() {
            override suspend fun listSchedules(agentId: String, limit: Int?, after: String?): ScheduleListResponse {
                throw AssertionError("Raw HTTP must not be called in iroh mode")
            }
        }
        val previousLogcatEnabled = Telemetry.logcatEnabled.get()
        try {
            Telemetry.clear()
            Telemetry.logcatEnabled.set(false)
            val repo = ScheduleRepository(apiThatThrows, IrohAdminRpcScheduleSource(transport, settings))

            repo.refreshSchedules("a1")

            assertEquals(listOf("s1"), repo.getSchedules("a1").first().map { it.id })
            assertTrue(repo.getSchedules("a2").first().isEmpty())
            val warning = Telemetry.snapshot().single { it.name == "scheduleList.scopeMismatch" }
            assertEquals(Telemetry.Level.WARN, warning.level)
            assertEquals("a1", warning.attrs["requestedAgentId"])
            assertEquals(1, warning.attrs["excludedCount"])
        } finally {
            Telemetry.clear()
            Telemetry.logcatEnabled.set(previousLogcatEnabled)
        }
    }

    @Test
    fun `createSchedule in iroh mode routes via admin_rpc`() = runTest {
        val settings = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(id = "test", mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t")
        )
        val transport = com.letta.mobile.testutil.FakeChannelTransport()
        val created = sampleScheduledMessage()
        transport.adminRpcHandler = { method, _, _ ->
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            if (method == "schedule.create") {
                com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                    requestId = "req", success = true, result = json.encodeToJsonElement(ScheduledMessage.serializer(), created), error = null
                )
            } else {
                com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                    requestId = "req",
                    success = true,
                    result = json.encodeToJsonElement(
                        ScheduleListResponse.serializer(),
                        ScheduleListResponse(hasNextPage = false, scheduledMessages = listOf(created)),
                    ),
                    error = null
                )
            }
        }
        val irohSource = IrohAdminRpcScheduleSource(transport, settings)
        val apiThatThrows = object : FakeScheduleApi() {
            override suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage {
                throw com.letta.mobile.data.api.IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
            override suspend fun listSchedules(agentId: String, limit: Int?, after: String?): com.letta.mobile.data.model.ScheduleListResponse {
                throw com.letta.mobile.data.api.IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = ScheduleRepository(apiThatThrows, irohSource)
        val result = repo.createSchedule("a1", ScheduleCreateParams(messages = listOf(ScheduleMessage(content = "hello", role = "user")), schedule = ScheduleDefinition(type = "one-time", scheduledAt = 1_700_000_000.0)))
        assertEquals("s1", result.id)
    }

    @Test
    fun `deleteSchedule in iroh mode routes via admin_rpc`() = runTest {
        val settings = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(id = "test", mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t")
        )
        val transport = com.letta.mobile.testutil.FakeChannelTransport()
        transport.adminRpcHandler = { method, path, _ ->
            assertEquals("schedule.delete", method)
            assertEquals("/v1/schedules/s1", path)
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(requestId = "req", success = true, result = kotlinx.serialization.json.JsonNull, error = null)
        }
        val irohSource = IrohAdminRpcScheduleSource(transport, settings)
        val apiThatThrows = object : FakeScheduleApi() {
            override suspend fun deleteSchedule(agentId: String, scheduledMessageId: String) {
                throw com.letta.mobile.data.api.IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = ScheduleRepository(apiThatThrows, irohSource)
        repo.deleteSchedule("a1", "s1")
        assertEquals(1, transport.adminRpcCalls.size)
    }

    private fun sampleScheduledMessage(
        id: String = "s1",
        agentId: String = "a1",
    ) = ScheduledMessage(
        id = id,
        agentId = agentId,
        message = SchedulePayload(
            messages = listOf(ScheduleMessage(content = "hello", role = "user"))
        ),
        schedule = ScheduleDefinition(type = "one-time", scheduledAt = 1_700_000_000.0),
        nextScheduledTime = "2026-04-09T10:00:00Z",
    )
}
