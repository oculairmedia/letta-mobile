package com.letta.mobile.data.meridian

import com.letta.mobile.data.controller.extras.ExternalToolCaller
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The error contract (letta-mobile-jna0o.3; design "CLI surface" and "Failure modes"): one JSON
 * object on stdout with a fixed key order, a JSON pointer where the input is at fault, hints only on
 * stderr, and exit 2 refused / 3 denied / 4 host unavailable.
 */
class MeridianErrorsTest {
    private val host = MeridianTestHost()

    private suspend fun run(
        argv: List<String>,
        stdin: String? = null,
        router: MeridianCommandRouter = host.router(),
        caller: ExternalToolCaller = MeridianTestHost.CALLER,
    ): MeridianResponse = router.execute(MeridianRequest(argv, stdin, caller))

    private fun MeridianResponse.json(): JsonObject = Json.parseToJsonElement(stdout).jsonObject

    @Test
    fun anUnknownCommandIsAUsageRefusalWithAHint() = runTest {
        val response = run(listOf("canvas", "draw"))
        assertEquals(MeridianExit.REFUSED, response.exitCode)
        assertEquals("""{"error":"unknown_command","message":"unknown command: canvas draw"}""", response.stdout)
        assertEquals("Run: meridian canvas --help\n", response.stderr)
        assertEquals("""{"error":"unknown_command","message":"unknown command: rest GET /v1/agents"}""", run(listOf("rest", "GET", "/v1/agents")).stdout)
    }

    @Test
    fun noAdminOrRestPassthroughIsReachable() = runTest {
        listOf("rest", "agents list", "conversations", "profile", "blocks", "tools", "app-server-serve-iroh").forEach { word ->
            val response = run(word.split(" "))
            assertEquals(MeridianExit.REFUSED, response.exitCode, word)
            assertEquals("unknown_command", (response.json()["error"] as JsonPrimitive).content, word)
        }
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun badStdinJsonIsRefusedAtTheRootPointer() = runTest {
        val response = run(listOf("canvas", "compose"), stdin = """{"items": [""")
        assertEquals(MeridianExit.REFUSED, response.exitCode)
        val error = response.json()
        assertEquals("invalid_json", (error["error"] as JsonPrimitive).content)
        assertEquals("canvas compose", (error["command"] as JsonPrimitive).content)
        assertEquals("", (error["pointer"] as JsonPrimitive).content)
        assertEquals(listOf("error", "command", "message", "pointer"), error.keys.toList())
        assertTrue(response.stderr.contains("<<'JSON'"), response.stderr)

        val notAnObject = run(listOf("canvas", "compose"), stdin = "[1,2]")
        assertEquals(
            """{"error":"invalid_json","command":"canvas compose","message":"stdin must be one JSON object","pointer":""}""",
            notAnObject.stdout,
        )
        assertTrue(host.calls.isEmpty(), "nothing reached a tool")
    }

    @Test
    fun flagProblemsNameTheirProperty() = runTest {
        assertEquals(
            """{"error":"invalid_input","command":"canvas layout","message":"--limit must be an integer","pointer":"/limit"}""",
            run(listOf("canvas", "layout", "--limit", "many")).stdout,
        )
        assertEquals(
            """{"error":"invalid_input","command":"canvas layout","message":"canvas layout takes no --colour","pointer":"/colour"}""",
            run(listOf("canvas", "layout", "--colour", "red")).stdout,
        )
        assertEquals(
            """{"error":"invalid_input","command":"canvas scene","message":"canvas_id is given both in the stdin JSON and as an argument","pointer":"/canvas_id"}""",
            run(listOf("canvas", "scene", "--canvas", "b"), stdin = """{"canvas_id":"a"}""").stdout,
        )
        assertEquals(
            """{"error":"invalid_input","command":"canvas apply-ops","message":"--ops must be given in the stdin JSON (an array cannot be a flag)","pointer":"/ops"}""",
            run(listOf("canvas", "apply-ops", "--ops", "[]")).stdout,
        )
        assertEquals(
            """{"error":"usage","command":"canvas layout","message":"--cursor needs a value"}""",
            run(listOf("canvas", "layout", "--cursor")).stdout,
        )
        assertEquals(
            """{"error":"usage","command":"canvas scene","message":"unexpected argument 'extra'"}""",
            run(listOf("canvas", "scene", "extra")).stdout,
        )
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun aToolsStructuredRefusalPassesThroughUnderDetail() = runTest {
        val response = run(listOf("canvas", "compose"), stdin = """{"items":[{"kind":"NOTE"}]}""")
        assertEquals(MeridianExit.REFUSED, response.exitCode)
        val error = response.json()
        assertEquals("refused", (error["error"] as JsonPrimitive).content)
        assertEquals("canvas_compose refused the input", (error["message"] as JsonPrimitive).content)
        val detail = assertIs<JsonObject>(error["detail"])
        assertEquals("VALIDATION_FAILED", (detail["code"] as JsonPrimitive).content)
    }

    @Test
    fun aCallWithoutAnAgentIsDenied() = runTest {
        val response = run(listOf("canvas", "scene"), caller = ExternalToolCaller(agentId = null))
        assertEquals(MeridianExit.DENIED, response.exitCode)
        assertEquals("denied", (response.json()["error"] as JsonPrimitive).content)
    }

    @Test
    fun theRateLimitDeniesWithRetryAfterButHelpStaysFree() = runTest {
        var now = 1_000L
        val router = host.router(rateLimiter = PerConversationRateLimiter(maxCalls = 2, windowMs = 60_000L, nowMs = { now }))
        repeat(2) { assertEquals(MeridianExit.OK, run(listOf("canvas", "list"), router = router).exitCode) }
        val limited = run(listOf("canvas", "list"), router = router)
        assertEquals(MeridianExit.DENIED, limited.exitCode)
        assertEquals(
            """{"error":"rate_limited","command":"canvas list","message":"too many meridian calls in this conversation","retry_after_ms":60000}""",
            limited.stdout,
        )
        assertEquals(MeridianExit.OK, run(listOf("canvas", "--help"), router = router).exitCode)
        val other = ExternalToolCaller(MeridianTestHost.AGENT, "conv-other")
        assertEquals(MeridianExit.OK, run(listOf("canvas", "list"), router = router, caller = other).exitCode, "per conversation")
        now += 60_000L
        assertEquals(MeridianExit.OK, run(listOf("canvas", "list"), router = router).exitCode, "a new window")
    }

    @Test
    fun aGroupThisHostDoesNotServeIsUnavailable() = runTest {
        val response = run(listOf("canvas", "compose"), router = MeridianTestHost(withCanvas = false).router())
        assertEquals(MeridianExit.HOST_UNAVAILABLE, response.exitCode)
        assertEquals("""{"error":"host_unavailable","command":"canvas","message":"this host serves no canvas commands"}""", response.stdout)
        val shim = MeridianError.hostUnavailable("connect ECONNREFUSED /run/meridian/tools.sock")
        assertEquals(MeridianExit.HOST_UNAVAILABLE, shim.exitCode)
        assertTrue(shim.stderr.isNotBlank())
    }

    @Test
    fun sizeCapsRefuseInputArgvAndOutput() = runTest {
        val small = host.router(limits = MeridianLimits(maxInputBytes = 16, maxOutputBytes = 64, maxArgs = 4, maxArgBytes = 8))
        assertEquals(
            """{"error":"input_too_large","command":"canvas compose","message":"the input is 46 bytes; at most 16"}""",
            run(listOf("canvas", "compose"), stdin = """{"items":[{"kind":"NOTE","markdown":"Hello"}]}""", router = small).stdout,
        )
        assertEquals(MeridianExit.REFUSED, run(listOf("a", "b", "c", "d", "e"), router = small).exitCode)
        assertEquals("input_too_large", (run(listOf("canvas", "create", "--title", "a-long-title"), router = small).json()["error"] as JsonPrimitive).content)
        val guide = run(listOf("canvas", "guide"), router = small)
        assertEquals(MeridianExit.REFUSED, guide.exitCode)
        assertEquals("output_too_large", (guide.json()["error"] as JsonPrimitive).content)
    }

    @Test
    fun inputFileIsReadByTheFrontDoorAndNeverBesideStdin() = runTest {
        val files = MeridianInputFiles { path -> if (path == "in.json") """{"title":"From file"}""" else null }
        val router = host.router(inputFiles = files)
        assertEquals(MeridianExit.OK, run(listOf("canvas", "create", "--input-file", "in.json"), router = router).exitCode)
        assertEquals("From file", (host.calls.last().input["title"] as JsonPrimitive).content)
        assertEquals(
            """{"error":"usage","command":"canvas create","message":"give the input on stdin or with --input-file, not both"}""",
            run(listOf("canvas", "create", "--input-file", "in.json"), stdin = "{}", router = router).stdout,
        )
        assertEquals("invalid_input", (run(listOf("canvas", "create", "--input-file", "nope"), router = router).json()["error"] as JsonPrimitive).content)
        assertEquals(
            """{"error":"usage","command":"canvas create","message":"--input-file is not available here; pipe the file on stdin"}""",
            run(listOf("canvas", "create", "--input-file", "in.json")).stdout,
        )
    }

    @Test
    fun theArgvSplitterKeepsQuotedWordsAndRefusesAnOpenQuote() {
        assertEquals(listOf("meridian", "canvas", "create", "--title", "My plan's draft"), MeridianArgv.split("""meridian canvas create --title "My plan's draft" """))
        assertEquals(listOf("a b", "c\"d", "e f", ""), MeridianArgv.split("""'a b' "c\"d" e\ f ''"""))
        assertNull(MeridianArgv.split("canvas create --title 'open"))
        assertEquals(emptyList(), MeridianArgv.split("   "))
        assertEquals(listOf("canvas", "list"), MeridianArgv.withoutProgram(listOf("meridian", "canvas", "list")))
    }

    @Test
    fun anUnterminatedQuoteInTheMetaCommandIsAUsageError() = runTest {
        val response = host.router().execute("canvas create --title 'x", null, MeridianTestHost.CALLER)
        assertEquals("""{"error":"usage","message":"the command has an unterminated quote"}""", response.stdout)
        assertEquals(MeridianExit.REFUSED, response.exitCode)
    }
}
