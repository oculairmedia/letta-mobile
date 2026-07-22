package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.delay
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

import kotlin.time.Duration.Companion.milliseconds
/**
 * eaczz.7 (TEST-S7) — THE hermetic multi-connection fanout test matrix and the
 * PRIMARY required-green regression gate for the Iroh multi-client live-sync
 * feature. Drives 2+ simulated viewers against a REAL [ConnectionRegistry] +
 * REAL [IrohViewerHandle]s over [CapturingSink]s and a scripted delta/controller
 * flow through the real [ConversationTurnFanout] seam — the exact seam
 * IrohNodeConnection.handleInput drives per draft. NO real QUIC, NO device,
 * deterministic (runTest virtual time, no sleeps).
 *
 * This suite CONSOLIDATES the whole matrix as the named gate so it stands alone
 * as the documented regression shield. Some cases overlap existing focused
 * suites (ConversationTurnFanoutTest, ...UserEchoTest, ...FaultIsolationTest,
 * IrohViewerHandleTest, ConnectionRegistryTest) — those are cross-referenced in
 * each case's doc; the NEW coverage this gate adds is CONVERSATION ISOLATION
 * (case 3) and CONCURRENT TURNS NO CROSS-TALK (case 4), which no prior suite
 * exercised. Every case is asserted here regardless so the gate is
 * self-contained.
 *
 * Tested at the [ConversationTurnFanout] level (not raw handleInput) because
 * handleInput hard-requires a JNI computer.iroh.SendStream that cannot be faked;
 * the fanout owns 100% of the frame-shaping + fanout + parking-hook + fault
 * isolation logic handleInput's collect-loop delegates to.
 */
class IrohFanoutServeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- Fakes / helpers -------------------------------------------------

    /** Capturing sink: records every writeAll; can fail or delay to model a wedged peer. */
    private class CapturingSink(
        val failWrites: Boolean = false,
        val delayMs: Long = 0L,
    ) : ViewerFrameSink {
        val chunks = mutableListOf<ByteArray>()
        override suspend fun writeAll(bytes: ByteArray) {
            if (delayMs > 0) delay(delayMs.milliseconds)
            if (failWrites) throw RuntimeException("dead observer stream")
            chunks.add(bytes)
        }
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

    private fun runtimeFor(conversationId: String) = AppServerRuntimeScope("agent-1", conversationId)

    /** A full upstream wire frame body, as DefaultAppServerController/ProbeStub emits. */
    private fun rawStreamDeltaBody(runtime: AppServerRuntimeScope, seq: Long, delta: JsonObject): String =
        buildJsonObject {
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

    /**
     * A fanout wired to a real registry, exactly as IrohNodeConnection.handleInput
     * wires it: viewersFor -> registry, unregisterViewer -> registry.unregister,
     * trackInitiatorFrame -> parked list. Each turn/conversation gets its own
     * fanout instance (as the real per-turn seam does).
     */
    private fun fanoutFor(
        conversationId: String,
        registry: ConnectionRegistry,
        initiator: ViewerHandle?,
        parked: MutableList<String> = mutableListOf(),
        observerWriteTimeoutMs: Long = 200L,
    ) = ConversationTurnFanout(
        conversationId = conversationId,
        runtime = runtimeFor(conversationId),
        remoteEndpointId = initiator?.connectionId ?: "conn-init",
        viewersFor = { conv -> registry.viewersFor(conv) },
        initiatorViewer = initiator,
        trackInitiatorFrame = { parked.add(it) },
        unregisterViewer = { conv, v -> registry.unregister(conv, v) },
        observerWriteTimeoutMs = observerWriteTimeoutMs,
    )

    /**
     * Scripted controller-style flow for [conversationId]: tool_call, assistant
     * multi-delta (incremental), tool_return, terminal stop_reason — the shape
     * handleInput's collect-loop delivers. [otid] scopes the assistant stream so
     * concurrent conversations produce distinct cm-stream ids.
     */
    private fun scriptedDrafts(
        runtime: AppServerRuntimeScope,
        otid: String,
        toolCallId: String,
    ): List<RuntimeEventPayload> {
        val seq = AtomicLong(0)
        fun raw(delta: JsonObject) = RuntimeEventPayload.RemoteStreamFrame(
            frameId = "f-${UUID.randomUUID()}", messageId = null, messageType = null,
            body = rawStreamDeltaBody(runtime, seq.incrementAndGet(), delta),
        )
        return listOf(
            raw(buildJsonObject {
                put("message_type", "tool_call_message")
                put("tool_call", buildJsonObject {
                    put("name", "stub_tool"); put("tool_call_id", toolCallId); put("arguments", "{}")
                })
            }),
            raw(buildJsonObject {
                put("message_type", "assistant_message"); put("otid", otid)
                put("id", "letta-msg-1"); put("content", "Hel")
            }),
            raw(buildJsonObject {
                put("message_type", "assistant_message"); put("otid", otid)
                put("id", "letta-msg-1"); put("content", "lo world")
            }),
            raw(buildJsonObject {
                put("message_type", "tool_return_message"); put("tool_call_id", toolCallId)
                put("tool_return", "ok"); put("status", "success")
            }),
            RuntimeEventPayload.RunLifecycleChanged(
                status = RuntimeRunStatus.Completed, reason = "end_turn",
            ),
        )
    }

    /**
     * Pump a scripted flow through a fanout exactly as handleInput's collect-loop
     * does: terminal-duplicate-skip, failure/cancel dangling flush, then onDraft.
     */
    private suspend fun pump(fanout: ConversationTurnFanout, drafts: List<RuntimeEventPayload>) {
        for (payload in drafts) {
            if (fanout.anyTerminalWritten && fanout.isTerminalLifecycle(payload)) continue
            if (fanout.isFailureOrCancelLifecycle(payload)) fanout.flushOpenToolCalls()
            fanout.onDraft(payload)
        }
    }

    // ---- Frame accessors -------------------------------------------------

    private fun parsed(sink: CapturingSink): List<JsonObject> =
        sink.frames().map { json.parseToJsonElement(it).jsonObject }

    private fun deltaOf(frame: JsonObject): JsonObject? = frame["delta"]?.jsonObject
    private fun deltaTypeOf(frame: JsonObject): String? =
        deltaOf(frame)?.get("message_type")?.jsonPrimitive?.content
    private fun seqOf(frame: JsonObject): Long = frame["event_seq"]!!.jsonPrimitive.content.toLong()

    /** case4 helper: interleave N scripted turns delta-by-delta (max cross-talk pressure). */
    private suspend fun interleaveTurns(vararg turns: Pair<ConversationTurnFanout, List<RuntimeEventPayload>>) {
        val maxLen = turns.maxOf { it.second.size }
        for (i in 0 until maxLen) {
            turns.forEach { (fanout, drafts) ->
                if (i < drafts.size) {
                    val p = drafts[i]
                    if (!(fanout.anyTerminalWritten && fanout.isTerminalLifecycle(p))) fanout.onDraft(p)
                }
            }
        }
    }

    /** case4 helper: a viewer received the full cumulative sequence for exactly ONE otid, one terminal, monotonic seq. */
    private fun assertViewerSawOnlyConversation(sink: CapturingSink, who: String, otid: String) {
        assertEquals(listOf("Hel", "Hello world"), assistantContents(sink), "$who cumulative text")
        val ids = parsed(sink).mapNotNull { deltaOf(it)?.get("id")?.jsonPrimitive?.content }
            .filter { it.startsWith("cm-stream-") }
        assertTrue(ids.all { it == "cm-stream-$otid" }, "$who only $otid assistant ids")
        assertEquals(1, terminalCount(sink), "$who one terminal")
        val seqs = parsed(sink).map { seqOf(it) }
        assertEquals(seqs.sorted(), seqs, "$who seq monotonic under interleave")
    }

    private fun terminalCount(sink: CapturingSink): Int =
        parsed(sink).count { deltaTypeOf(it) == "stop_reason" }

    private fun assistantContents(sink: CapturingSink): List<String> = parsed(sink).mapNotNull {
        val d = deltaOf(it) ?: return@mapNotNull null
        if (d["message_type"]?.jsonPrimitive?.content == "assistant_message") {
            d["content"]?.jsonPrimitive?.content
        } else null
    }

    // ================= CASE 1: FANOUT ORDER + TERMINAL ==================
    // A turn on conn A / conv C fans an ordered assistant(multi-delta) +
    // tool_call + tool_return + stop_reason to BOTH A and B (both viewing C):
    // frames IN ORDER, EXACTLY ONE terminal each, cm-stream-<otid> tagged
    // assistant id, per-viewer strictly-monotonic + unique event_seq.
    // (Cross-refs ConversationTurnFanoutTest.bothViewersReceive...; re-asserted
    // here as the primary gate case.)
    @Test
    fun case1_fanoutOrderAndTerminalToBothViewers() = runTest {
        val registry = ConnectionRegistry()
        val sinkA = CapturingSink()
        val sinkB = CapturingSink()
        val a = viewer("conn-A", sinkA)
        val b = viewer("conn-B", sinkB)
        registry.register("conv-C", a)
        registry.register("conv-C", b)

        pump(
            fanoutFor("conv-C", registry, initiator = a),
            scriptedDrafts(runtimeFor("conv-C"), otid = "otid-C", toolCallId = "tc-C"),
        )

        listOf(sinkA to "A", sinkB to "B").forEach { (sink, who) ->
            val frames = parsed(sink)
            // tool_call, 2 assistant deltas, tool_return, stop_reason = 5 frames, IN ORDER.
            assertEquals(
                listOf("tool_call_message", "assistant_message", "assistant_message", "tool_return_message", "stop_reason"),
                frames.map { deltaTypeOf(it) },
                "$who ordered delta types",
            )
            // Per-viewer strictly-monotonic + unique event_seq.
            val seqs = frames.map { seqOf(it) }
            assertEquals(seqs.sorted(), seqs, "$who event_seq monotonic")
            assertEquals(seqs.toSet().size, seqs.size, "$who event_seq unique")
            // cm-stream tag on the assistant deltas.
            val assistantIds = frames.mapNotNull { deltaOf(it)?.get("id")?.jsonPrimitive?.content }
                .filter { it.startsWith("cm-stream-") }
            assertEquals(2, assistantIds.size, "$who cm-stream tagged assistant deltas")
            assertTrue(assistantIds.all { it == "cm-stream-otid-C" }, "$who cm-stream id")
            // Cumulative accumulation carries full text in the last delta.
            assertEquals(listOf("Hel", "Hello world"), assistantContents(sink), "$who cumulative text")
            // EXACTLY ONE terminal.
            assertEquals(1, terminalCount(sink), "$who exactly one terminal")
        }
        // Viewers draw from disjoint event_seq ranges (allocator strides per conn).
        val aSeqs = parsed(sinkA).map { seqOf(it) }.toSet()
        val bSeqs = parsed(sinkB).map { seqOf(it) }.toSet()
        assertTrue(aSeqs.intersect(bSeqs).isEmpty(), "viewers must not share event_seq")
    }

    // ================= CASE 2: USER ECHO ================================
    // Observer B receives user_message BEFORE the assistant stream; the
    // initiator A also gets exactly ONE user echo row (its optimistic-dedup
    // collapse is a reducer concern, cross-ref UserEchoFanoutReducerTest /
    // ConversationTurnFanoutUserEchoTest — here we assert the fanout emits ONE
    // echo per viewer, ordered before the assistant stream, so no double).
    @Test
    fun case2_userEchoBeforeAssistantStreamNoDouble() = runTest {
        val registry = ConnectionRegistry()
        val sinkA = CapturingSink()
        val sinkB = CapturingSink()
        val a = viewer("conn-A", sinkA)
        val b = viewer("conn-B", sinkB)
        registry.register("conv-C", a)
        registry.register("conv-C", b)

        val fanout = fanoutFor("conv-C", registry, initiator = a)
        fanout.broadcastUserEcho(clientMessageId = "cm-1", text = "hello", contentParts = null)
        pump(fanout, scriptedDrafts(runtimeFor("conv-C"), otid = "otid-C", toolCallId = "tc-C"))

        listOf(sinkA to "A(initiator)", sinkB to "B(observer)").forEach { (sink, who) ->
            val types = parsed(sink).map { deltaTypeOf(it) }
            // EXACTLY ONE user echo row (no double).
            assertEquals(1, types.count { it == "user_message" }, "$who exactly one user echo")
            val echoIdx = types.indexOf("user_message")
            val assistantIdx = types.indexOf("assistant_message")
            assertEquals(0, echoIdx, "$who echo is first frame")
            assertTrue(assistantIdx > echoIdx, "$who echo precedes assistant stream")
            // Echo carries the stable dedup id + otid == clientMessageId.
            val echoDelta = deltaOf(parsed(sink).first())!!
            assertEquals("cm-user-cm-1", echoDelta["id"]!!.jsonPrimitive.content, "$who stable echo id")
            assertEquals("cm-1", echoDelta["otid"]!!.jsonPrimitive.content, "$who echo otid")
        }
    }

    // ================= CASE 3 (NEW): CONVERSATION ISOLATION =============
    // conn D viewing conv X receives NOTHING from a turn on conv C. No prior
    // suite exercised cross-conversation isolation; this is a NEW gate case.
    @Test
    fun case3_conversationIsolationDGetsNothingFromCTurn() = runTest {
        val registry = ConnectionRegistry()
        val sinkA = CapturingSink()   // initiator on conv-C
        val sinkD = CapturingSink()   // viewer on a DIFFERENT conversation, conv-X
        val a = viewer("conn-A", sinkA)
        val d = viewer("conn-D", sinkD)
        registry.register("conv-C", a)
        registry.register("conv-X", d)

        // Full turn on conv-C only.
        pump(
            fanoutFor("conv-C", registry, initiator = a),
            scriptedDrafts(runtimeFor("conv-C"), otid = "otid-C", toolCallId = "tc-C"),
        )

        // conv-C initiator got the full sequence...
        assertEquals(5, parsed(sinkA).size, "conv-C viewer got frames")
        assertEquals(1, terminalCount(sinkA))
        // ...but the conv-X viewer got ABSOLUTELY NOTHING.
        assertTrue(sinkD.frames().isEmpty(), "conv-X viewer must receive nothing from a conv-C turn")
    }

    // ============ CASE 4 (NEW): CONCURRENT TURNS NO CROSS-TALK ==========
    // Turns on conv-C and conv-X run CONCURRENTLY (interleaved on the virtual
    // clock). C viewers only ever see C frames, X viewers only X frames; each
    // viewer's event_seq stays monotonic under interleave. Two separate fanout
    // instances (one per turn/conversation, as the real per-turn seam) share the
    // ONE registry. No prior suite exercised concurrent cross-conversation
    // fanout — NEW gate case.
    @Test
    fun case4_concurrentTurnsNoCrossTalk() = runTest {
        val registry = ConnectionRegistry()
        val sinkC1 = CapturingSink()
        val sinkC2 = CapturingSink()
        val sinkX1 = CapturingSink()
        val sinkX2 = CapturingSink()
        val c1 = viewer("conn-C1", sinkC1)
        val c2 = viewer("conn-C2", sinkC2)
        val x1 = viewer("conn-X1", sinkX1)
        val x2 = viewer("conn-X2", sinkX2)
        registry.register("conv-C", c1)
        registry.register("conv-C", c2)
        registry.register("conv-X", x1)
        registry.register("conv-X", x2)

        val fanoutC = fanoutFor("conv-C", registry, initiator = c1)
        val fanoutX = fanoutFor("conv-X", registry, initiator = x1)
        val draftsC = scriptedDrafts(runtimeFor("conv-C"), otid = "otid-C", toolCallId = "tc-C")
        val draftsX = scriptedDrafts(runtimeFor("conv-X"), otid = "otid-X", toolCallId = "tc-X")

        // Interleave the two turns delta-by-delta on the same coroutine — the
        // most adversarial ordering for cross-talk (both turns in flight at once).
        interleaveTurns(fanoutC to draftsC, fanoutX to draftsX)

        // Each viewer sees ONLY its conversation's content, full sequence, one terminal.
        assertViewerSawOnlyConversation(sinkC1, "C1", "otid-C")
        assertViewerSawOnlyConversation(sinkC2, "C2", "otid-C")
        assertViewerSawOnlyConversation(sinkX1, "X1", "otid-X")
        assertViewerSawOnlyConversation(sinkX2, "X2", "otid-X")
        // Hard cross-talk guard: NO conv-X id ever appears in a C sink and vice versa.
        parsed(sinkC1).plus(parsed(sinkC2)).forEach {
            assertFalse((deltaOf(it)?.toString() ?: "").contains("otid-X"), "no conv-X leakage into C")
        }
        parsed(sinkX1).plus(parsed(sinkX2)).forEach {
            assertFalse((deltaOf(it)?.toString() ?: "").contains("otid-C"), "no conv-C leakage into X")
        }
    }

    // ====== CASE 5: SLOW/DEAD OBSERVER DOES NOT BLOCK INITIATOR =========
    // Observer sink stalls its writes -> initiator completes the FULL turn and
    // the observer is de-registered. Cross-ref
    // ConversationTurnFanoutFaultIsolationTest; re-asserted as part of the gate.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun case5_slowOrDeadObserverDoesNotBlockInitiator() = runTest {
        val registry = ConnectionRegistry()
        val sinkA = CapturingSink()
        // A wedged observer that stalls WAY past the (short) observer timeout.
        val sinkStall = CapturingSink(delayMs = 10_000L)
        val a = viewer("conn-A", sinkA)
        val stalled = viewer("conn-stall", sinkStall)
        registry.register("conv-C", a)
        registry.register("conv-C", stalled)

        val fanout = fanoutFor("conv-C", registry, initiator = a, observerWriteTimeoutMs = 200L)
        val start = testScheduler.currentTime
        pump(fanout, scriptedDrafts(runtimeFor("conv-C"), otid = "otid-C", toolCallId = "tc-C"))
        val elapsed = testScheduler.currentTime - start

        // Initiator got the full ordered sequence + terminal.
        assertEquals(listOf("Hel", "Hello world"), assistantContents(sinkA), "initiator full stream")
        assertEquals(1, terminalCount(sinkA), "initiator got terminal")
        // Bounded: de-registered after the FIRST stall, so total delay ~= one
        // timeout window, not one-per-delta.
        assertTrue(elapsed <= 1_000L, "turn must not stall beyond a bound; elapsed=$elapsed")
        // The wedged observer is de-registered; the initiator remains.
        val remaining = registry.viewersFor("conv-C")
        assertFalse(remaining.any { it.connectionId == "conn-stall" }, "wedged observer de-registered")
        assertTrue(remaining.any { it.connectionId == "conn-A" }, "initiator still registered")
    }

    // Also assert the pure FAILING (throwing) observer variant here so the gate
    // covers dead-stream isolation without relying on the fault-isolation suite.
    @Test
    fun case5b_failingObserverIsIsolatedAndDeregistered() = runTest {
        val registry = ConnectionRegistry()
        val sinkA = CapturingSink()
        val sinkDead = CapturingSink(failWrites = true)
        val a = viewer("conn-A", sinkA)
        val dead = viewer("conn-dead", sinkDead)
        registry.register("conv-C", a)
        registry.register("conv-C", dead)

        pump(
            fanoutFor("conv-C", registry, initiator = a),
            scriptedDrafts(runtimeFor("conv-C"), otid = "otid-C", toolCallId = "tc-C"),
        )

        assertEquals(1, terminalCount(sinkA), "initiator completed despite dead observer")
        assertFalse(
            registry.viewersFor("conv-C").any { it.connectionId == "conn-dead" },
            "failing observer de-registered",
        )
    }

    // ==== CASE 6: OBSERVER JOIN MID-TURN + DISCONNECT MID-TURN ==========
    // JOIN: an observer that registers after the first deltas still converges to
    // final (gets the remaining cumulative delta + terminal — cumulative deltas
    // self-heal the gap). DISCONNECT: an observer removed mid-turn stops
    // receiving writes cleanly; the initiator is unaffected. Cross-ref
    // ConversationTurnFanoutFaultIsolationTest; re-asserted as part of the gate.
    @Test
    fun case6_observerJoinMidTurnConvergesAndDisconnectRemovedCleanly() = runTest {
        val registry = ConnectionRegistry()
        val sinkA = CapturingSink()
        val a = viewer("conn-A", sinkA)
        registry.register("conv-C", a)

        // Build a scripted flow with THREE incremental assistant deltas so we can
        // join between them and prove the fanout emits cumulative text.
        val seq = AtomicLong(0)
        fun raw(delta: JsonObject) = RuntimeEventPayload.RemoteStreamFrame(
            frameId = "f-${UUID.randomUUID()}", messageId = null, messageType = null,
            body = rawStreamDeltaBody(runtimeFor("conv-C"), seq.incrementAndGet(), delta),
        )
        fun assistant(text: String) = raw(buildJsonObject {
            put("message_type", "assistant_message"); put("otid", "otid-C")
            put("id", "letta-msg-1"); put("content", text)
        })
        val fanout = fanoutFor("conv-C", registry, initiator = a)

        // First two deltas BEFORE any observer joins / while a leaver is present.
        val leaverSink = CapturingSink()
        val leaver = viewer("conn-leaver", leaverSink)
        registry.register("conv-C", leaver)
        fanout.onDraft(assistant("Hel"))

        // Leaver disconnects mid-turn (eaczz.1 unregisterAll on disconnect).
        registry.unregisterAll("conn-leaver")
        assertFalse(
            registry.viewersFor("conv-C").any { it.connectionId == "conn-leaver" },
            "disconnected observer removed from viewersFor",
        )
        val leaverFramesAtDisconnect = leaverSink.frames().size

        fanout.onDraft(assistant("lo wor"))

        // Joiner registers mid-turn (its own message.list subscribe would do this).
        val joinerSink = CapturingSink()
        val joiner = viewer("conn-joiner", joinerSink)
        registry.register("conv-C", joiner)

        // Final incremental delta is emitted cumulatively, followed by terminal.
        fanout.onDraft(assistant("ld"))
        fanout.onDraft(RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Completed, reason = "end_turn"))

        // JOINER converged to the FINAL text + one terminal.
        assertEquals("Hello world", assistantContents(joinerSink).last(), "joiner converges to final text")
        assertEquals(1, terminalCount(joinerSink), "joiner got terminal")
        // LEAVER received nothing after its disconnect.
        assertEquals(leaverFramesAtDisconnect, leaverSink.frames().size, "no writes after disconnect")
        // INITIATOR unaffected: full text + one terminal.
        assertEquals("Hello world", assistantContents(sinkA).last(), "initiator final text")
        assertEquals(1, terminalCount(sinkA), "initiator one terminal")
    }

    // ================= CASE 7: SINGLE-VIEWER BASELINE GOLDEN ============
    // A lone initiator produces a golden frame sequence: every frame is a
    // canonical stream_delta envelope, per-connection monotonic event_seq,
    // iroh-delta-<uuid> idempotency_key, cm-stream tag, exactly one terminal —
    // byte-identical (modulo the per-viewer seq/idempotency fields) to the
    // pre-fanout single-client path. This guards the fanout refactor against
    // regressing the common single-client case.
    @Test
    fun case7_singleViewerBaselineGolden() = runTest {
        val registry = ConnectionRegistry()
        val sinkA = CapturingSink()
        val a = viewer("conn-A", sinkA)
        registry.register("conv-C", a)

        pump(
            fanoutFor("conv-C", registry, initiator = a),
            scriptedDrafts(runtimeFor("conv-C"), otid = "otid-C", toolCallId = "tc-C"),
        )

        val frames = parsed(sinkA)
        assertEquals(5, frames.size, "golden frame count")
        // Canonical envelope on every frame.
        frames.forEach { f ->
            assertEquals("stream_delta", f["type"]!!.jsonPrimitive.content, "golden type")
            assertTrue(f.containsKey("runtime"), "golden has runtime")
            assertTrue(f.containsKey("event_seq"), "golden has event_seq")
            assertTrue(f.containsKey("emitted_at"), "golden has emitted_at")
            assertTrue(
                f["idempotency_key"]!!.jsonPrimitive.content.startsWith("iroh-delta-"),
                "golden fresh iroh-delta idempotency_key",
            )
            assertTrue(f.containsKey("delta"), "golden has delta")
        }
        // Golden ordered delta-type sequence.
        assertEquals(
            listOf("tool_call_message", "assistant_message", "assistant_message", "tool_return_message", "stop_reason"),
            frames.map { deltaTypeOf(it) },
            "golden delta type sequence",
        )
        // Golden terminal is exactly {message_type: stop_reason, stop_reason: end_turn}.
        val terminal = deltaOf(frames.last())!!
        assertEquals("stop_reason", terminal["message_type"]!!.jsonPrimitive.content, "golden terminal type")
        assertEquals("end_turn", terminal["stop_reason"]!!.jsonPrimitive.content, "golden terminal reason")
        // Golden: monotonic + unique seq, exactly one terminal.
        val seqs = frames.map { seqOf(it) }
        assertEquals(seqs.sorted(), seqs, "golden monotonic seq")
        assertEquals(seqs.toSet().size, seqs.size, "golden unique seq")
        assertEquals(1, terminalCount(sinkA), "golden exactly one terminal")

        // GOLDEN BODY: the delta bodies (which live inside the envelope; the
        // per-viewer seq/idempotency are envelope fields, NOT body fields) are
        // stable — pin them so a future refactor that changes shaping is caught.
        // Compared as structural JsonObjects (order-independent) so the golden is
        // robust to key-ordering, while still asserting exact key/value content.
        val goldenBodies = frames.map { deltaOf(it)!! }
        val expectedBodies = listOf(
            """{"message_type":"tool_call_message","tool_call":{"tool_call_id":"tc-C","name":"stub_tool","arguments":"{}"}}""",
            """{"message_type":"assistant_message","otid":"otid-C","content":"Hel","id":"cm-stream-otid-C"}""",
            """{"message_type":"assistant_message","otid":"otid-C","content":"Hello world","id":"cm-stream-otid-C"}""",
            """{"message_type":"tool_return_message","tool_call_id":"tc-C","status":"success","tool_return":"ok"}""",
            """{"message_type":"stop_reason","stop_reason":"end_turn"}""",
        ).map { json.parseToJsonElement(it).jsonObject }
        assertEquals(
            expectedBodies,
            goldenBodies,
            "golden delta bodies (per-viewer seq/idempotency live in the envelope, not the body)",
        )
    }
}

