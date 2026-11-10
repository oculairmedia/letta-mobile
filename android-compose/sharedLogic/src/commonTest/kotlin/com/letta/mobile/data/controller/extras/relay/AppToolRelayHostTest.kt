package com.letta.mobile.data.controller.extras.relay

import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The host's relay: a call is forwarded to the app viewing the caller's conversation and return its
 * answer, and fail fast and clearly when no app can answer, the app is too slow, or it disconnects.
 */
class AppToolRelayHostTest {
    private val scope = AppToolCallScope(agentId = "agent-a", conversationId = "conv-a", toolCallId = "tc-1")
    private val input = buildJsonObject { put("action", "get") }
    private var nextId = 0
    private val host = AppToolRelayHost(setOf(TOOL), timeout = 5.seconds, newCallId = { AppToolCallId("call-${++nextId}") })
    private val viewing = mutableMapOf<String, List<AppToolRelayLink>>()

    init {
        host.bindViewers { conversation -> viewing[conversation].orEmpty() }
    }

    /** One app connection as the host sees it: the frames sent to it, and whether it negotiated the relay. */
    private inner class FakeApp(var ready: Boolean = true, var reachable: Boolean = true) {
        val sent = mutableListOf<JsonObject>()
        val link: AppToolRelayLink = host.link(
            send = { frame -> reachable.also { if (it) sent += Json.parseToJsonElement(frame).jsonObject } },
            ready = { ready },
        )

        fun answer(result: AppToolCallResult, callId: AppToolCallId = lastCallId()): Boolean =
            link.receive(Json.parseToJsonElement(AppToolRelayFrames.encode(AppToolCallResponse(callId, result))).jsonObject)

        fun lastCallId(): AppToolCallId = AppToolRelayFrames.decodeRequest(sent.last())!!.callId
    }

    private fun TestScope.callAsync() = async { host.call(TOOL, input, scope) }

    private fun assertNoApp(result: AppToolCallResult) {
        assertTrue(result.isError && result.text.startsWith("No app is showing this conversation"), result.text)
    }

    @Test
    fun aCallIsForwardedWithTheStampedScopeAndReturnsTheAppsAnswer() = runTest {
        val app = FakeApp()
        viewing["conv-a"] = listOf(app.link)

        val call = callAsync()
        runCurrent()
        val forwarded = AppToolRelayFrames.decodeRequest(app.sent.single())!!
        assertEquals(TOOL, forwarded.toolName)
        assertEquals(input, forwarded.input)
        assertEquals(scope, forwarded.scope)
        assertTrue(app.answer(AppToolCallResult("""{"applied":true}""")))

        assertEquals(AppToolCallResult("""{"applied":true}"""), call.await())
    }

    @Test
    fun theAppsErrorIsReturnedAsIs() = runTest {
        val app = FakeApp()
        viewing["conv-a"] = listOf(app.link)

        val call = callAsync()
        runCurrent()
        app.answer(AppToolCallResult("""{"error":"no_shell"}""", isError = true))

        assertEquals(AppToolCallResult("""{"error":"no_shell"}""", isError = true), call.await())
    }

    @Test
    fun withNoAppViewingTheConversationTheCallFailsAtOnce() = runTest {
        assertNoApp(host.call(TOOL, input, scope))
    }

    @Test
    fun aCallWithoutAConversationScopeFailsAtOnce() = runTest {
        viewing["conv-a"] = listOf(FakeApp().link)

        assertNoApp(host.call(TOOL, input, AppToolCallScope(agentId = "agent-a")))
    }

    @Test
    fun anOlderAppThatDidNotNegotiateTheRelayIsNeverSentACall() = runTest {
        val older = FakeApp(ready = false)
        viewing["conv-a"] = listOf(older.link)

        assertNoApp(host.call(TOOL, input, scope))
        assertTrue(older.sent.isEmpty())
    }

    @Test
    fun anAppThatDoesNotAnswerTimesOutAndItsLateAnswerIsIgnored() = runTest {
        val app = FakeApp()
        viewing["conv-a"] = listOf(app.link)

        val call = callAsync()
        runCurrent()
        advanceTimeBy(5.seconds + 1.seconds)

        val result = call.await()
        assertTrue(result.isError && "did not answer shell_layout within 5s" in result.text, result.text)
        assertFalse(app.answer(AppToolCallResult("late")), "a late answer finds no waiting call")
    }

    @Test
    fun anAppThatDisconnectsMidCallFailsItAndTakesNoMoreCalls() = runTest {
        val app = FakeApp()
        viewing["conv-a"] = listOf(app.link)

        val call = callAsync()
        runCurrent()
        app.link.close()

        val result = call.await()
        assertTrue(result.isError && "disconnected" in result.text, result.text)
        assertFalse(app.link.acceptsCalls)
        assertNoApp(host.call(TOOL, input, scope))
    }

    @Test
    fun aDuplicateAnswerIsIgnored() = runTest {
        val app = FakeApp()
        viewing["conv-a"] = listOf(app.link)

        val call = callAsync()
        runCurrent()
        val callId = app.lastCallId()
        assertTrue(app.answer(AppToolCallResult("first"), callId))
        assertFalse(app.answer(AppToolCallResult("second"), callId))

        assertEquals(AppToolCallResult("first"), call.await())
    }

    @Test
    fun theNewestViewerGetsTheCallAndAnOlderOneOnlyWhenTheNewerCannotBeReached() = runTest {
        val older = FakeApp()
        val newer = FakeApp(reachable = false)
        viewing["conv-a"] = listOf(older.link, newer.link)

        val call = callAsync()
        runCurrent()
        assertTrue(newer.sent.isEmpty())
        older.answer(AppToolCallResult("from older"))

        assertEquals(AppToolCallResult("from older"), call.await())
    }

    @Test
    fun aToolOffTheAllowListIsNeverRelayed() = runTest {
        val app = FakeApp()
        viewing["conv-a"] = listOf(app.link)

        val result = host.call("device_action", input, scope)

        assertEquals(AppToolCallResult("device_action is not relayed to the app.", isError = true), result)
        assertTrue(app.sent.isEmpty())
    }

    private companion object {
        const val TOOL = "shell_layout"
    }
}
