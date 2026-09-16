package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Wire shapes added with letta-code 0.32: input acknowledgement, turn_finished and change_device_state. */
class AppServerProtocol032Test {
    @Test
    fun inputRequestIdAndNewCreateMessageFieldsEncodeOnlyWhenSet() {
        val plain = AppServerProtocol.json.parseToJsonElement(
            AppServerProtocol.encodeCommand(
                AppServerCommand.Input(runtime, AppServerInputPayload.CreateMessage(listOf(AppServerInputMessage.userText("hi")))),
            ),
        ).jsonObject
        assertNull(plain["request_id"], "an input without request_id must stay byte-compatible with older servers")
        assertNull(plain["payload"]?.jsonObject?.get("exclude_interactive_tools"))

        val acked = AppServerProtocol.json.parseToJsonElement(
            AppServerProtocol.encodeCommand(
                AppServerCommand.Input(
                    runtime = runtime,
                    payload = AppServerInputPayload.CreateMessage(
                        messages = listOf(AppServerInputMessage.userText("hi")),
                        imageFailureMode = "drop",
                        excludeInteractiveTools = true,
                    ),
                    requestId = "input-1",
                ),
            ),
        ).jsonObject
        assertEquals("input-1", acked["request_id"]?.jsonPrimitive?.content)
        assertEquals("drop", acked["payload"]?.jsonObject?.get("image_failure_mode")?.jsonPrimitive?.content)
        assertEquals(true, acked["payload"]?.jsonObject?.get("exclude_interactive_tools")?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun encodesChangeDeviceStateNotTheLegacyModeOrCwdCommands() {
        val root = AppServerProtocol.json.parseToJsonElement(
            AppServerProtocol.encodeCommand(
                AppServerCommand.ChangeDeviceState(runtime, AppServerDeviceStatePayload(mode = AppServerPermissionMode.AcceptEdits, cwd = "/work")),
            ),
        ).jsonObject

        assertEquals("change_device_state", root["type"]?.jsonPrimitive?.content)
        assertEquals("acceptEdits", root["payload"]?.jsonObject?.get("mode")?.jsonPrimitive?.content)
        assertEquals("/work", root["payload"]?.jsonObject?.get("cwd")?.jsonPrimitive?.content)
        assertFalse("agent_id" in root["payload"]!!.jsonObject, "unset fields must not be sent: only sent fields change")
    }

    @Test
    fun decodesInputAcceptedAndTurnFinishedAsTypedControlFrames() {
        val accepted = AppServerProtocol.decodeFrame(
            """{"type":"input_accepted","request_id":"input-4","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"accepted":true,"disposition":"started"}""",
        )
        assertEquals(AppServerChannel.Control, accepted.channel)
        val ack = assertIs<AppServerInboundFrame.InputAccepted>(accepted.frame)
        assertEquals("input-4", ack.requestId)
        assertFalse(ack.queued)

        val finished = AppServerProtocol.decodeFrame(
            """{"type":"turn_finished","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"event_seq":51,"emitted_at":"2026-09-15T20:30:00.000Z","idempotency_key":"turn_finished:51","turn_id":"batch-1","stop_reason":"end_turn","run_id":"local-run-3006","usage":{"total_tokens":10},"future_field":1}""",
        )
        assertEquals(AppServerChannel.Control, finished.channel)
        val turn = assertIs<AppServerInboundFrame.TurnFinished>(finished.frame)
        assertEquals("end_turn", turn.stopReason)
        assertEquals("local-run-3006", turn.runId)
        assertEquals(1, finished.raw["future_field"]?.jsonPrimitive?.content?.toInt(), "additive fields stay on the raw envelope")
    }

    private companion object {
        val runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-1")
    }
}
