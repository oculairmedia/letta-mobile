package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.meridian.MeridianAdmission
import com.letta.mobile.data.meridian.MeridianCommandRouter
import com.letta.mobile.data.meridian.MeridianExit
import com.letta.mobile.data.meridian.MeridianRateLimiter
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.scope
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.toolEnd
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.toolStart
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeridianToolsServiceTest {
    private val tool = CallerEchoTool()
    private val liveCalls = MeridianLiveCalls(nowMs = { 0L })

    private fun service(
        mode: MeridianCallerBindingMode = MeridianCallerBindingMode.LIVE_CALL,
        token: String? = null,
        rateLimiter: MeridianRateLimiter = MeridianRateLimiter.Unlimited,
    ) = MeridianToolsService(
        router = MeridianCommandRouter(ExternalToolRegistry.hostTools(listOf(tool)), rateLimiter = rateLimiter),
        binder = MeridianCallerBinder(mode, liveCalls),
        requiredToken = token,
    )

    private fun request(
        vararg argv: String,
        agent: String? = "agent-a",
        conversation: String? = "conv-a",
        token: String? = null,
        stdin: String? = null,
    ) = MeridianToolsWire.encodeRequest(MeridianToolsWireRequest(argv.toList(), stdin, agent, conversation, token))

    private suspend fun call(service: MeridianToolsService, line: String) = MeridianToolsWire.decodeResponse(service.handle(line))

    @Test
    fun `a call inside a live meridian shell call runs with the App Server's scope and call id`() = runTest {
        liveCalls.observe(toolStart(scope(), "call-1", "meridian canvas list"))

        val first = call(service(), request("meridian", "canvas", "list"))
        val second = call(service(), request("meridian", "canvas", "list"))

        assertEquals(MeridianExit.OK, first.exitCode)
        val echoed = Json.parseToJsonElement(first.stdout).jsonObject
        assertEquals("agent-a", echoed["agent"]?.jsonPrimitive?.content)
        assertEquals("conv-a", echoed["conversation"]?.jsonPrimitive?.content)
        assertEquals("call-1", echoed["call"]?.jsonPrimitive?.content)
        assertEquals("call-1:2", tool.callers.last().toolCallId)
        assertEquals(MeridianExit.OK, second.exitCode)
    }

    @Test
    fun `live-call binding refuses a call with no live meridian shell call`() = runTest {
        val noCall = call(service(), request("canvas", "list"))
        liveCalls.observe(toolStart(scope(conversation = "conv-b"), "call-b", "meridian canvas list"))
        val forgedAgent = call(service(), request("canvas", "list", agent = "agent-evil", conversation = "conv-b"))
        liveCalls.observe(toolEnd(scope(conversation = "conv-b"), "call-b"))
        val afterEnd = call(service(), request("canvas", "list", conversation = "conv-b"))
        val noScope = call(service(), request("canvas", "list", agent = null, conversation = null))

        listOf(noCall, forgedAgent, afterEnd, noScope).forEach {
            assertEquals(MeridianExit.DENIED, it.exitCode)
            assertTrue(it.stdout.contains("\"error\":\"denied\""), it.stdout)
        }
        assertTrue(tool.callers.isEmpty())
    }

    @Test
    fun `env-scoped fallback attributes to the claimed scope without a call id`() = runTest {
        val response = call(service(MeridianCallerBindingMode.ENV_SCOPED), request("canvas", "list"))
        val noScope = call(service(MeridianCallerBindingMode.ENV_SCOPED), request("canvas", "list", agent = null, conversation = null))

        assertEquals(MeridianExit.OK, response.exitCode)
        assertEquals("agent-a", tool.callers.single().agentId)
        kotlin.test.assertNull(tool.callers.single().toolCallId)
        assertEquals(MeridianExit.DENIED, noScope.exitCode)
    }

    @Test
    fun `help is served after binding and needs no tool`() = runTest {
        liveCalls.observe(toolStart(scope(), "call-1", "meridian --help"))

        val help = call(service(), request("meridian", "--help"))

        assertEquals(MeridianExit.OK, help.exitCode)
        assertTrue(help.stdout.contains("canvas"))
    }

    @Test
    fun `the TCP token is required when set`() = runTest {
        liveCalls.observe(toolStart(scope(), "call-1", "meridian canvas list"))

        val missing = call(service(token = "s3cret"), request("canvas", "list"))
        val wrong = call(service(token = "s3cret"), request("canvas", "list", token = "s3cre7"))
        val right = call(service(token = "s3cret"), request("canvas", "list", token = "s3cret"))

        assertEquals(MeridianExit.DENIED, missing.exitCode)
        assertEquals(MeridianExit.DENIED, wrong.exitCode)
        assertEquals(MeridianExit.OK, right.exitCode)
    }

    @Test
    fun `malformed lines and unknown protocols are refused`() = runTest {
        val garbage = call(service(), "not json")
        val emptyArgv = call(service(), """{"argv":[]}""")
        val future = call(service(), """{"argv":["canvas","list"],"protocol":"meridian/tools/9"}""")

        listOf(garbage, emptyArgv, future).forEach { assertEquals(MeridianExit.REFUSED, it.exitCode) }
    }

    @Test
    fun `the router's per-conversation rate limit applies`() = runTest {
        liveCalls.observe(toolStart(scope(), "call-1", "meridian canvas list"))
        val limited = service(rateLimiter = { _, _ -> MeridianAdmission.Limited(retryAfterMs = 500) })

        val response = call(limited, request("canvas", "list"))

        assertEquals(MeridianExit.DENIED, response.exitCode)
        assertTrue(response.stdout.contains("rate_limited"))
    }

    @Test
    fun `only router commands are reachable`() = runTest {
        liveCalls.observe(toolStart(scope(), "call-1", "meridian rest get /v1/agents"))

        val rest = call(service(), request("meridian", "rest", "get", "/v1/agents"))
        val profile = call(service(), request("meridian", "profile", "list"))

        assertEquals(MeridianExit.REFUSED, rest.exitCode)
        assertEquals(MeridianExit.REFUSED, profile.exitCode)
        assertTrue(tool.callers.isEmpty())
    }
}
