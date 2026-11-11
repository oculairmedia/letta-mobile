package com.letta.mobile.data.controller.extras.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The relay frames: they round-trip, tolerate fields a later peer adds, and reject what is not theirs. */
class AppToolRelayFramesTest {
    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val request = AppToolCallRequest(
        callId = AppToolCallId("call-1"),
        toolName = "shell_layout",
        input = buildJsonObject { put("action", "get") },
        scope = AppToolCallScope(agentId = "agent-a", conversationId = "conv-a", toolCallId = "tc-1"),
    )

    @Test
    fun aRequestRoundTripsWithItsScope() {
        val frame = parse(AppToolRelayFrames.encode(request))

        assertEquals(AppToolRelay.REQUEST_TYPE, AppToolRelayFrames.typeOf(frame))
        assertEquals(request, AppToolRelayFrames.decodeRequest(frame))
    }

    @Test
    fun aRequestWithoutAScopeKeepsItNull() {
        val bare = request.copy(scope = AppToolCallScope())

        assertEquals(bare, AppToolRelayFrames.decodeRequest(parse(AppToolRelayFrames.encode(bare))))
    }

    @Test
    fun successAndErrorResponsesRoundTrip() {
        val success = AppToolCallResponse(AppToolCallId("call-1"), AppToolCallResult("""{"applied":true}"""))
        val error = AppToolCallResponse(AppToolCallId("call-2"), AppToolCallResult("no shell", isError = true))

        assertEquals(success, AppToolRelayFrames.decodeResponse(parse(AppToolRelayFrames.encode(success))))
        assertEquals(error, AppToolRelayFrames.decodeResponse(parse(AppToolRelayFrames.encode(error))))
    }

    @Test
    fun fieldsALaterPeerAddsAreIgnored() {
        val newerRequest = parse(
            """{"type":"app_tool_call_request","call_id":"call-1","tool_name":"shell_layout","input":{"action":"get"},""" +
                """"agent_id":"agent-a","conversation_id":"conv-a","tool_call_id":"tc-1","priority":3,"trace":{"x":1}}""",
        )
        val newerResponse = parse("""{"type":"app_tool_call_response","call_id":"call-1","success":true,"content":"ok","took_ms":4}""")

        assertEquals(request, AppToolRelayFrames.decodeRequest(newerRequest))
        assertEquals(AppToolCallResult("ok"), AppToolRelayFrames.decodeResponse(newerResponse)?.result)
    }

    @Test
    fun framesOfAnotherTypeOrWithoutACallIdAreNotRelayFrames() {
        assertNull(AppToolRelayFrames.decodeRequest(parse("""{"type":"app_tool_call_response","call_id":"call-1"}""")))
        assertNull(AppToolRelayFrames.decodeResponse(parse("""{"type":"app_tool_call_request","call_id":"call-1"}""")))
        assertNull(AppToolRelayFrames.decodeRequest(parse("""{"type":"app_tool_call_request","tool_name":"shell_layout"}""")))
        assertNull(AppToolRelayFrames.decodeResponse(parse("""{"type":"app_tool_call_response","call_id":"  ","success":true}""")))
        assertNull(AppToolRelayFrames.typeOf(parse("""{"call_id":"call-1"}""")))
    }

    @Test
    fun aRequestMissingItsToolOrInputStillDecodesSoTheAppCanRefuseItById() {
        val decoded = AppToolRelayFrames.decodeRequest(parse("""{"type":"app_tool_call_request","call_id":"call-9","input":"nope"}"""))

        assertEquals(AppToolCallId("call-9"), decoded?.callId)
        assertEquals("", decoded?.toolName)
        assertEquals(JsonObject(emptyMap()), decoded?.input)
    }

    @Test
    fun aResponseWithoutAResultIsAnError() {
        val noContent = parse("""{"type":"app_tool_call_response","call_id":"call-1","success":true}""")
        val notBoolean = parse("""{"type":"app_tool_call_response","call_id":"call-1","success":"yes","content":"ok"}""")

        val noResult = AppToolCallResult("The app answered without a result.", isError = true)
        assertEquals(noResult, AppToolRelayFrames.decodeResponse(noContent)?.result)
        assertEquals(noResult, AppToolRelayFrames.decodeResponse(notBoolean)?.result)
    }
}
