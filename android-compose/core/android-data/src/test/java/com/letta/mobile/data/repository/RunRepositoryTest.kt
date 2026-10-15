package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound

import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.runtime.RunId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeRunApi
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class RunRepositoryTest {

    private lateinit var fakeApi: FakeRunApi
    private lateinit var repository: CachedRunRepository

    @Before
    fun setup() {
        fakeApi = FakeRunApi()
        repository = CachedRunRepository(fakeApi.bound())
    }

    @Test
    fun `refreshRuns calls API with correct params`() = runTest {
        fakeApi.runs.add(Run(id = "r1", agentId = "a1", status = "completed"))
        repository.refreshRuns(RunListParams(agentId = "a1", limit = 10))
        assertEquals(1, fakeApi.calls.size)
        assertEquals("a1", fakeApi.lastListParams?.agentId)
    }

    @Test
    fun `getRun returns correct run`() = runTest {
        val testRun = Run(id = "r1", agentId = "a1", status = "completed")
        fakeApi.runs.add(testRun)
        val result = repository.getRun(RunId("r1"))
        assertEquals("r1", result.id)
        assertEquals("a1", result.agentId)
    }

    @Test
    fun `getRunSteps returns steps for run`() = runTest {
        val testStep = Step(
            id = "s1",
            origin = "sdk",
            providerId = "p1",
            runId = "r1",
            agentId = "a1",
            providerName = "OpenAI",
            providerCategory = "llm",
            model = "gpt-4",
            modelEndpoint = "https://api.openai.com/v1",
            contextWindowLimit = 8000,
            promptTokens = 10,
            completionTokens = 20,
            totalTokens = 30,
            status = "completed",
        )
        fakeApi.runSteps["r1"] = listOf(testStep)
        val result = repository.getRunSteps(RunId("r1"))
        assertEquals(1, result.size)
        assertEquals("s1", result[0].id)
    }

    // ─── Iroh Purity Tests (letta-mobile client batch) ────────────────────────

    @Test
    fun `refreshRuns in iroh mode without source throws AppServerRouteUnavailable`() = runTest {
        val failure = runCatching {
            val settings = FakeSettingsRepository(
                initialActiveConfig = LettaConfig(
                    id = "test",
                    mode = LettaConfig.Mode.SELF_HOSTED,
                    serverUrl = "iroh://test-node",
                    accessToken = "token",
                )
            )
            val apiThatThrows = object : FakeRunApi() {
                override suspend fun listRuns(params: RunListParams): List<Run> {
                    throw AppServerRouteUnavailable(SessionRoute.RUNS, "Raw HTTP forbidden in iroh:// mode")
                }
            }
            val repo = CachedRunRepository(apiThatThrows.bound())
            repo.refreshRuns(RunListParams())
        }.exceptionOrNull()
        org.junit.Assert.assertTrue("expected AppServerRouteUnavailable, got $failure", failure is AppServerRouteUnavailable)
    }

    @Test
    fun `refreshRuns in iroh mode routes via admin_rpc with exact method, path and body`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val transport = FakeChannelTransport()
        val testRuns = listOf(
            Run(id = "r1", agentId = "a1", status = "completed"),
            Run(id = "r2", agentId = "a2", status = "running"),
        )
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("run.list", method)
            assertEquals("/v1/runs", path)
            assertEquals(
                "{\"agent_id\":\"a1\",\"active\":true,\"limit\":10,\"order\":\"desc\"}",
                body,
            )
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(ListSerializer(Run.serializer()), testRuns),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcRunSource(transport, settings)
        val apiThatThrows = object : FakeRunApi() {
            override suspend fun listRuns(params: RunListParams): List<Run> {
                throw AppServerRouteUnavailable(SessionRoute.RUNS, "Raw HTTP forbidden")
            }
        }
        val repo = CachedRunRepository(apiThatThrows.bound(), irohSource)
        repo.refreshRuns(RunListParams(agentId = "a1", active = true, limit = 10, order = "desc"))
        assertEquals(2, repo.runs.value.size)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `getRecentRuns in iroh mode routes via admin_rpc with order=desc, order_by=created_at`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val transport = FakeChannelTransport()
        val testRuns = listOf(Run(id = "r1", agentId = "a1", status = "completed"))
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("run.list", method)
            assertEquals("/v1/runs", path)
            assertEquals(
                "{\"limit\":5,\"order\":\"desc\",\"order_by\":\"created_at\"}",
                body,
            )
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(ListSerializer(Run.serializer()), testRuns),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcRunSource(transport, settings)
        val apiThatThrows = object : FakeRunApi() {
            override suspend fun listRuns(params: RunListParams): List<Run> {
                throw AppServerRouteUnavailable(SessionRoute.RUNS, "Raw HTTP forbidden in iroh:// mode")
            }
        }
        val repo = CachedRunRepository(apiThatThrows.bound(), irohSource)
        val result = repo.getRecentRuns(limit = 5)
        assertEquals(1, result.size)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `getRecentRuns in iroh mode without source throws AppServerRouteUnavailable`() = runTest {
        val failure = runCatching {
            val apiThatThrows = object : FakeRunApi() {
                override suspend fun listRuns(params: RunListParams): List<Run> {
                    throw AppServerRouteUnavailable(SessionRoute.RUNS, "Raw HTTP forbidden in iroh:// mode")
                }
            }
            val repo = CachedRunRepository(apiThatThrows.bound())
            repo.getRecentRuns(limit = 5)
        }.exceptionOrNull()
        org.junit.Assert.assertTrue("expected AppServerRouteUnavailable, got $failure", failure is AppServerRouteUnavailable)
    }

    @Test
    fun `getRun in iroh mode routes via admin_rpc`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val transport = FakeChannelTransport()
        val testRun = Run(id = "r1", agentId = "a1", status = "completed")
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("run.get", method)
            assertEquals("/v1/runs/r1", path)
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(Run.serializer(), testRun),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcRunSource(transport, settings)
        val apiThatThrows = object : FakeRunApi() {
            override suspend fun retrieveRun(runId: String): Run {
                throw AppServerRouteUnavailable(SessionRoute.RUNS, "Raw HTTP forbidden")
            }
        }
        val repo = CachedRunRepository(apiThatThrows.bound(), irohSource)
        val result = repo.getRun(RunId("r1"))
        assertEquals("r1", result.id)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `getRunSteps in iroh mode routes via admin_rpc`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val transport = FakeChannelTransport()
        val testSteps = listOf(
            Step(
                id = "s1",
                origin = "sdk",
                providerId = "p1",
                runId = "r1",
                agentId = "a1",
                providerName = "OpenAI",
                providerCategory = "llm",
                model = "gpt-4",
                modelEndpoint = "https://api.openai.com/v1",
                contextWindowLimit = 8000,
                promptTokens = 10,
                completionTokens = 20,
                totalTokens = 30,
                status = "completed",
            )
        )
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("step.list", method)
            assertEquals("/v1/runs/r1/steps", path)
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(ListSerializer(Step.serializer()), testSteps),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcRunSource(transport, settings)
        val apiThatThrows = object : FakeRunApi() {
            override suspend fun listRunSteps(
                runId: String,
                before: String?,
                after: String?,
                limit: Int?,
                order: String?,
            ): List<Step> {
                throw AppServerRouteUnavailable(SessionRoute.RUNS, "Raw HTTP forbidden")
            }
        }
        val repo = CachedRunRepository(apiThatThrows.bound(), irohSource)
        val result = repo.getRunSteps(RunId("r1"))
        assertEquals(1, result.size)
        assertEquals("s1", result[0].id)
        assertEquals(1, transport.adminRpcCalls.size)
    }
}
