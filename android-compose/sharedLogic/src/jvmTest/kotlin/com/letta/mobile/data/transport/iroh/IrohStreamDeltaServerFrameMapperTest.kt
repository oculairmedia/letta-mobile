package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

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
                "run_id": "run-app",
                "logical_message_id": "lm-1",
                "turn_id": "turn-wire",
                "text_seq": 3
              }
            }
            """.trimIndent(),
        )

        val frame = assertIs<ServerFrame.AssistantMessage>(frames.single())
        assertEquals("lm-1", frame.id)
        assertEquals("lm-1", frame.logicalMessageId)
        assertEquals(3, frame.textSeq)
        assertEquals("pong", frame.content)
        assertEquals("agent-stream", frame.agentId)
        assertEquals("conv-stream", frame.conversationId)
        assertEquals("turn-wire", frame.turnId)
        assertEquals("run-app", frame.runId)
        assertEquals(7L, frame.seq)
        assertEquals(7, frame.seqId)
        assertEquals("2026-07-02T00:00:00Z", frame.ts)
    }

    @Test
    fun rotatingDeltaIdsOfOneLogicalMessageShareOneRowId() {
        fun assistant(id: String, content: String) = assertIs<ServerFrame.AssistantMessage>(map(
            """{"type":"stream_delta","idempotency_key":"$id","delta":{"id":"$id","logical_message_id":"lm-1","text_seq":1,"message_type":"assistant_message","content":"$content"}}""",
        ).single())

        val first = assistant("delivery-1", "not a prefix")
        val second = assistant("delivery-2", "completely different")

        assertEquals("lm-1", first.id)
        assertEquals(first.id, second.id)
    }

    @Test
    fun aBlankLogicalIdIsNotAnIdentity() {
        val frames = map(
            """{"type":"stream_delta","delta":{"id":"delivery","message_id":"m-1","otid":"stable-otid","logical_message_id":" ","message_type":"assistant_message","content":"x"}}""",
        )
        assertTrue(frames.isEmpty())
    }

    @Test
    fun mapsReasoningDeltaToTypedFrame() {
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
                    "run_id": "run-app",
                    "logical_message_id": "lm-r",
                    "turn_id": "turn-wire"
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        assertEquals("thinking", reasoning.reasoning)
        assertEquals("run-app", reasoning.runId)
        assertEquals("lm-r", reasoning.id)
        assertEquals("turn-wire", reasoning.turnId)
    }

    @Test
    fun mapsToolCallDeltaToTypedFrame() {
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
    }

    @Test
    fun mapsToolReturnDeltaToTypedFrame() {
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
    fun reasoningChunksOfOneLogicalMessageShareTheStampedIdEvenWithUniqueDeltaIds() {
        fun reasoning(n: Int, wireId: String?) = assertIs<ServerFrame.ReasoningMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": $n,
                  "idempotency_key": "evt-reasoning-$n",
                  "delta": {
                    ${wireId?.let { "\"id\": \"$it\"," } ?: ""}
                    "message_type": "reasoning_message",
                    "reasoning": "chunk $n",
                    "run_id": "run-app",
                    "logical_message_id": "lm-r",
                    "text_seq": $n
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        val first = reasoning(1, null)
        val second = reasoning(2, "reasoning-word-2")

        assertEquals("lm-r", first.id)
        assertEquals(first.id, second.id)
        assertEquals(listOf(1, 2), listOf(first.textSeq, second.textSeq))
        assertEquals(1, first.seqId)
        assertEquals(2, second.seqId)
    }

    @Test
    fun mapsStopReasonWithoutSynthesizingTurnDone() {
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
                "stop_reason": "requires_approval"
              }
            }
            """.trimIndent(),
        )

        assertEquals(1, frames.size)
        val stop = assertIs<ServerFrame.StopReason>(frames[0])
        assertEquals("evt-stop", stop.id)
        assertEquals("requires_approval", stop.stopReason)
        assertEquals("run-app", stop.runId)
        assertTrue(frames.none { it is ServerFrame.TurnDone })
    }

    @Test
    fun mapsBusyErrorMessageWithoutSynthesizingTurnDone() {
        val frames = map(
            """
            {
              "type": "stream_delta",
              "event_seq": 5,
              "emitted_at": "2026-07-02T00:00:05Z",
              "idempotency_key": "evt-busy",
              "delta": {
                "message_type": "error_message",
                "message": "An App Server turn is already active for runtime-1."
              }
            }
            """.trimIndent(),
        )

        assertEquals(1, frames.size)
        val error = assertIs<ServerFrame.Error>(frames[0])
        assertEquals("iroh_turn_engine_busy", error.code)
        assertTrue(frames.none { it is ServerFrame.TurnDone })
    }

    @Test
    fun mapsInitiatorBusyRejectionToErrorAndFailedTurnDone() {
        val frames = map(
            """
            {
              "type": "stream_delta",
              "event_seq": 5,
              "emitted_at": "2026-07-02T00:00:05Z",
              "idempotency_key": "evt-busy-init",
              "delta": {
                "message_type": "error_message",
                "message": "Iroh App Server turn engine is already busy.",
                "iroh_rejection": "initiator_busy"
              }
            }
            """.trimIndent(),
        )

        assertEquals(2, frames.size)
        val error = assertIs<ServerFrame.Error>(frames[0])
        assertEquals("iroh_turn_engine_busy", error.code)
        val done = assertIs<ServerFrame.TurnDone>(frames[1])
        assertEquals("failed", done.status)
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
    fun plainTextBodiesCarryNoStampAndAreDropped() {
        val frames = IrohStreamDeltaServerFrameMapper.map(
            payload = RuntimeEventPayload.RemoteStreamFrame(
                frameId = "plain-frame",
                messageId = "plain-message",
                messageType = "assistant_message",
                body = "plain text",
            ),
            context = context,
        )

        assertTrue(frames.isEmpty())
    }

    @Test
    fun fragmentsWithRotatingDeltaIdsAndRunsStayOneRowByTheStamp() {
        // The backend `id` and `run_id` rotate across the fragments of one message; the host's
        // stamp is the only identity, so the mapper never re-derives one from them.
        fun fragment(n: Int, id: String, run: String) = assertIs<ServerFrame.AssistantMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": $n,
                  "idempotency_key": "evt-a$n",
                  "delta": {
                    "id": "$id",
                    "message_type": "assistant_message",
                    "content": "fragment $n",
                    "run_id": "$run",
                    "logical_message_id": "lm-1",
                    "turn_id": "cm-turn",
                    "text_seq": $n
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        val first = fragment(1, "letta-msg-5020", "iroh-run-synthetic")
        val second = fragment(2, "letta-msg-5021", "run-real-app-server")

        assertEquals("lm-1", first.id)
        assertEquals(first.id, second.id)
        assertEquals(first.turnId, second.turnId)
        assertEquals(null, first.otid)
        assertEquals(null, second.otid)
    }

    @Test
    fun aWireProvidedOtidIsCopiedAndNeverSynthesized() {
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
                    "run_id": "run-app",
                    "logical_message_id": "lm-9"
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        assertEquals("wire-otid-123", frame.otid)
    }

    /**
     * letta-mobile-utw4u (root-cause guard): the fanned-out user echo arrives
     * as a `user_message` delta whose `content` is a multimodal `content_parts`
     * JSON array (text + base64 image), NOT a bare string. The pre-utw4u
     * `contentText()` flattened the array into a string of base64 garbage and
     * dropped the image on every observer. The mapper must forward the raw
     * JsonElement on [ServerFrame.UserMessage.contentRaw] so the WsFrameMapper
     * hop and the timeline reducer can rebuild the image attachment.
     *
     * CodeRabbit review (2026-08-20): assert the raw/structural contract
     * exactly (not via substring) so a regression where the array is
     * re-stringified cannot pass through `contains(...)` checks. Also pin
     * the legacy `.content` text projection to `"look at this"` exactly —
     * the pre-fix projection leaked the base64 via `raw.toString()` and
     * downstream text-only consumers reported garbage.
     */
    @Test
    fun userMessageDeltaForwardsMultimodalContentPartsArrayVerbatim() {
        val frame = assertIs<ServerFrame.UserMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": 1,
                  "idempotency_key": "evt-user-mp",
                  "delta": {
                    "message_type": "user_message",
                    "id": "cm-user-cm-mp",
                    "otid": "cm-mp",
                    "content": [
                      {"type": "text", "text": "look at this"},
                      {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "IROP_PNG+"}}
                    ],
                    "run_id": "run-app"
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        // contentRaw survives the mapper hop as the verbatim JSON array.
        // CodeRabbit: equality, not substring.
        val raw = frame.contentRaw ?: fail("contentRaw must carry the array, not be null")
        assertIs<kotlinx.serialization.json.JsonArray>(raw)
        assertEquals(2, raw.size)
        // Cast to JsonObject first so the bracket-index resolves to
        // JsonObject.get, not MatchGroupCollection.get (Kotlin can't infer
        // the type from JsonArray index access alone).
        val textPart = assertIs<kotlinx.serialization.json.JsonObject>(raw[0])
        val imagePartOuter = assertIs<kotlinx.serialization.json.JsonObject>(raw[1])
        assertEquals("text", (textPart["type"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("look at this", (textPart["text"] as? JsonPrimitive)?.contentOrNull)
        // Image part preserves source.media_type + source.data verbatim.
        val imageSource = assertIs<kotlinx.serialization.json.JsonObject>(imagePartOuter["source"]
            ?: fail("image part must have a `source` field; got=${imagePartOuter}"))
        assertEquals("base64", (imageSource["type"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("image/png", (imageSource["media_type"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("IROP_PNG+", (imageSource["data"] as? JsonPrimitive)?.contentOrNull)
        // Legacy `.content` projection exposes ONLY the text portion — base64
        // image data must NOT leak through, otherwise text-only consumers
        // (IrohProbeMetrics, MergeTracer) report garbage.
        assertEquals("look at this", frame.content)
    }

    /**
     * letta-mobile-utw4u (flat-shape guard): the live wire / persisted rows
     * occasionally emit image parts as `{ type:"image", data:"…", mimeType:"…" }`
     * without a `source` wrapper. The mapper must still carry the flat shape
     * through; the downstream [parseLettaImagePart] is the layer responsible
     * for resolving it into [MessageContentPart.Image]. This test pins the
     * mapper's role (transport-preserving only) so a future "be helpful"
     * refactor cannot regress into flattening either.
     */
    @Test
    fun userMessageDeltaForwardsFlatImageContentPartVerbatim() {
        val frame = assertIs<ServerFrame.UserMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": 1,
                  "idempotency_key": "evt-user-flat",
                  "delta": {
                    "message_type": "user_message",
                    "id": "cm-user-cm-flat",
                    "otid": "cm-flat",
                    "content": [
                      {"type": "text", "text": "hi"},
                      {"type": "image", "data": "FLATMAP_JPEG=", "mimeType": "image/jpeg"}
                    ],
                    "run_id": "run-app"
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        val raw = frame.contentRaw ?: fail("contentRaw must carry the array, not be null")
        assertIs<kotlinx.serialization.json.JsonArray>(raw)
        // CodeRabbit review: equality, not substring. The flat-shape image
        // part must survive verbatim — neither `data` nor `mimeType` get
        // re-shaped by the mapper. Cast to JsonObject so bracket-index
        // resolves to JsonObject.get (not MatchGroupCollection.get).
        val imagePart = assertIs<kotlinx.serialization.json.JsonObject>(raw[1])
        assertEquals("image", (imagePart["type"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("FLATMAP_JPEG=", (imagePart["data"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("image/jpeg", (imagePart["mimeType"] as? JsonPrimitive)?.contentOrNull)
        // Legacy `.content` text projection locks to the text portion.
        // CodeRabbit: exactly equal, not `contains`.
        assertEquals("hi", frame.content)
    }

    /**
     * letta-mobile-utw4u (text-only regression): the pre-utw4u behaviour
     * for plain-text sends must not change. A bare string `content` on the
     * wire still surfaces as a JsonPrimitive on `contentRaw` (so the
     * downstream extractAttachments returns []) and the legacy `.content`
     * projection equals the bare string.
     */
    @Test
    fun userMessageDeltaWithPlainStringContentStillSurfacesAsJsonPrimitive() {
        val frame = assertIs<ServerFrame.UserMessage>(
            map(
                """
                {
                  "type": "stream_delta",
                  "event_seq": 1,
                  "idempotency_key": "evt-user-text",
                  "delta": {
                    "message_type": "user_message",
                    "id": "cm-user-cm-text",
                    "otid": "cm-text",
                    "content": "hello",
                    "run_id": "run-app"
                  }
                }
                """.trimIndent(),
            ).single(),
        )
        assertEquals("hello", frame.content)
        val raw = frame.contentRaw
        assertTrue(
            raw is JsonPrimitive && raw.contentOrNull == "hello",
            "text-only contentRaw must be a JsonPrimitive with the bare string; got=${raw}",
        )
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
