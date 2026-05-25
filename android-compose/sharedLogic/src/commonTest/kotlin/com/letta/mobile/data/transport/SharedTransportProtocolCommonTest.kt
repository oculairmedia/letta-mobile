package com.letta.mobile.data.transport

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.a2ui.LETTA_TOOL_APPROVAL_WIDGET_ID
import com.letta.mobile.data.model.AssistantMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class SharedTransportProtocolCommonTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun helloFrameKeepsA2uiCapabilitiesTopLevel() {
        val frame = HelloFrame(
            id = "frame-1",
            ts = "2026-05-25T12:00:00Z",
            token = "secret",
            deviceId = "device-1",
            clientVersion = "letta-mobile/test",
        )

        val encoded = frame.encodeJson(json)

        assertTrue(encoded.contains("\"type\":\"hello\""))
        assertTrue(encoded.contains("\"a2ui_version\":\"0.9\""))
        assertTrue(encoded.contains("\"supported_catalogs\""))
        assertTrue(encoded.contains("\"supported_widgets\""))
        assertTrue(encoded.contains(LETTA_TOOL_APPROVAL_WIDGET_ID))
        assertTrue(!encoded.contains("\"a2ui_capability\""))
    }

    @Test
    fun unknownServerFrameRemainsRawAndForwardCompatible() {
        val payload = """{"v":1,"type":"future_frame","id":"frame-2","ts":"2026-05-25T12:00:01Z","extra":42}"""

        val parsed = json.decodeFromString(ServerFrameSerializer, payload)

        val unknown = assertIs<ServerFrame.Unknown>(parsed)
        assertEquals("future_frame", unknown.type)
        assertEquals("42", unknown.raw["extra"]?.jsonPrimitive?.content)
    }

    @Test
    fun wsFrameMapperProjectsAssistantFramesToLettaMessages() {
        val frame = ServerFrame.AssistantMessage(
            id = "cm-stream-1",
            ts = "2026-05-25T12:00:02Z",
            agentId = "agent-1",
            conversationId = "conversation-1",
            turnId = "turn-1",
            runId = "run-1",
            content = "hello",
            otid = "client-1",
        )

        val mapped = assertIs<AssistantMessage>(WsFrameMapper.toLettaMessage(frame))

        assertEquals("cm-stream-1", mapped.id)
        assertEquals("hello", mapped.content)
        assertEquals("client-1", mapped.otid)
        assertEquals("run-1", mapped.runId)
    }

    @Test
    fun a2uiActionCarriesRawDispatchPayload() {
        val action = A2uiAction(
            name = "tool.approve",
            surfaceId = "surface-1",
            context = JsonObject(mapOf("approvalId" to JsonPrimitive("approval-1"))),
            runId = "run-1",
            turnId = "turn-1",
            actionId = "action-1",
        )

        assertEquals("tool.approve", action.raw["name"]?.jsonPrimitive?.content)
        assertEquals("surface-1", action.raw["surfaceId"]?.jsonPrimitive?.content)
        assertEquals("run-1", action.raw["runId"]?.jsonPrimitive?.content)
        assertEquals("turn-1", action.raw["turnId"]?.jsonPrimitive?.content)
        assertEquals("action-1", action.raw["actionId"]?.jsonPrimitive?.content)
    }
}
