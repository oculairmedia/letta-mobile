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
    /** What the shim forwards from the shell env (tool token and claimed ids), plus the TCP token. */
    private data class Claim(
        val toolToken: String? = null,
        val agent: String? = "agent-a",
        val conversation: String? = "conv-a",
        val token: String? = null,
    )

    private val tool = CallerEchoTool()
    private var minted = 0
    private val liveCalls = MeridianLiveCalls(nowMs = { 0L }, newToken = { "tok-${++minted}" })
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
        MeridianToolsWireRequest(
            argv.toList(),
            agentId = claim.agent,
            conversationId = claim.conversation,
            token = claim.token,
            toolToken = claim.toolToken,
        ),
    )

    /** What letta-code's PreToolUse hook does: ask for the call's token, as the shell will get it. */
    private suspend fun issue(
        callId: String,
        via: MeridianToolsService = service(),
        bearer: String? = null,
    ): MeridianToolsWireResponse =
        via.call(MeridianToolsWire.encodeRequest(MeridianToolsWireRequest(issueToolToken = callId, token = bearer)))

    private fun MeridianToolsWireResponse.toolToken(): String =
        Json.parseToJsonElement(stdout).jsonObject.getValue("tool_token").jsonPrimitive.content

    private suspend fun MeridianToolsService.call(line: String) = MeridianToolsWire.decodeResponse(handle(line))

    @Test
    fun aCallWithItsShellCallsTokenRunsWithTheAppServersScopeAndCallId() = runTest {
        liveCalls.observe(started(listCall))
        val token = issue("call-1").toolToken()

        val first = service().call(request("meridian", "canvas", "list", claim = Claim(toolToken = token)))
        val second = service().call(request("meridian", "canvas", "list", claim = Claim(toolToken = token)))

        assertEquals(MeridianExit.OK, first.exitCode)
        val echoed = Json.parseToJsonElement(first.stdout).jsonObject
        assertEquals("agent-a", echoed["agent"]?.jsonPrimitive?.content)
        assertEquals("conv-a", echoed["conversation"]?.jsonPrimitive?.content)
        assertEquals("call-1", echoed["call"]?.jsonPrimitive?.content)
        assertEquals("call-1:2", tool.callers.last().toolCallId)
        assertEquals(MeridianExit.OK, second.exitCode)
    }

    @Test
    fun spoofedIdsWithAMissingOrWrongTokenAreRefused() = runTest {
        // The other conversation has a live meridian call: before by4vm.44, claiming its ids bound to it.
        val otherCall = ShellCall("call-b", "meridian canvas list", scope = OTHER_CONVERSATION)
        liveCalls.observe(started(otherCall))
        issue("call-b").toolToken()
        val spoofed = Claim(agent = "agent-a", conversation = "conv-b")

        val missing = service().call(request("canvas", "list", claim = spoofed))
        val wrong = service().call(request("canvas", "list", claim = spoofed.copy(toolToken = "tok-guess")))
        val noScope = service().call(request("canvas", "list", claim = Claim(agent = null, conversation = null)))

        listOf(missing, wrong, noScope).forEach {
            assertEquals(MeridianExit.DENIED, it.exitCode)
            assertTrue(it.stdout.contains("\"error\":\"denied\""), it.stdout)
        }
        assertTrue(tool.callers.isEmpty())
    }

    @Test
    fun theTokenNotTheEnvIdsDecidesTheScope() = runTest {
        liveCalls.observe(started(listCall))
        val token = issue("call-1").toolToken()

        val response = service().call(
            request("canvas", "list", claim = Claim(toolToken = token, agent = "agent-z", conversation = "conv-z")),
        )

        assertEquals(MeridianExit.OK, response.exitCode)
        assertEquals("agent-a", tool.callers.single().agentId)
        assertEquals("conv-a", tool.callers.single().conversationId)
        assertEquals("call-1", tool.callers.single().toolCallId)
    }

    @Test
    fun aTokenIsDeadOnceItsCallCompletes() = runTest {
        liveCalls.observe(started(listCall))
        val token = issue("call-1").toolToken()
        liveCalls.observe(ended(listCall))

        val reused = service().call(request("canvas", "list", claim = Claim(toolToken = token)))

        assertEquals(MeridianExit.DENIED, reused.exitCode)
        assertTrue(tool.callers.isEmpty())
    }

    @Test
    fun aCallsTokenIsIssuedOnceAndNeverForAnUnknownCall() = runTest {
        liveCalls.observe(started(listCall))

        val first = issue("call-1")
        val again = issue("call-1")
        val unknown = issue("call-nobody")

        assertEquals(MeridianExit.OK, first.exitCode)
        assertEquals(MeridianExit.DENIED, again.exitCode)
        assertEquals(MeridianExit.DENIED, unknown.exitCode)
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
        val token = issue("call-1").toolToken()

        val help = service().call(request("meridian", "--help", claim = Claim(toolToken = token)))

        assertEquals(MeridianExit.OK, help.exitCode)
        assertTrue(help.stdout.contains("canvas"))
    }

    @Test
    fun theTCPTokenIsRequiredWhenSet() = runTest {
        liveCalls.observe(started(listCall))
        val guarded = service(token = "s3cret")
        val unguardedIssue = issue("call-1", via = guarded)
        val toolToken = issue("call-1", via = guarded, bearer = "s3cret").toolToken()

        val missing = guarded.call(request("canvas", "list", claim = Claim(toolToken = toolToken)))
        val wrong = guarded.call(request("canvas", "list", claim = Claim(toolToken = toolToken, token = "s3cre7")))
        val right = guarded.call(request("canvas", "list", claim = Claim(toolToken = toolToken, token = "s3cret")))

        assertEquals(MeridianExit.DENIED, unguardedIssue.exitCode)
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

        val response = limited.call(request("canvas", "list", claim = Claim(toolToken = issue("call-1").toolToken())))

        assertEquals(MeridianExit.DENIED, response.exitCode)
        assertTrue(response.stdout.contains("rate_limited"))
    }

    @Test
    fun onlyRouterCommandsAreReachable() = runTest {
        liveCalls.observe(started(ShellCall("call-1", "meridian rest get /v1/agents")))
        val bound = Claim(toolToken = issue("call-1").toolToken())

        val rest = service().call(request("meridian", "rest", "get", "/v1/agents", claim = bound))
        val profile = service().call(request("meridian", "profile", "list", claim = bound))

        assertEquals(MeridianExit.REFUSED, rest.exitCode)
        assertEquals(MeridianExit.REFUSED, profile.exitCode)
        assertTrue(tool.callers.isEmpty())
    }
}
