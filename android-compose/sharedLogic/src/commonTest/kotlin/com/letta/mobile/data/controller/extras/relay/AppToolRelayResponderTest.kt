package com.letta.mobile.data.controller.extras.relay

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's side of the frames: a relayed call goes to the app's answerer and its answer goes back by call id. */
class AppToolRelayResponderTest {
    private val scope = AppToolCallScope(agentId = "agent-a", conversationId = "conv-a", toolCallId = "tc-1")
    private val input = buildJsonObject { put("action", "get") }
    private val installs = mutableListOf<AutoCloseable>()

    @AfterTest
    fun uninstall() {
        installs.asReversed().forEach { it.close() }
    }

    private fun request(toolName: String = "shell_layout") = Json.parseToJsonElement(
        AppToolRelayFrames.encode(AppToolCallRequest(AppToolCallId("call-1"), toolName, input, scope)),
    ).jsonObject

    private suspend fun AppToolRelayResponder.answerOf(frame: JsonObject): AppToolCallResponse =
        AppToolRelayFrames.decodeResponse(Json.parseToJsonElement(answer(frame)!!).jsonObject)!!

    @Test
    fun aRelayedCallReachesTheAnswererWithTheHostsScopeAndItsAnswerKeepsTheCallId() = runTest {
        val received = mutableListOf<AppToolCallRequest>()
        val responder = AppToolRelayResponder { AppToolRelayAnswerer { received += it; AppToolCallResult("ran ${it.toolName}") } }

        val response = responder.answerOf(request())

        assertEquals(AppToolCallResponse(AppToolCallId("call-1"), AppToolCallResult("ran shell_layout")), response)
        assertEquals(listOf(AppToolCallRequest(AppToolCallId("call-1"), "shell_layout", input, scope)), received)
    }

    @Test
    fun withNoAnswererTheCallIsRefusedByCallId() = runTest {
        val response = AppToolRelayResponder { null }.answerOf(request())

        assertEquals(AppToolCallId("call-1"), response.callId)
        assertEquals(AppToolCallResult("This app does not run shell_layout for its host.", isError = true), response.result)
    }

    @Test
    fun onlyRelayRequestsAreTakenAndOnesWithoutACallIdGetNoAnswer() = runTest {
        val responder = AppToolRelayResponder { AppToolRelayAnswerer { AppToolCallResult("ran") } }
        val noId = Json.parseToJsonElement("""{"type":"app_tool_call_request","tool_name":"shell_layout"}""").jsonObject

        assertTrue(responder.isRequest(request()))
        assertFalse(responder.isRequest(Json.parseToJsonElement("""{"type":"auth_response"}""").jsonObject))
        assertNull(responder.answer(noId))
    }

    @Test
    fun theDefaultAnswersWithWhatTheAppInstalledUntilItIsClosed() = runTest {
        val first = AppToolRelayAnswerer { AppToolCallResult("first") }
        val second = AppToolRelayAnswerer { AppToolCallResult("second") }

        installs += AppToolRelayResponder.install(first)
        val secondInstall = AppToolRelayResponder.install(second).also { installs += it }
        assertEquals(AppToolCallResult("second"), AppToolRelayResponder.Default.answerOf(request()).result)

        secondInstall.close()
        assertEquals(AppToolCallResult("first"), AppToolRelayResponder.Default.answerOf(request()).result)
    }
}
