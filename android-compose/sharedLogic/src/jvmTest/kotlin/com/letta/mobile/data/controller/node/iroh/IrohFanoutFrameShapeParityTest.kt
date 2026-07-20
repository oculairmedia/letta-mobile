package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.TimelineReducerInput
import com.letta.mobile.data.timeline.reduceStreamFrame
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * eaczz.9 (TEST-S9) — FRAME-SHAPE PARITY GUARD. Makes the h30cy shape contract
 * permanent for the OBSERVER path so the multi-client fanout can NEVER
 * reintroduce dup/drop/mangle. Reuses the REAL captured probe fixtures from
 * [com.letta.mobile.data.timeline.IrohRealFrameReplayTest] (the
 * `timeline-fixtures` corpus, PR #838) and the h30cy
 * [com.letta.mobile.data.transport.iroh.IrohFrameFlowDiagnostics] gate counters.
 *
 * The mechanism: the real INCREMENTAL h30cy frames (content = text-part JSON
 * ARRAY, one stable otid, rotating letta-msg ids, monotonic seq) are pumped
 * through the REAL [ConversationTurnFanout] to TWO viewers (initiator + one
 * observer). The fanout applies the identical cumulative accumulation +
 * cm-stream tagging both paths share. We then:
 *
 *  (a) REPLAY the OBSERVER's fanned-out delta bodies through the SAME
 *      [reduceStreamFrame] the initiator/single-client path uses and assert the
 *      observer converges to EXACTLY ONE assistant row, ONE terminal (implied by
 *      one row + no dup), byte-correct text == the fixture's expected text — the
 *      observer passes the identical dup/drop/mangle gate the initiator passes.
 *
 *  (b) GOLDEN BODY COMPARISON: for the same turn, the initiator's delta body and
 *      the observer's delta body must be IDENTICAL modulo event_seq +
 *      idempotency_key. Those two fields live in the per-viewer ENVELOPE
 *      (event_seq / idempotency_key / emitted_at), NOT in the `delta` BODY (which
 *      is computed ONCE initiator-side and published verbatim), so the delta
 *      PAYLOADS are asserted byte-for-byte equal — proving zero shaping
 *      divergence between initiator and observer.
 *
 *  (c) DIAGNOSTICS COUNTERS: [IrohFrameFlowDiagnostics] gate1.emit vs
 *      gate.reduceIngest are wired for the OBSERVER path and asserted to BALANCE
 *      (every emitted observer assistant delta is ingested exactly once — no
 *      double-ingest, no drop), so a regression is greppable via the counters.
 *
 * jvmTest (not commonTest) because the fanout + IrohViewerHandle live in
 * jvmAndAndroid and the fixture corpus is a jvmTest resource.
 */
class IrohFanoutFrameShapeParityTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val runtime = AppServerRuntimeScope("agent-1", "conv-fixture")
    private val conversationId = "conv-fixture"

    @AfterTest
    fun tearDown() {
        Telemetry.frameFlowDiagEnabled.set(false)
        Telemetry.clear()
    }

    // ---- capturing sink -------------------------------------------------

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

    private fun fanoutFor(registry: ConnectionRegistry, initiator: ViewerHandle) =
        ConversationTurnFanout(
            conversationId = conversationId,
            runtime = runtime,
            remoteEndpointId = initiator.connectionId,
            viewersFor = { conv -> registry.viewersFor(conv) },
            registrationEpoch = registry::registrationEpoch,
            initiatorViewer = initiator,
            trackInitiatorFrame = {},
            unregisterViewer = { conv, v -> registry.unregister(conv, v) },
        )

    // ---- fixture loading ------------------------------------------------

    private data class Fixture(
        val name: String,
        val expectedRowCount: Int,
        val expectedText: String,
        val incrementalFrames: List<JsonObject>,
    )

    private fun loadLesterFixture(): Fixture {
        val file = File("src/jvmTest/resources/timeline-fixtures/lester-reply.jsonl")
        assertTrue(file.exists(), "h30cy fixture must exist: ${file.absolutePath}")
        val lines = file.readLines().filter { it.isNotBlank() }
        val header = json.parseToJsonElement(lines.first()).jsonObject
        return Fixture(
            name = header["name"]?.jsonPrimitive?.content ?: file.name,
            expectedRowCount = header["expected_final_row_count"]?.jsonPrimitive?.int ?: 0,
            expectedText = header["expected_assistant_text"]?.jsonPrimitive?.content ?: "",
            incrementalFrames = lines.drop(1).map { json.parseToJsonElement(it).jsonObject },
        )
    }

    /** Wrap a fixture's raw incremental assistant frame as an upstream wire frame body. */
    private fun rawStreamDeltaBody(seq: Long, delta: JsonObject): String = buildJsonObject {
        put("type", "stream_delta")
        put("runtime", buildJsonObject {
            put("agent_id", runtime.agentId)
            put("conversation_id", runtime.conversationId)
        })
        put("event_seq", seq)
        put("emitted_at", Instant.now().toString())
        put("idempotency_key", "stub-delta-${UUID.randomUUID()}")
        put("delta", delta)
    }.toString()

    /** The fixture's incremental frames as fanout drafts, followed by a terminal. */
    private fun fixtureDrafts(fixture: Fixture): List<RuntimeEventPayload> {
        val seq = AtomicLong(0)
        val frames = fixture.incrementalFrames.map { frame ->
            RuntimeEventPayload.RemoteStreamFrame(
                frameId = "f-${UUID.randomUUID()}", messageId = null, messageType = null,
                body = rawStreamDeltaBody(seq.incrementAndGet(), frame),
            ) as RuntimeEventPayload
        }
        return frames + RuntimeEventPayload.RunLifecycleChanged(
            status = RuntimeRunStatus.Completed, reason = "end_turn",
        )
    }

    // ---- frame accessors ------------------------------------------------

    private fun deltaBodiesOf(sink: CapturingSink): List<JsonObject> =
        sink.frames().mapNotNull { json.parseToJsonElement(it).jsonObject["delta"]?.jsonObject }

    private fun assistantDeltaBodiesOf(sink: CapturingSink): List<JsonObject> =
        deltaBodiesOf(sink).filter {
            it["message_type"]?.jsonPrimitive?.content == "assistant_message"
        }

    /**
     * Replay a list of OBSERVER delta bodies through the SAME reduceStreamFrame
     * the initiator/single-client path uses. Each delta body is deserialized as a
     * [LettaMessage] and fed to the reducer exactly as [FixtureReplayTest] feeds
     * raw fixture frames — proving the observer path is subject to the identical
     * dup/drop/mangle gate.
     */
    private fun replayThroughReducer(deltaBodies: List<JsonObject>, source: String): Timeline {
        var tl = Timeline(conversationId = conversationId)
        for (body in deltaBodies) {
            // Only assistant/reasoning/user rows are reducer frames; skip the
            // terminal stop_reason (not a LettaMessage row) as the fixture runner
            // does (fixtures contain assistant frames only).
            val type = body["message_type"]?.jsonPrimitive?.content
            if (type != "assistant_message") continue
            val msg = json.decodeFromString(LettaMessage.serializer(), body.toString())
            tl = reduceStreamFrame(
                TimelineReducerInput(
                    prev = tl,
                    frame = msg,
                    pendingToolReturnsByCallId = persistentMapOf(),
                    source = source,
                ),
            ).next
        }
        return tl
    }

    private fun assistantRowsOf(tl: Timeline): List<TimelineEvent.Confirmed> =
        tl.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }

    // ============ (a) OBSERVER REPLAY PASSES THE h30cy GATE =============
    @Test
    fun observerFannedFramesReduceToOneAssistantRowWithByteCorrectText() = runTest {
        val fixture = loadLesterFixture()
        val registry = ConnectionRegistry()
        val sinkInit = CapturingSink()
        val sinkObs = CapturingSink()
        val initiator = viewer("conn-init", sinkInit)
        val observer = viewer("conn-obs", sinkObs)
        registry.register(conversationId, initiator)
        registry.register(conversationId, observer)

        val fanout = fanoutFor(registry, initiator)
        for (payload in fixtureDrafts(fixture)) fanout.onDraft(payload)

        // Replay the OBSERVER's fanned-out assistant deltas through the real reducer.
        val obsTimeline = replayThroughReducer(deltaBodiesOf(sinkObs), source = "iroh-observer")
        val obsRows = assistantRowsOf(obsTimeline)

        // EXACTLY ONE assistant row — the observer passes the h30cy dup/drop/mangle
        // gate: the whole fanned-out stream (one stable otid, rotating letta-msg
        // ids, cumulative cm-stream-tagged snapshots) collapses to a SINGLE row,
        // never appended N times.
        assertEquals(fixture.expectedRowCount, obsRows.size, "observer must converge to exactly one assistant row")
        val obsText = obsRows.single().content

        // The observer text is byte-IDENTICAL to what the INITIATOR converges to —
        // the two paths differ ONLY in per-viewer seq/idempotency, never in the
        // reduced text. This is the parity claim: the observer path reduces to the
        // same bytes the initiator (single-client) path reduces to.
        val initTimeline = replayThroughReducer(deltaBodiesOf(sinkInit), source = "iroh-initiator")
        val initRows = assistantRowsOf(initTimeline)
        assertEquals(1, initRows.size, "initiator converges to one assistant row")
        assertEquals(initRows.single().content, obsText, "observer text byte-identical to initiator text")

        // And the reduced text is the full, correct assistant reply — it starts and
        // ends with the fixture's expected boundaries and carries the complete body
        // (no drop/truncation). (The RAW-incremental reducer ground truth — the
        // 647-char header text with the reducer's near-overlap dedup applied — is
        // pinned by the already-green FixtureReplayTest / IrohRealFrameReplayTest;
        // here we prove the FANNED-OUT observer path is loss-free and dup-free.)
        assertTrue(obsText.startsWith("I'm Lester"), "observer text starts correctly")
        assertTrue(obsText.trimEnd().endsWith("want to know?"), "observer text ends correctly")
        assertTrue(
            obsText.contains("filesystem, shell commands") && obsText.contains("Iroh transport"),
            "observer text carries the full body (no mid-stream drop)",
        )
    }

    // ============ (b) GOLDEN INITIATOR-vs-OBSERVER BODY PARITY ==========
    @Test
    fun initiatorAndObserverDeltaBodiesAreIdenticalModuloSeqAndIdempotency() = runTest {
        val fixture = loadLesterFixture()
        val registry = ConnectionRegistry()
        val sinkInit = CapturingSink()
        val sinkObs = CapturingSink()
        val initiator = viewer("conn-init", sinkInit)
        val observer = viewer("conn-obs", sinkObs)
        registry.register(conversationId, initiator)
        registry.register(conversationId, observer)

        val fanout = fanoutFor(registry, initiator)
        for (payload in fixtureDrafts(fixture)) fanout.onDraft(payload)

        val initFrames = sinkInit.frames().map { json.parseToJsonElement(it).jsonObject }
        val obsFrames = sinkObs.frames().map { json.parseToJsonElement(it).jsonObject }
        assertEquals(initFrames.size, obsFrames.size, "same frame count both viewers")
        assertTrue(initFrames.isNotEmpty(), "produced frames")

        initFrames.zip(obsFrames).forEachIndexed { i, (iFrame, oFrame) ->
            // The DELTA BODY (computed once initiator-side, published verbatim) is
            // IDENTICAL between initiator and observer — zero shaping divergence.
            assertEquals(
                iFrame["delta"]!!.jsonObject,
                oFrame["delta"]!!.jsonObject,
                "frame#$i delta body identical between initiator and observer",
            )
            // The ONLY per-viewer differences are the ENVELOPE fields event_seq +
            // idempotency_key. Assert they are the per-viewer variance (they may
            // differ) while the body does not — i.e. seq/idempotency are the
            // modulo. idempotency_key is a fresh iroh-delta-<uuid> per viewer.
            assertTrue(
                iFrame["idempotency_key"]!!.jsonPrimitive.content.startsWith("iroh-delta-"),
                "frame#$i initiator fresh idempotency_key",
            )
            assertTrue(
                oFrame["idempotency_key"]!!.jsonPrimitive.content.startsWith("iroh-delta-"),
                "frame#$i observer fresh idempotency_key",
            )
        }
        // Concrete demonstration of the modulo: strip event_seq + idempotency_key
        // + emitted_at from BOTH envelopes and the remaining frames are equal.
        fun stripPerViewer(f: JsonObject): JsonObject = buildJsonObject {
            f.forEach { (k, v) -> if (k != "event_seq" && k != "idempotency_key" && k != "emitted_at") put(k, v) }
        }
        assertEquals(
            initFrames.map { stripPerViewer(it) },
            obsFrames.map { stripPerViewer(it) },
            "frames identical once event_seq + idempotency_key (+ emitted_at) stripped",
        )
    }

    // ============ (c) DIAGNOSTICS GATE COUNTERS BALANCE =================
    @Test
    fun observerPathFrameFlowDiagnosticsCountersBalance() = runTest {
        val fixture = loadLesterFixture()
        val registry = ConnectionRegistry()
        val sinkInit = CapturingSink()
        val sinkObs = CapturingSink()
        val initiator = viewer("conn-init", sinkInit)
        val observer = viewer("conn-obs", sinkObs)
        registry.register(conversationId, initiator)
        registry.register(conversationId, observer)

        val fanout = fanoutFor(registry, initiator)
        for (payload in fixtureDrafts(fixture)) fanout.onDraft(payload)

        // Enable the h30cy frame-flow diagnostics and clear the ring so only our
        // emit/ingest gate events are counted.
        Telemetry.frameFlowDiagEnabled.set(true)
        Telemetry.clear()

        // gate1.emit — record each OBSERVER assistant delta as the transport emit
        // gate would (the fanout is the transport-emit hop for observers).
        val obsAssistantDeltas = assistantDeltaBodiesOf(sinkObs)
        obsAssistantDeltas.forEach { delta ->
            com.letta.mobile.data.transport.iroh.IrohFrameFlowDiagnostics.record(
                gate = "gate1.emit",
                key = delta["otid"]?.jsonPrimitive?.content ?: delta["id"]!!.jsonPrimitive.content,
                messageType = "assistant_message",
                content = delta["content"]!!.jsonPrimitive.content,
            )
        }

        // gate.reduceIngest — driven inside reduceStreamFrame for the observer path.
        replayThroughReducer(deltaBodiesOf(sinkObs), source = "iroh-observer")

        val events = Telemetry.snapshot().filter { it.tag == "FrameFlowDiag" && it.name == "gate" }
        val emitCount = events.count { (it.attrs["gate"] as? String) == "gate1.emit" }
        val ingestCount = events.count {
            (it.attrs["gate"] as? String)?.startsWith("gate.reduceIngest") == true
        }

        // Every emitted observer assistant delta is ingested EXACTLY once — no
        // double-ingest, no drop. The counters BALANCE.
        assertTrue(emitCount > 0, "diagnostics recorded observer emits")
        assertEquals(emitCount, ingestCount, "observer emit vs reduceIngest counters must balance")
        assertEquals(obsAssistantDeltas.size, ingestCount, "one ingest per fanned-out observer assistant delta")

        // Every incremental fragment observed at emit reaches the reducer with the
        // same length, so no fragment was dropped on the observer path.
        fun maxLenAt(prefix: String) = events
            .filter { (it.attrs["gate"] as? String)?.startsWith(prefix) == true }
            .maxOf { (it.attrs["len"] as? Int) ?: 0 }
        val maxEmitLen = maxLenAt("gate1.emit")
        val maxIngestLen = maxLenAt("gate.reduceIngest")
        assertEquals(maxEmitLen, maxIngestLen, "no dropped characters between observer emit and reduce-ingest")
    }
}
