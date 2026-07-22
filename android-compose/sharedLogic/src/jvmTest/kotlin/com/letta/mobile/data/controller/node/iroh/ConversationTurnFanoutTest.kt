package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * eaczz.4 (S4-T): the fanout core. A scripted RuntimeEventDraft flow (assistant
 * multi-delta + tool_call + tool_return + terminal) is pumped through
 * [ConversationTurnFanout] — the exact seam [IrohNodeConnection.handleInput]
 * drives per draft — while TWO IrohViewerHandles are registered for one
 * conversation (one is the initiator's selfViewer). Asserts BOTH viewers receive
 * the ordered, cm-stream-tagged sequence with one terminal each and per-viewer
 * monotonic event_seq; and that the SINGLE-VIEWER case is shape-identical to the
 * synthesized initiator path baseline (golden).
 *
 * Tested at the ConversationTurnFanout level (not the raw handleInput) because
 * handleInput hard-requires a JNI computer.iroh.SendStream that cannot be faked;
 * the fanout owns 100% of the frame-shaping + fanout + parking-hook logic that
 * handleInput's collect-loop delegates to, so this exercises the real behavior.
 */
class ConversationTurnFanoutTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val runtime = AppServerRuntimeScope("agent-1", "conv-C")
    private val conversationId = "conv-C"

    /** Capturing sink: records every writeAll and decodes back to frame strings. */
    private class CapturingSink : ViewerFrameSink {
        val chunks = mutableListOf<ByteArray>()
        override suspend fun writeAll(bytes: ByteArray) { chunks.add(bytes) }
        fun frames(): List<String> {
            val decoder = IrohFrameCodec.Decoder(
                IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
                IrohFrameCodec.DEFAULT_MAX_REASSEMBLED_BYTES,
            )
            val out = mutableListOf<String>()
            chunks.forEach { out += decoder.feed(it) }
            return out
        }
    }

    private fun viewer(connectionId: String, sink: CapturingSink) = IrohViewerHandle(
        connectionId = connectionId,
        sink = sink,
        eventSeq = IrohEventSeqAllocator.newConnectionSeq(),
        streamWriteMutex = Mutex(),
        frameParts = { false },
        maxFrameBytes = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
    )

    /** A full upstream wire frame body, as DefaultAppServerController/ProbeStub emits. */
    private fun rawStreamDeltaBody(
        seq: Long,
        delta: JsonObject,
        idempotencyKey: String = "stub-delta-${UUID.randomUUID()}",
    ): String = buildJsonObject {
        put("type", "stream_delta")
        put("runtime", buildJsonObject {
            put("agent_id", runtime.agentId)
            put("conversation_id", runtime.conversationId)
        })
        put("event_seq", seq)
        put("emitted_at", Instant.now().toString())
        put("idempotency_key", idempotencyKey)
        put("delta", delta)
    }.toString()

    private data class AssistantTestFrame(
        val content: String,
        val idempotencyKey: String,
        val otid: String,
    )

    private fun assistantFrame(frame: AssistantTestFrame): String = rawStreamDeltaBody(
        seq = 1,
        idempotencyKey = frame.idempotencyKey,
        delta = buildJsonObject {
            put("message_type", "assistant_message")
            put("otid", frame.otid)
            put("content", frame.content)
        },
    )

    private fun assistantContent(frame: String): String = json.parseToJsonElement(frame).jsonObject
        .getValue("delta").jsonObject.getValue("content").jsonPrimitive.content

    private fun assertAccumulatedText(
        source: StreamTextFrameSource,
        frames: List<AssistantTestFrame>,
        expected: List<String>,
    ) {
        val accumulator = CumulativeStreamText()
        val actual = frames.map { frame ->
            assistantContent(accumulator.applyToRawFrame(assistantFrame(frame), source))
        }
        assertEquals(expected, actual)
    }

    private val command = TurnCommand(
        backendId = BackendId("iroh-node-server"),
        runtimeId = RuntimeId("rt"),
        agentId = AgentId("agent-1"),
        conversationId = ConversationId("conv-C"),
        input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
    )

    /**
     * A scripted controller-style flow: reasoning delta, tool_call, assistant
     * multi-delta (incremental), tool_return, terminal stop_reason. Uses a
     * monotonic upstream seq like the real controller.
     */
    private fun scriptedDrafts(): List<RuntimeEventPayload> {
        val seq = AtomicLong(0)
        fun raw(delta: JsonObject) = RuntimeEventPayload.RemoteStreamFrame(
            frameId = "f-${UUID.randomUUID()}", messageId = null, messageType = null,
            body = rawStreamDeltaBody(seq.incrementAndGet(), delta),
        )
        return listOf(
            raw(buildJsonObject {
                put("message_type", "tool_call_message")
                put("tool_call", buildJsonObject {
                    put("name", "stub_tool"); put("tool_call_id", "tc-1"); put("arguments", "{}")
                })
            }),
            raw(buildJsonObject {
                put("message_type", "assistant_message"); put("otid", "otid-1")
                put("id", "letta-msg-1"); put("content", "Hel")
            }),
            raw(buildJsonObject {
                put("message_type", "assistant_message"); put("otid", "otid-1")
                put("id", "letta-msg-1"); put("content", "lo world")
            }),
            raw(buildJsonObject {
                put("message_type", "tool_return_message"); put("tool_call_id", "tc-1")
                put("tool_return", "ok"); put("status", "success")
            }),
            RuntimeEventPayload.RunLifecycleChanged(
                status = RuntimeRunStatus.Completed, reason = "end_turn",
            ),
        )
    }

    /**
     * Pump a scripted flow through a fanout wired to [registry], exactly as
     * handleInput's collect-loop does: terminal-duplicate-skip, failure/cancel
     * dangling flush, then onDraft. Returns nothing (assertions read the sinks).
     */
    private suspend fun pump(
        fanout: ConversationTurnFanout,
        drafts: List<RuntimeEventPayload>,
    ) {
        for (payload in drafts) {
            if (fanout.anyTerminalWritten && fanout.isTerminalLifecycle(payload)) continue
            if (fanout.isFailureOrCancelLifecycle(payload)) fanout.flushOpenToolCalls()
            fanout.onDraft(payload)
        }
    }

    private fun fanoutFor(
        registry: ConnectionRegistry?,
        initiator: ViewerHandle?,
        parked: MutableList<String> = mutableListOf(),
    ) = ConversationTurnFanout(
        conversationId = conversationId,
        runtime = runtime,
        remoteEndpointId = "conn-init",
        viewersFor = { conv -> registry?.viewersFor(conv) ?: emptySet() },
        initiatorViewer = initiator,
        trackInitiatorFrame = { parked.add(it) },
    )

    @Test
    fun bothViewersReceiveOrderedTaggedSequenceWithOneTerminalEach() = runTest {
        val registry = ConnectionRegistry()
        val sinkInit = CapturingSink()
        val sinkObs = CapturingSink()
        val initiator = viewer("conn-init", sinkInit)
        val observer = viewer("conn-obs", sinkObs)
        registry.register(conversationId, initiator)
        registry.register(conversationId, observer)

        pump(fanoutFor(registry, initiator), scriptedDrafts())

        listOf(sinkInit to "initiator", sinkObs to "observer").forEach { (sink, who) ->
            val frames = sink.frames().map { json.parseToJsonElement(it).jsonObject }
            // tool_call, 2 assistant deltas, tool_return, stop_reason = 5 frames.
            assertEquals(5, frames.size, "$who frame count")
            // Per-viewer strictly-monotonic + unique event_seq.
            val seqs = frames.map { it["event_seq"]!!.jsonPrimitive.content.toLong() }
            assertEquals(seqs.sorted(), seqs, "$who event_seq must be monotonic")
            assertEquals(seqs.toSet().size, seqs.size, "$who event_seq must be unique")
            // Unique idempotency_key per frame, iroh-delta-* format (fresh, not stub-delta-*).
            val keys = frames.map { it["idempotency_key"]!!.jsonPrimitive.content }
            assertEquals(keys.toSet().size, keys.size, "$who idempotency_key unique")
            assertTrue(keys.all { it.startsWith("iroh-delta-") }, "$who idempotency_key format")
            // cm-stream tag applied to assistant deltas (h30cy dedup).
            val assistantIds = frames.mapNotNull {
                it["delta"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            }.filter { it.startsWith("cm-stream-") }
            assertEquals(2, assistantIds.size, "$who cm-stream tagged assistant deltas")
            assertTrue(assistantIds.all { it == "cm-stream-otid-1" }, "$who cm-stream id")
            // Cumulative accumulation: last assistant delta carries full text.
            val assistantContents = frames.mapNotNull {
                val d = it["delta"]?.jsonObject ?: return@mapNotNull null
                if (d["message_type"]?.jsonPrimitive?.content == "assistant_message") {
                    d["content"]?.jsonPrimitive?.content
                } else null
            }
            assertEquals(listOf("Hel", "Hello world"), assistantContents, "$who cumulative text")
            // Exactly one terminal.
            val terminals = frames.count {
                it["delta"]?.jsonObject?.get("message_type")?.jsonPrimitive?.content == "stop_reason"
            }
            assertEquals(1, terminals, "$who exactly one terminal")
        }

        // Viewers draw from disjoint event_seq ranges (allocator strides per connection).
        val initSeqs = sinkInit.frames().map { json.parseToJsonElement(it).jsonObject["event_seq"]!!.jsonPrimitive.content.toLong() }.toSet()
        val obsSeqs = sinkObs.frames().map { json.parseToJsonElement(it).jsonObject["event_seq"]!!.jsonPrimitive.content.toLong() }.toSet()
        assertTrue(initSeqs.intersect(obsSeqs).isEmpty(), "viewers must not share event_seq")
    }

    @Test
    fun repeatedIncrementalTokensArePreservedExactly() = runTest {
        val registry = ConnectionRegistry()
        val sinkInit = CapturingSink()
        val initiator = viewer("conn-init", sinkInit)
        registry.register(conversationId, initiator)
        val seq = AtomicLong(0)
        fun assistant(content: String) = RuntimeEventPayload.RemoteStreamFrame(
            frameId = "f-${UUID.randomUUID()}",
            messageId = null,
            messageType = null,
            body = rawStreamDeltaBody(seq.incrementAndGet(), buildJsonObject {
                put("message_type", "assistant_message")
                put("otid", "otid-repeat")
                put("id", "letta-msg-repeat")
                put("content", content)
            }),
        )

        pump(
            fanoutFor(registry, initiator),
            listOf(
                assistant("ha"),
                assistant("ha"),
                assistant(" "),
                assistant(" "),
                assistant("."),
                assistant("."),
                assistant("\n"),
                assistant("\n"),
            ),
        )

        val assistantContents = sinkInit.frames().mapNotNull { encoded ->
            val delta = json.parseToJsonElement(encoded).jsonObject["delta"]?.jsonObject
                ?: return@mapNotNull null
            if (delta["message_type"]?.jsonPrimitive?.content == "assistant_message") {
                delta["content"]?.jsonPrimitive?.content
            } else {
                null
            }
        }
        assertEquals(
            listOf("ha", "haha", "haha ", "haha  ", "haha  .", "haha  ..", "haha  ..\n", "haha  ..\n\n"),
            assistantContents,
        )
    }

    @Test
    fun overlappingIncrementalFramesPreserveEveryByte() = runTest {
        assertAccumulatedText(
            source = StreamTextFrameSource.AppServerDelta,
            frames = listOf(
                AssistantTestFrame("a", "overlap-1", "otid-overlap"),
                AssistantTestFrame("aa", "overlap-2", "otid-overlap"),
            ),
            expected = listOf("a", "aaa"),
        )
    }

    @Test
    fun explicitlyCumulativeFramesReplaceThePreviousSnapshot() = runTest {
        assertAccumulatedText(
            source = StreamTextFrameSource.CumulativeSnapshot,
            frames = listOf(
                AssistantTestFrame("Hel", "cumulative-1", "otid-cumulative"),
                AssistantTestFrame("Hello", "cumulative-2", "otid-cumulative"),
            ),
            expected = listOf("Hel", "Hello"),
        )
    }

    @Test
    fun replayedEnvelopeDoesNotDuplicateIncrementalText() = runTest {
        val registry = ConnectionRegistry()
        val sinkInit = CapturingSink()
        val initiator = viewer("conn-init", sinkInit)
        registry.register(conversationId, initiator)
        val delta = buildJsonObject {
            put("message_type", "assistant_message")
            put("otid", "otid-replay")
            put("id", "letta-msg-replay")
            put("content", "same")
        }
        val body = rawStreamDeltaBody(1, delta, idempotencyKey = "replay-key")
        val replay = RuntimeEventPayload.RemoteStreamFrame(
            frameId = "frame-replay",
            messageId = null,
            messageType = null,
            body = body,
        )

        pump(fanoutFor(registry, initiator), listOf(replay, replay))

        val assistantContents = sinkInit.frames().mapNotNull { encoded ->
            val bodyDelta = json.parseToJsonElement(encoded).jsonObject["delta"]?.jsonObject
                ?: return@mapNotNull null
            bodyDelta["content"]?.jsonPrimitive?.content
        }
        assertEquals(listOf("same", "same"), assistantContents)
    }

    @Test
    fun failingObserverDoesNotBreakInitiatorOrTheLoop() = runTest {
        val registry = ConnectionRegistry()
        val sinkInit = CapturingSink()
        val initiator = viewer("conn-init", sinkInit)
        val dead = object : ViewerHandle {
            override val connectionId = "conn-dead"
            override suspend fun writeFrame(frame: String): Boolean = throw RuntimeException("dead stream")
        }
        registry.register(conversationId, initiator)
        registry.register(conversationId, dead)

        // Must not throw despite the dead observer failing every write.
        pump(fanoutFor(registry, initiator), scriptedDrafts())

        // Initiator still got the full, terminal-terminated sequence.
        val frames = sinkInit.frames()
        assertEquals(5, frames.size)
        val terminals = frames.count {
            json.parseToJsonElement(it).jsonObject["delta"]?.jsonObject
                ?.get("message_type")?.jsonPrimitive?.content == "stop_reason"
        }
        assertEquals(1, terminals)
    }

    @Test
    fun singleViewerIsShapeIdenticalToInitiatorBaselineGolden() = runTest {
        // With ONLY the initiator viewing (the common case), the frames the
        // initiator receives must be shape-identical to the pre-fanout path: one
        // stream_delta wrapper per delta, per-connection monotonic event_seq,
        // iroh-delta-<uuid> idempotency_key, cm-stream tag, exactly one terminal.
        val registry = ConnectionRegistry()
        val sinkInit = CapturingSink()
        val initiator = viewer("conn-init", sinkInit)
        registry.register(conversationId, initiator)

        pump(fanoutFor(registry, initiator), scriptedDrafts())

        val frames = sinkInit.frames().map { json.parseToJsonElement(it).jsonObject }
        assertEquals(5, frames.size)
        // Every frame is a stream_delta with the canonical envelope keys.
        frames.forEach { f ->
            assertEquals("stream_delta", f["type"]!!.jsonPrimitive.content)
            assertTrue(f.containsKey("runtime"))
            assertTrue(f.containsKey("event_seq"))
            assertTrue(f.containsKey("emitted_at"))
            assertTrue(f["idempotency_key"]!!.jsonPrimitive.content.startsWith("iroh-delta-"))
            assertTrue(f.containsKey("delta"))
        }
        // Terminal delta is exactly {message_type: stop_reason, stop_reason: end_turn}.
        val terminal = frames.last()["delta"]!!.jsonObject
        assertEquals("stop_reason", terminal["message_type"]!!.jsonPrimitive.content)
        assertEquals("end_turn", terminal["stop_reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun legacyNoRegistryStillWritesToInitiatorOnly() = runTest {
        // No registry wired (legacy/test construction) — fanout must still deliver
        // the full sequence to the single initiator viewer.
        val sinkInit = CapturingSink()
        val initiator = viewer("conn-init", sinkInit)

        pump(fanoutFor(registry = null, initiator = initiator), scriptedDrafts())

        assertEquals(5, sinkInit.frames().size)
    }

    @Test
    fun initiatorOnlyParkingTracksEveryBroadcastFrameOnce() = runTest {
        // Parking must be INITIATOR-ONLY and record ONE entry per broadcast delta
        // (not one per viewer) — even with a second observer registered.
        val registry = ConnectionRegistry()
        val parked = mutableListOf<String>()
        val initiator = viewer("conn-init", CapturingSink())
        val observer = viewer("conn-obs", CapturingSink())
        registry.register(conversationId, initiator)
        registry.register(conversationId, observer)

        pump(fanoutFor(registry, initiator, parked), scriptedDrafts())

        // One tracked frame per broadcast delta (5), NOT 10 (would be per-viewer).
        assertEquals(5, parked.size, "parking must be initiator-scoped, once per delta")
        // The tracked terminal is the stop_reason delta.
        assertTrue(parked.last().contains("stop_reason"))
    }

    @Test
    fun rawAndSemanticToolEventsBroadcastOneLogicalCallAndReturn() = runTest {
        val registry = ConnectionRegistry()
        val sink = CapturingSink()
        val initiator = viewer("conn-init", sink)
        registry.register(conversationId, initiator)
        val fanout = fanoutFor(registry, initiator)
        val seq = AtomicLong(0)
        fun raw(delta: JsonObject) = RuntimeEventPayload.RemoteStreamFrame(
            frameId = "dedup-${seq.incrementAndGet()}",
            messageId = null,
            messageType = null,
            body = rawStreamDeltaBody(seq.get(), delta),
        )
        val callId = ToolCallId("tc-dedup")

        fanout.onDraft(RuntimeEventPayload.ToolCallObserved(callId, ToolName("bash"), "{}"))
        fanout.onDraft(raw(buildJsonObject {
            put("message_type", "tool_call_message")
            put("tool_call", buildJsonObject {
                put("tool_call_id", callId.value)
                put("name", "bash")
                put("arguments", "{}")
            })
        }))
        fanout.onDraft(raw(buildJsonObject {
            put("message_type", "tool_return_message")
            put("id", "message-tool-return-rotating")
            put("tool_call_id", callId.value)
            put("status", "success")
            put("tool_return", "ok")
        }))
        fanout.onDraft(RuntimeEventPayload.ToolReturnObserved(callId, ToolExecutionStatus.Succeeded, "ok"))

        val deltas = sink.frames().map { json.parseToJsonElement(it).jsonObject["delta"]!!.jsonObject }
        assertEquals(1, deltas.count { it["message_type"]!!.jsonPrimitive.content == "tool_call_message" })
        assertEquals(1, deltas.count { it["message_type"]!!.jsonPrimitive.content == "tool_return_message" })
        fanout.flushOpenToolCalls()
        assertEquals(2, sink.frames().size, "real return must close the tracker despite duplicate suppression")
    }

    @Test
    fun sameIdRawToolUpdatesBroadcastDistinctArgumentSnapshots() = runTest {
        val registry = ConnectionRegistry()
        val sink = CapturingSink()
        val initiator = viewer("conn-init", sink)
        registry.register(conversationId, initiator)
        val fanout = fanoutFor(registry, initiator)
        val seq = AtomicLong(0)
        fun raw(arguments: String) = RuntimeEventPayload.RemoteStreamFrame(
            frameId = "update-${seq.incrementAndGet()}",
            messageId = null,
            messageType = null,
            body = rawStreamDeltaBody(seq.get(), buildJsonObject {
                put("message_type", "tool_call_message")
                put("tool_call", buildJsonObject {
                    put("tool_call_id", "tc-progressive")
                    put("name", "bash")
                    put("arguments", arguments)
                })
            }),
        )

        fanout.onDraft(raw("{\"command\":\"ec\"}"))
        fanout.onDraft(raw("{\"command\":\"echo ok\"}"))
        fanout.onDraft(raw("{\"command\":\"echo ok\"}"))

        val arguments = sink.frames().map {
            json.parseToJsonElement(it).jsonObject["delta"]!!.jsonObject["tool_call"]!!
                .jsonObject["arguments"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("{\"command\":\"ec\"}", "{\"command\":\"echo ok\"}"), arguments)
    }

    @Test
    fun topLevelToolCallShapeDeduplicatesAgainstSemanticEvent() = runTest {
        val sink = CapturingSink()
        val initiator = viewer("conn-init", sink)
        val registry = ConnectionRegistry().apply { register(conversationId, initiator) }
        val fanout = fanoutFor(registry, initiator)
        val callId = ToolCallId("tc-top-level")

        fanout.onDraft(RuntimeEventPayload.ToolCallObserved(callId, ToolName("bash"), "{\"command\":\"pwd\"}"))
        fanout.onDraft(RuntimeEventPayload.RemoteStreamFrame(
            frameId = "raw-top-level",
            messageId = null,
            messageType = null,
            body = rawStreamDeltaBody(1, buildJsonObject {
                put("message_type", "tool_call_message")
                put("tool_call_id", callId.value)
                put("name", "bash")
                put("arguments", "{\"command\":\"pwd\"}")
            }),
        ))

        assertEquals(1, sink.frames().size)
    }

    @Test
    fun toolCallsArrayShapeDeduplicatesAgainstSemanticEvent() = runTest {
        val sink = CapturingSink()
        val initiator = viewer("conn-init", sink)
        val registry = ConnectionRegistry().apply { register(conversationId, initiator) }
        val fanout = fanoutFor(registry, initiator)
        val callId = ToolCallId("tc-array")

        fanout.onDraft(RuntimeEventPayload.ToolCallObserved(callId, ToolName("bash"), "{\"command\":\"pwd\"}"))
        fanout.onDraft(RuntimeEventPayload.RemoteStreamFrame(
            frameId = "raw-array",
            messageId = null,
            messageType = null,
            body = rawStreamDeltaBody(1, buildJsonObject {
                put("message_type", "tool_call_message")
                put("id", "message-id")
                put("tool_calls", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("tool_call_id", callId.value)
                        put("name", "bash")
                        put("arguments", "{\"command\":\"pwd\"}")
                    })
                })
            }),
        ))

        assertEquals(1, sink.frames().size)
    }

    @Test
    fun returnAliasesAndDefaultStatusDeduplicateAgainstSemanticEvent() = runTest {
        listOf("output", "message", "content").forEach { bodyAlias ->
            val sink = CapturingSink()
            val initiator = viewer("conn-$bodyAlias", sink)
            val registry = ConnectionRegistry().apply { register(conversationId, initiator) }
            val fanout = fanoutFor(registry, initiator)
            val callId = ToolCallId("tc-$bodyAlias")

            fanout.onDraft(RuntimeEventPayload.RemoteStreamFrame(
                frameId = "raw-$bodyAlias",
                messageId = null,
                messageType = null,
                body = rawStreamDeltaBody(1, buildJsonObject {
                    put("message_type", "tool_return_message")
                    put("tool_call_id", callId.value)
                    put(bodyAlias, "ok")
                }),
            ))
            fanout.onDraft(RuntimeEventPayload.ToolReturnObserved(callId, ToolExecutionStatus.Succeeded, "ok"))

            assertEquals(1, sink.frames().size, "alias $bodyAlias should use mapper-equivalent normalization")
        }
    }

    @Test
    fun richerRawReturnIsPreservedAfterSemanticReturn() = runTest {
        val sink = CapturingSink()
        val initiator = viewer("conn-init", sink)
        val registry = ConnectionRegistry().apply { register(conversationId, initiator) }
        val fanout = fanoutFor(registry, initiator)
        val callId = ToolCallId("tc-rich")

        fanout.onDraft(RuntimeEventPayload.ToolReturnObserved(callId, ToolExecutionStatus.Succeeded, "ok"))
        fanout.onDraft(RuntimeEventPayload.RemoteStreamFrame(
            frameId = "raw-rich",
            messageId = null,
            messageType = null,
            body = rawStreamDeltaBody(1, buildJsonObject {
                put("message_type", "tool_return_message")
                put("tool_call_id", callId.value)
                put("status", "success")
                put("tool_return", "ok")
                put("stdout", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("full output")) })
                put("metadata", buildJsonObject { put("duration_ms", 12) })
            }),
        ))

        val deltas = sink.frames().map { json.parseToJsonElement(it).jsonObject["delta"]!!.jsonObject }
        assertEquals(2, deltas.size)
        assertTrue(deltas.last().containsKey("stdout"))
        assertTrue(deltas.last().containsKey("metadata"))
        fanout.flushOpenToolCalls()
        assertEquals(2, sink.frames().size, "dedupe state must not interfere with open-tool tracking")
    }

    @Test
    fun distinctToolCallIdsAreNotDeduplicated() = runTest {
        val registry = ConnectionRegistry()
        val sink = CapturingSink()
        val initiator = viewer("conn-init", sink)
        registry.register(conversationId, initiator)
        val fanout = fanoutFor(registry, initiator)

        listOf("tc-one", "tc-two").forEach { id ->
            fanout.onDraft(RuntimeEventPayload.ToolCallObserved(ToolCallId(id), ToolName("bash"), "{}"))
            fanout.onDraft(RuntimeEventPayload.ToolReturnObserved(ToolCallId(id), ToolExecutionStatus.Succeeded, "ok"))
        }

        assertEquals(4, sink.frames().size)
    }

    @Test
    fun terminalDuplicateIsSkipped() = runTest {
        val registry = ConnectionRegistry()
        val sinkInit = CapturingSink()
        val initiator = viewer("conn-init", sinkInit)
        registry.register(conversationId, initiator)

        val drafts = scriptedDrafts() + RuntimeEventPayload.RunLifecycleChanged(
            status = RuntimeRunStatus.Completed, reason = "end_turn",
        )
        pump(fanoutFor(registry, initiator), drafts)

        val terminals = sinkInit.frames().count {
            json.parseToJsonElement(it).jsonObject["delta"]?.jsonObject
                ?.get("message_type")?.jsonPrimitive?.content == "stop_reason"
        }
        assertEquals(1, terminals, "duplicate terminal must be skipped")
    }
}
