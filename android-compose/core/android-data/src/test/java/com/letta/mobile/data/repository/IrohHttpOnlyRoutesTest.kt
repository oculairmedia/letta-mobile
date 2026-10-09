package com.letta.mobile.data.repository

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.api.ToolUnavailableException
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeRunApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.FakeStepApi
import com.letta.mobile.testutil.FakeToolApi
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-xneqn / letta-mobile-ocuii: routes that used to hit HTTP-only endpoints (and so the
 * iroh:// guard) must either ride admin_rpc or degrade without any HTTP call.
 */
class IrohHttpOnlyRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun irohSettings() = FakeSettingsRepository(
        initialActiveConfig = LettaConfig(
            id = "iroh",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "iroh://EndpointTicket",
        ),
    )

    private fun ok(element: kotlinx.serialization.json.JsonElement) =
        AppServerInboundFrame.AdminRpcResponse(requestId = "req", success = true, result = element)

    @Test
    fun `fetchToolsPage under iroh pages over tool list admin rpc without HTTP`() = runTest {
        val catalog = (1..5).map { TestData.tool(id = "t$it") }
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { method, _, body ->
                assertEquals("tool.list", method)
                val request = json.parseToJsonElement(body!!).jsonObject
                val limit = request.getValue("limit").jsonPrimitive.int
                val offset = request.getValue("offset").jsonPrimitive.int
                ok(json.encodeToJsonElement(ListSerializer(Tool.serializer()), catalog.drop(offset).take(limit)))
            }
        }
        val fakeApi = FakeToolApi()
        val repository = ToolRepository(fakeApi, IrohAdminRpcToolSource(transport, irohSettings()))

        val first = repository.fetchToolsPage(limit = 2, offset = 0)
        val second = repository.fetchToolsPage(limit = 2, offset = 2)
        val last = repository.fetchToolsPage(limit = 2, offset = 4)

        assertEquals(listOf("t1", "t2"), first.map { it.id.value })
        assertEquals(listOf("t3", "t4"), second.map { it.id.value })
        assertEquals(listOf("t5"), last.map { it.id.value })
        assertEquals(3, transport.adminRpcCalls.size)
        assertTrue("no HTTP under iroh://", fakeApi.calls.isEmpty())
    }

    @Test
    fun `getTool under iroh resolves over tool get admin rpc without HTTP`() = runTest {
        val tool = TestData.tool(id = "t7", name = "seven")
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { method, _, body ->
                assertEquals("tool.get", method)
                assertEquals("\"t7\"", json.parseToJsonElement(body!!).jsonObject.getValue("tool_id").jsonPrimitive.toString())
                ok(json.encodeToJsonElement(Tool.serializer(), tool))
            }
        }
        val fakeApi = FakeToolApi()
        val repository = ToolRepository(fakeApi, IrohAdminRpcToolSource(transport, irohSettings()))

        assertEquals("seven", repository.getTool("t7").name)
        assertTrue("no HTTP under iroh://", fakeApi.calls.isEmpty())
    }

    @Test
    fun `getTool under iroh reports not found for an unknown id`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                AppServerInboundFrame.AdminRpcResponse(requestId = "req", success = false, error = "tool nope not found")
            }
        }
        val fakeApi = FakeToolApi()
        val repository = ToolRepository(fakeApi, IrohAdminRpcToolSource(transport, irohSettings()))

        val e = assertThrows(ToolUnavailableException::class.java) { runBlockingUnit { repository.getTool("nope") } }
        assertEquals(ToolUnavailableException.Reason.NOT_FOUND, e.reason)
        assertTrue(fakeApi.calls.isEmpty())
    }

    @Test
    fun `getTool under iroh falls back to the tool list catalog when tool get is unavailable`() = runTest {
        val catalog = listOf(TestData.tool(id = "a"), TestData.tool(id = "b"))
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { method, _, _ ->
                when (method) {
                    "tool.get" -> AppServerInboundFrame.AdminRpcResponse(
                        requestId = "req", success = false, error = "capability_unavailable: tool.get",
                    )
                    "tool.list" -> ok(json.encodeToJsonElement(ListSerializer(Tool.serializer()), catalog))
                    else -> error("unexpected $method")
                }
            }
        }
        val fakeApi = FakeToolApi()
        val repository = ToolRepository(fakeApi, IrohAdminRpcToolSource(transport, irohSettings()))

        assertEquals("b", repository.getTool("b").id.value)
        val e = assertThrows(ToolUnavailableException::class.java) { runBlockingUnit { repository.getTool("zzz") } }
        assertEquals(ToolUnavailableException.Reason.NOT_FOUND, e.reason)
        assertTrue(fakeApi.calls.isEmpty())
    }

    @Test
    fun `getTool on an HTTP backend still reads over HTTP`() = runTest {
        val fakeApi = FakeToolApi().apply { tools.add(TestData.tool(id = "h1")) }
        val transport = FakeChannelTransport()
        val httpSettings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(id = "http", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://letta.example.com"),
        )
        val repository = ToolRepository(fakeApi, IrohAdminRpcToolSource(transport, httpSettings))

        assertEquals("h1", repository.getTool("h1").id.value)
        assertEquals(listOf("getTool:h1"), fakeApi.calls)
        assertTrue(transport.adminRpcCalls.isEmpty())
    }

    @Test
    fun `fetchToolsPage on an HTTP backend still pages over HTTP`() = runTest {
        val fakeApi = FakeToolApi()
        val transport = FakeChannelTransport()
        val httpSettings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "http",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "https://letta.example.com",
            ),
        )
        val repository = ToolRepository(fakeApi, IrohAdminRpcToolSource(transport, httpSettings))

        repository.fetchToolsPage(limit = 10, offset = 0)

        assertEquals(listOf("listTools"), fakeApi.calls)
        assertTrue(transport.adminRpcCalls.isEmpty())
    }

    @Test
    fun `run list and run steps work under iroh while run detail is gated off without HTTP`() = runTest {
        val runs = listOf(Run(id = "r1", agentId = "a1", status = "completed"))
        val steps = listOf(FakeStepApi().sampleStep("s1").copy(runId = "r1"))
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { method, _, _ ->
                when (method) {
                    "run.list" -> ok(json.encodeToJsonElement(ListSerializer(Run.serializer()), runs))
                    "step.list" -> ok(json.encodeToJsonElement(ListSerializer(Step.serializer()), steps))
                    else -> error("unexpected admin_rpc $method")
                }
            }
        }
        val fakeApi = FakeRunApi()
        val repository = RunRepository(fakeApi, IrohAdminRpcRunSource(transport, irohSettings()))

        assertFalse(repository.supportsRunDetail)
        repository.refreshRuns()
        assertEquals(listOf("r1"), repository.runs.value.map { it.id })
        assertEquals(listOf("s1"), repository.getRunSteps("r1").map { it.id })

        assertThrows(UnsupportedOperationException::class.java) { runBlockingUnit { repository.getRunMessages("r1") } }
        assertThrows(UnsupportedOperationException::class.java) { runBlockingUnit { repository.getRunUsage("r1") } }
        assertThrows(UnsupportedOperationException::class.java) { runBlockingUnit { repository.getRunMetrics("r1") } }
        assertThrows(UnsupportedOperationException::class.java) { runBlockingUnit { repository.cancelRun(runs.first()) } }
        assertThrows(UnsupportedOperationException::class.java) { runBlockingUnit { repository.deleteRun("r1") } }

        assertEquals(listOf("run.list", "step.list"), transport.adminRpcCalls.map { it.method })
        assertTrue("no HTTP under iroh://: ${fakeApi.calls}", fakeApi.calls.isEmpty())
    }

    @Test
    fun `run detail stays on HTTP for http backends`() = runTest {
        val httpSettings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "http",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "https://letta.example.com",
            ),
        )
        val fakeApi = FakeRunApi()
        fakeApi.runs.add(Run(id = "r1", agentId = "a1", status = "completed"))
        val repository = RunRepository(fakeApi, IrohAdminRpcRunSource(FakeChannelTransport(), httpSettings))

        assertTrue(repository.supportsRunDetail)
        repository.getRunMessages("r1")
        assertTrue(fakeApi.calls.contains("listRunMessages:r1"))
    }

    @Test
    fun `step queries under iroh degrade to empty or unsupported without HTTP`() = runTest {
        val fakeApi = FakeStepApi()
        fakeApi.steps.add(fakeApi.sampleStep("s1"))
        val repository = StepRepository(fakeApi, isIrohBackend = { true })

        assertFalse(repository.supportsStepQueries)
        assertEquals(emptyList<Step>(), repository.listSteps(StepListParams(limit = 10)))
        repository.refreshSteps()
        assertEquals(emptyList<Step>(), repository.steps.value)
        assertThrows(UnsupportedOperationException::class.java) { runBlockingUnit { repository.getStep("s1") } }
        assertThrows(UnsupportedOperationException::class.java) { runBlockingUnit { repository.getStepMetrics("s1") } }
        assertThrows(UnsupportedOperationException::class.java) { runBlockingUnit { repository.getStepTrace("s1") } }
        assertThrows(UnsupportedOperationException::class.java) { runBlockingUnit { repository.getStepMessages("s1") } }
        assertThrows(UnsupportedOperationException::class.java) {
            runBlockingUnit { repository.updateStepFeedback("s1", StepFeedbackUpdateParams(feedback = "positive")) }
        }
        assertTrue("no HTTP under iroh://: ${fakeApi.calls}", fakeApi.calls.isEmpty())
    }

    @Test
    fun `step queries stay on HTTP when the backend is not iroh`() = runTest {
        val fakeApi = FakeStepApi()
        fakeApi.steps.add(fakeApi.sampleStep("s1"))
        val repository = StepRepository(fakeApi)

        assertTrue(repository.supportsStepQueries)
        assertEquals(1, repository.listSteps().size)
        assertEquals(listOf("listSteps"), fakeApi.calls)
    }

    private fun runBlockingUnit(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}
