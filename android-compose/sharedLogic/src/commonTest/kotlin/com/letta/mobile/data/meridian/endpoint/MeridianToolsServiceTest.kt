package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.meridian.MeridianAdmission
import com.letta.mobile.data.meridian.MeridianCommandRouter
import com.letta.mobile.data.meridian.MeridianExit
import com.letta.mobile.data.meridian.MeridianRateLimiter
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.OTHER_CONVERSATION
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.ended
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.started
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeridianToolsServiceTest {
    /** What the shim forwards from the shell env, plus the TCP token. */
    private data class Claim(val agent: String? = "agent-a", val conversation: String? = "conv-a", val token: String? = null)

    private val tool = CallerEchoTool()
    private val liveCalls = MeridianLiveCalls(nowMs = { 0L })
    private val listCall = ShellCall("call-1", "meridian canvas list")

    private fun service(
        mode: MeridianCallerBindingMode = MeridianCallerBindingMode.LIVE_CALL,
        token: String? = null,
        rateLimiter: MeridianRateLimiter = MeridianRateLimiter.Unlimited,
    ) = MeridianToolsService(
        router = MeridianCommandRouter(ExternalToolRegistry.hostTools(listOf(tool)), rateLimiter = rateLimiter),
        binder = MeridianCallerBinder(mode, liveCalls),
        requiredToken = token,
    )

    private fun request(vararg argv: String, claim: Claim = Claim()) = MeridianToolsWire.encodeRequest(
        MeridianToolsWireRequest(argv.toList(), agentId = claim.agent, conversationId = claim.conversation, token = claim.token),
    )

    private suspend fun MeridianToolsService.call(line: String) = MeridianToolsWire.decodeResponse(handle(line))

    @Test
    fun aCallInsideALiveMeridianShellCallRunsWithTheAppServersScopeAndCallId() = runTest {
        liveCalls.observe(started(listCall))

        val first = service().call(request("meridian", "canvas", "list"))
        val second = service().call(request("meridian", "canvas", "list"))

        assertEquals(MeridianExit.OK, first.exitCode)
        val echoed = Json.parseToJsonElement(first.stdout).jsonObject
        assertEquals("agent-a", echoed["agent"]?.jsonPrimitive?.content)
        assertEquals("conv-a", echoed["conversation"]?.jsonPrimitive?.content)
        assertEquals("call-1", echoed["call"]?.jsonPrimitive?.content)
        assertEquals("call-1:2", tool.callers.last().toolCallId)
        assertEquals(MeridianExit.OK, second.exitCode)
    }

    @Test
    fun liveCallBindingRefusesACallWithNoLiveMeridianShellCall() = runTest {
        val otherCall = ShellCall("call-b", "meridian canvas list", scope = OTHER_CONVERSATION)
        val noCall = service().call(request("canvas", "list"))
        liveCalls.observe(started(otherCall))
        val forgedAgent = service().call(request("canvas", "list", claim = Claim(agent = "agent-evil", conversation = "conv-b")))
        liveCalls.observe(ended(otherCall))
        val afterEnd = service().call(request("canvas", "list", claim = Claim(conversation = "conv-b")))
        val noScope = service().call(request("canvas", "list", claim = Claim(agent = null, conversation = null)))

        listOf(noCall, forgedAgent, afterEnd, noScope).forEach {
            assertEquals(MeridianExit.DENIED, it.exitCode)
            assertTrue(it.stdout.contains("\"error\":\"denied\""), it.stdout)
        }
        assertTrue(tool.callers.isEmpty())
    }

    @Test
    fun envScopedFallbackAttributesToTheClaimedScopeWithoutACallId() = runTest {
        val envScoped = service(MeridianCallerBindingMode.ENV_SCOPED)

        val response = envScoped.call(request("canvas", "list"))
        val noScope = envScoped.call(request("canvas", "list", claim = Claim(agent = null, conversation = null)))

        assertEquals(MeridianExit.OK, response.exitCode)
        assertEquals("agent-a", tool.callers.single().agentId)
        assertNull(tool.callers.single().toolCallId)
        assertEquals(MeridianExit.DENIED, noScope.exitCode)
    }

    @Test
    fun helpIsServedAfterBindingAndNeedsNoTool() = runTest {
        liveCalls.observe(started(ShellCall("call-1", "meridian --help")))

        val help = service().call(request("meridian", "--help"))

        assertEquals(MeridianExit.OK, help.exitCode)
        assertTrue(help.stdout.contains("canvas"))
    }

    @Test
    fun theTCPTokenIsRequiredWhenSet() = runTest {
        liveCalls.observe(started(listCall))
        val guarded = service(token = "s3cret")

        val missing = guarded.call(request("canvas", "list"))
        val wrong = guarded.call(request("canvas", "list", claim = Claim(token = "s3cre7")))
        val right = guarded.call(request("canvas", "list", claim = Claim(token = "s3cret")))

        assertEquals(MeridianExit.DENIED, missing.exitCode)
        assertEquals(MeridianExit.DENIED, wrong.exitCode)
        assertEquals(MeridianExit.OK, right.exitCode)
    }

    @Test
    fun malformedLinesAndUnknownProtocolsAreRefused() = runTest {
        val garbage = service().call("not json")
        val emptyArgv = service().call("""{"argv":[]}""")
        val future = service().call("""{"argv":["canvas","list"],"protocol":"meridian/tools/9"}""")

        listOf(garbage, emptyArgv, future).forEach { assertEquals(MeridianExit.REFUSED, it.exitCode) }
    }

    @Test
    fun theRoutersPerConversationRateLimitApplies() = runTest {
        liveCalls.observe(started(listCall))
        val limited = service(rateLimiter = { _, _ -> MeridianAdmission.Limited(retryAfterMs = 500) })

        val response = limited.call(request("canvas", "list"))

        assertEquals(MeridianExit.DENIED, response.exitCode)
        assertTrue(response.stdout.contains("rate_limited"))
    }

    @Test
    fun onlyRouterCommandsAreReachable() = runTest {
        liveCalls.observe(started(ShellCall("call-1", "meridian rest get /v1/agents")))

        val rest = service().call(request("meridian", "rest", "get", "/v1/agents"))
        val profile = service().call(request("meridian", "profile", "list"))

        assertEquals(MeridianExit.REFUSED, rest.exitCode)
        assertEquals(MeridianExit.REFUSED, profile.exitCode)
        assertTrue(tool.callers.isEmpty())
    }
}
