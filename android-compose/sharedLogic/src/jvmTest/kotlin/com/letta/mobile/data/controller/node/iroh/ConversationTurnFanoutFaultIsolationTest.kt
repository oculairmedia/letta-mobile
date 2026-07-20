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
 * eaczz.6 (S6-T): fault isolation + observer reconnect — a slow/dead OBSERVER
 * must NEVER block or fail the INITIATOR turn, and lifecycle transitions land
 * consistently. Driven through [ConversationTurnFanout] wired to a real
 * [ConnectionRegistry] whose `unregister` is the de-register-on-failure sink,
 * exactly as [IrohNodeConnection.handleInput] wires it. Fakes let an observer
 * fail or delay its writes.
 *
 * Covers: (1) failing observer -> initiator gets the full ordered sequence +
 * terminal AND is de-registered; (2) slow observer does not delay the
 * initiator's terminal beyond a bound (de-registered after first stall);
 * (3) observer that registers after 2 deltas still converges (gets the rest +
 * terminal); (4) observer disconnect mid-stream removes it from viewersFor.
 */
class ConversationTurnFanoutFaultIsolationTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val runtime = AppServerRuntimeScope("agent-1", "conv-C")
    private val conversationId = "conv-C"

    /** Capturing sink; can fail every write or delay each write to model a wedged peer. */
    private class FakeSink(
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

    private fun viewer(connectionId: String, sink: FakeSink) = IrohViewerHandle(
        connectionId = connectionId,
        sink = sink,
        eventSeq = IrohEventSeqAllocator.newConnectionSeq(),
        streamWriteMutex = Mutex(),
        frameParts = { false },
        maxFrameBytes = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
    )

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

    private val seq = AtomicLong(0)
    private fun assistantDelta(content: String) = RuntimeEventPayload.RemoteStreamFrame(
        frameId = "f-${UUID.randomUUID()}", messageId = null, messageType = null,
        body = rawStreamDeltaBody(seq.incrementAndGet(), buildJsonObject {
            put("message_type", "assistant_message"); put("otid", "otid-1")
            put("id", "letta-msg-1"); put("content", content)
        }),
    )
    private fun terminal() = RuntimeEventPayload.RunLifecycleChanged(
        status = RuntimeRunStatus.Completed, reason = "end_turn",
    )

    /** Fanout wired to a real registry; unregisterViewer routes to registry.unregister. */
    private fun fanoutFor(
        registry: ConnectionRegistry,
        initiator: ViewerHandle?,
        observerWriteTimeoutMs: Long = 200L,
    ) = ConversationTurnFanout(
        conversationId = conversationId,
        runtime = runtime,
        remoteEndpointId = "conn-init",
        viewersFor = { conv -> registry.viewersFor(conv) },
        initiatorViewer = initiator,
        trackInitiatorFrame = {},
        unregisterViewer = { conv, v -> registry.unregister(conv, v) },
        observerWriteTimeoutMs = observerWriteTimeoutMs,
    )

    private fun countTerminals(frames: List<String>): Int = frames.count {
        json.parseToJsonElement(it).jsonObject["delta"]?.jsonObject
            ?.get("message_type")?.jsonPrimitive?.content == "stop_reason"
    }

    private fun assistantContents(frames: List<String>): List<String> = frames.mapNotNull {
        val d = json.parseToJsonElement(it).jsonObject["delta"]?.jsonObject ?: return@mapNotNull null
        if (d["message_type"]?.jsonPrimitive?.content == "assistant_message") {
            d["content"]?.jsonPrimitive?.content
        } else null
    }

    @Test
    fun failingObserverIsDeregisteredAndInitiatorTurnCompletes() = runTest {
        val registry = ConnectionRegistry()
        val sinkInit = FakeSink()
        val sinkObs = FakeSink(failWrites = true)
        val initiator = viewer("conn-init", sinkInit)
        val observer = viewer("conn-obs", sinkObs)
        registry.register(conversationId, initiator)
        registry.register(conversationId, observer)

        val fanout = fanoutFor(registry, initiator)
        // Full ordered turn: two assistant deltas + terminal. Must NOT throw.
        fanout.onDraft(assistantDelta("Hel"))
        fanout.onDraft(assistantDelta("Hello world"))
        fanout.onDraft(terminal())

        // Initiator got the full ordered sequence + exactly one terminal.
        val frames = sinkInit.frames()
        assertEquals(listOf("Hel", "Hello world"), assistantContents(frames), "initiator ordered deltas")
        assertEquals(1, countTerminals(frames), "initiator got terminal (turn completed)")

        // The failing observer was de-registered from the SAME registry the
        // fanout reads from — the initiator remains.
        val remaining = registry.viewersFor(conversationId)
        assertFalse(remaining.any { it.connectionId == "conn-obs" }, "failing observer de-registered")
        assertTrue(remaining.any { it.connectionId == "conn-init" }, "initiator still registered")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun slowObserverDoesNotDelayInitiatorTerminalBeyondABound() = runTest {
        val registry = ConnectionRegistry()
        val sinkInit = FakeSink()
        // Observer stalls far longer than the (short) observer write timeout.
        val sinkObs = FakeSink(delayMs = 10_000L)
        val initiator = viewer("conn-init", sinkInit)
        val observer = viewer("conn-obs", sinkObs)
        registry.register(conversationId, initiator)
        registry.register(conversationId, observer)

        // Short observer timeout; runTest virtual clock makes the bound explicit.
        val fanout = fanoutFor(registry, initiator, observerWriteTimeoutMs = 200L)

        val start = testScheduler.currentTime
        fanout.onDraft(assistantDelta("Hel"))       // first delta: observer times out -> dropped
        fanout.onDraft(assistantDelta("Hello world"))
        fanout.onDraft(terminal())
        val elapsed = testScheduler.currentTime - start

        // Initiator got the full sequence + terminal.
        val frames = sinkInit.frames()
        assertEquals(listOf("Hel", "Hello world"), assistantContents(frames))
        assertEquals(1, countTerminals(frames))

        // The dead observer is de-registered after the first stall, so the
        // WHOLE turn is bounded by ~one timeout window (not one per delta).
        assertTrue(elapsed <= 1_000L, "turn must not stall beyond a bound; elapsed=$elapsed")
        assertFalse(
            registry.viewersFor(conversationId).any { it.connectionId == "conn-obs" },
            "slow observer de-registered",
        )
    }

    @Test
    fun observerJoiningMidTurnConvergesToFinal() = runTest {
        val registry = ConnectionRegistry()
        val sinkInit = FakeSink()
        val initiator = viewer("conn-init", sinkInit)
        registry.register(conversationId, initiator)

        val fanout = fanoutFor(registry, initiator)
        // Two deltas broadcast BEFORE the observer joins.
        fanout.onDraft(assistantDelta("Hel"))
        fanout.onDraft(assistantDelta("Hello wor"))

        // Observer registers mid-turn (as its own message.list subscribe would).
        val sinkObs = FakeSink()
        val observer = viewer("conn-obs", sinkObs)
        registry.register(conversationId, observer)

        // Remaining cumulative delta + terminal.
        fanout.onDraft(assistantDelta("Hello world"))
        fanout.onDraft(terminal())

        // The mid-turn joiner received the REMAINING cumulative delta (which
        // carries the full text so far) + the terminal — converges to final.
        val obsFrames = sinkObs.frames()
        assertEquals(listOf("Hello world"), assistantContents(obsFrames), "joiner gets remaining cumulative delta")
        assertEquals(1, countTerminals(obsFrames), "joiner gets terminal")
        // The final assistant text the joiner holds == the initiator's final text.
        assertEquals(assistantContents(sinkInit.frames()).last(), assistantContents(obsFrames).last())
    }

    @Test
    fun observerDisconnectMidStreamRemovesItFromViewers() = runTest {
        val registry = ConnectionRegistry()
        val sinkInit = FakeSink()
        val sinkObs = FakeSink()
        val initiator = viewer("conn-init", sinkInit)
        val observer = viewer("conn-obs", sinkObs)
        registry.register(conversationId, initiator)
        registry.register(conversationId, observer)

        val fanout = fanoutFor(registry, initiator)
        fanout.onDraft(assistantDelta("Hel"))

        // Observer disconnects mid-stream (eaczz.1 unregisterAll on disconnect).
        registry.unregisterAll("conn-obs")
        assertFalse(
            registry.viewersFor(conversationId).any { it.connectionId == "conn-obs" },
            "disconnected observer removed from viewersFor",
        )

        // Broadcaster stops writing to it: it received only the pre-disconnect delta.
        val before = sinkObs.frames().size
        fanout.onDraft(assistantDelta("Hello world"))
        fanout.onDraft(terminal())
        assertEquals(before, sinkObs.frames().size, "no writes after disconnect")

        // Initiator unaffected: full sequence + terminal.
        assertEquals(1, countTerminals(sinkInit.frames()))
    }
}
