package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IrohStreamDeltaServerFrameMapperTest {
    @Test
    fun mapsAssistantStreamDeltaWithoutFlatteningEnvelopeMetadata() {
        val frames = map(
            """
            {
              "type": "stream_delta",
              "runtime": {"agent_id": "agent-stream", "conversation_id": "conv-stream"},
              "event_seq": 7,
              "emitted_at": "2026-07-02T00:00:00Z",
              "idempotency_key": "evt-7",
              "delta": {
                "id": "letta-msg-1",
                "message_type": "assistant_message",
                "text": "pong",
                "run_id": "run-app"
              }
            }
            """.trimIndent(),
        )

        val frame = assertIs<ServerFrame.AssistantMessage>(frames.single())
        assertEquals("letta-msg-1", frame.id)
        assertEquals("pong", frame.content)
        assertEquals("agent-stream", frame.agentId)
        assertEquals("conv-stream", frame.conversationId)
        assertEquals("turn-fallback", frame.turnId)
        assertEquals("run-app", frame.runId)
        assertEquals(7L, frame.seq)
        assertEquals(7, frame.seqId)
        assertEquals("2026-07-02T00:00:00Z", frame.ts)
    }

    @Test
    fun mapsReasoningToolCallAndToolReturnDeltasToTypedFrames() {
        val reasoning = assertIs<ServerFrame.ReasoningMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": 1,
                  "idempotency_key": "evt-reasoning",
                  "delta": {
                    "id": "reasoning-1",
                    "message_type": "reasoning_message",
                    "reasoning": "thinking",
                    "run_id": "run-app"
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        assertEquals("thinking", reasoning.reasoning)
        assertEquals("run-app", reasoning.runId)

        val toolCall = assertIs<ServerFrame.ToolCallMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": 2,
                  "idempotency_key": "evt-tool",
                  "delta": {
                    "id": "tool-msg-1",
                    "message_type": "tool_call_message",
                    "run_id": "run-app",
                    "tool_call": {
                      "id": "call-1",
                      "function": {
                        "name": "search",
                        "arguments": {"q": "iroh"}
                      }
                    }
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        assertEquals("tool-msg-1", toolCall.id)
        assertEquals("call-1", toolCall.toolCall?.toolCallId)
        assertEquals("search", toolCall.toolCall?.name)
        assertEquals("""{"q":"iroh"}""", toolCall.toolCall?.arguments)

        val toolReturn = assertIs<ServerFrame.ToolReturnMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": 3,
                  "idempotency_key": "evt-return",
                  "delta": {
                    "id": "return-msg-1",
                    "message_type": "tool_return_message",
                    "run_id": "run-app",
                    "tool_call_id": "call-1",
                    "status": "success",
                    "tool_return": "ok"
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        assertEquals("return-msg-1", toolReturn.id)
        assertEquals("call-1", toolReturn.toolCallId)
        assertEquals("success", toolReturn.status)
        assertEquals("ok", toolReturn.toolReturn?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun synthesizesStableReasoningIdWhenDeltaHasNoMessageId() {
        val first = assertIs<ServerFrame.ReasoningMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": 1,
                  "idempotency_key": "evt-reasoning-1",
                  "delta": {
                    "message_type": "reasoning_message",
                    "reasoning": "The",
                    "run_id": "run-app"
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        val second = assertIs<ServerFrame.ReasoningMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": 2,
                  "idempotency_key": "evt-reasoning-2",
                  "delta": {
                    "message_type": "reasoning_message",
                    "reasoning": " user",
                    "run_id": "run-app"
                  }
                }
                """.trimIndent(),
            ).single(),
        )

        assertEquals(first.id, second.id)
        assertEquals("iroh-reasoning_message-run-app-turn-fallback", first.id)
        assertEquals(1, first.seqId)
        assertEquals(2, second.seqId)
    }

    @Test
    fun usesStableReasoningIdEvenWhenDeltaChunksCarryUniqueIds() {
        val first = assertIs<ServerFrame.ReasoningMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": 1,
                  "idempotency_key": "evt-reasoning-1",
                  "delta": {
                    "id": "reasoning-word-1",
                    "message_type": "reasoning_message",
                    "reasoning": "Still",
                    "run_id": "run-app"
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        val second = assertIs<ServerFrame.ReasoningMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": 2,
                  "idempotency_key": "evt-reasoning-2",
                  "delta": {
                    "id": "reasoning-word-2",
                    "message_type": "reasoning_message",
                    "reasoning": " responsive",
                    "run_id": "run-app"
                  }
                }
                """.trimIndent(),
            ).single(),
        )

        assertEquals("iroh-reasoning_message-run-app-turn-fallback", first.id)
        assertEquals(first.id, second.id)
    }

    @Test
    fun mapsStopReasonToStopReasonAndSingleTerminalCandidate() {
        val frames = map(
            """
            {
              "type": "stream_delta",
              "event_seq": 4,
              "emitted_at": "2026-07-02T00:00:04Z",
              "idempotency_key": "evt-stop",
              "delta": {
                "message_type": "stop_reason",
                "run_id": "run-app",
                "stop_reason": "end_turn"
              }
            }
            """.trimIndent(),
        )

        val stop = assertIs<ServerFrame.StopReason>(frames[0])
        val done = assertIs<ServerFrame.TurnDone>(frames[1])
        assertEquals("evt-stop", stop.id)
        assertEquals("end_turn", stop.stopReason)
        assertEquals("run-app", stop.runId)
        assertEquals("evt-stop", done.id)
        assertEquals("completed", done.status)
        assertEquals("run-app", done.runId)
        assertEquals(4L, done.seq)
    }

    @Test
    fun doesNotConvertUnknownDeltasToAssistantMessages() {
        val frames = map(
            """
            {
              "type": "stream_delta",
              "event_seq": 9,
              "idempotency_key": "evt-future",
              "delta": {"message_type": "future_delta", "payload": "ignored"}
            }
            """.trimIndent(),
        )

        assertTrue(frames.isEmpty())
    }

    @Test
    fun preservesPlainAssistantFramesForLegacyControllers() {
        val frames = IrohStreamDeltaServerFrameMapper.map(
            payload = RuntimeEventPayload.RemoteStreamFrame(
                frameId = "plain-frame",
                messageId = "plain-message",
                messageType = "assistant_message",
                body = "plain text",
            ),
            context = context,
        )

        val assistant = assertIs<ServerFrame.AssistantMessage>(frames.single())
        assertEquals("plain-message", assistant.id)
        assertEquals("plain text", assistant.content)
        assertEquals("run-fallback", assistant.runId)
    }

    @Test
    fun assistantFragmentsWithRotatingIdsShareStableOtidAnchoredOnTurn() {
        // letta-mobile-x1xnl root-cause guard. App Server assistant deltas carry
        // NO otid/client_message_id, and over Iroh the backend `id` ROTATES per
        // streamed fragment. Before the fix, the client projection synthesized a
        // NEW effectiveOtid per fragment (server-<id>-assistant-<runId>), so the
        // reducer's otid/serverId dedup never matched and the trailing fragment
        // stranded as a duplicate row. The mapper must instead emit a STABLE otid
        // for all fragments of one assistant message so they merge into one row.
        val first = assertIs<ServerFrame.AssistantMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": 1,
                  "idempotency_key": "evt-a1",
                  "delta": {
                    "id": "letta-msg-5020",
                    "message_type": "assistant_message",
                    "content": "Got",
                    "run_id": "iroh-run-synthetic"
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        val second = assertIs<ServerFrame.AssistantMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": 2,
                  "idempotency_key": "evt-a2",
                  "delta": {
                    "id": "letta-msg-5021",
                    "message_type": "assistant_message",
                    "content": " it — streaming works.",
                    "run_id": "run-real-app-server"
                  }
                }
                """.trimIndent(),
            ).single(),
        )

        // Backend ids AND run ids rotate across fragments...
        assertEquals("letta-msg-5020", first.id)
        assertEquals("letta-msg-5021", second.id)
        // ...but the otid is stable (anchored on the invariant turn id), so the
        // reducer groups both fragments into a single assistant row.
        assertEquals("iroh-assistant-turn-fallback", first.otid)
        assertEquals(first.otid, second.otid)
    }

    @Test
    fun wireProvidedOtidStillWinsOverSyntheticTurnAnchor() {
        val frame = assertIs<ServerFrame.AssistantMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": 1,
                  "idempotency_key": "evt-otid",
                  "delta": {
                    "id": "letta-msg-9",
                    "message_type": "assistant_message",
                    "content": "hi",
                    "otid": "wire-otid-123",
                    "run_id": "run-app"
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        assertEquals("wire-otid-123", frame.otid)
    }

    private fun map(body: String): List<ServerFrame> =
        IrohStreamDeltaServerFrameMapper.map(
            payload = RuntimeEventPayload.RemoteStreamFrame(
                frameId = "frame-fallback",
                messageId = null,
                messageType = null,
                body = body,
            ),
            context = context,
        )

    private companion object {
        val context = IrohStreamDeltaServerFrameMapper.Context(
            agentId = "agent-fallback",
            conversationId = "conv-fallback",
            turnId = "turn-fallback",
            runId = "run-fallback",
            timestamp = "2026-07-02T00:00:00Z",
        )
    }
}
