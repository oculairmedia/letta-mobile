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
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * letta-mobile-aggeh: the INITIATOR's QUIC write must not be awaited per frame.
 *
 * Awaiting it was the last synchronous network hop in the App Server drain path.
 * Every hop from the transport socket to this fanout is a suspending send over a
 * bounded buffer, so a slow initiator link propagated backpressure all the way
 * back to KtorAppServerWebSocketTransport's 1024-frame stream delivery queue,
 * whose overflow policy tears down the WHOLE shared App Server generation --
 * control included, every surface at once.
 *
 * These tests pin the two properties that make not-awaiting safe:
 *  - a slow initiator does not delay the broadcast of ordinary deltas, and
 *  - a TERMINAL delta is still on the wire before the broadcast returns, so a
 *    turn never completes with its terminal frame still queued.
 */
class ConversationTurnFanoutInitiatorBackpressureTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val runtime = AppServerRuntimeScope("agent-1", "conv-C")
    private val conversationId = "conv-C"

    private class FakeSink(val delayMs: Long = 0L) : ViewerFrameSink {
        val chunks = mutableListOf<ByteArray>()
        override suspend fun writeAll(bytes: ByteArray) {
            if (delayMs > 0) delay(delayMs.milliseconds)
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

    private fun fanoutFor(
        registry: ConnectionRegistry,
        initiator: ViewerHandle?,
        observerWrites: ObserverWriteQueue? = null,
    ) = ConversationTurnFanout(
        conversationId = conversationId,
        runtime = runtime,
        remoteEndpointId = "conn-init",
        viewersFor = { conv -> registry.viewersFor(conv) },
        initiatorViewer = initiator,
        trackInitiatorFrame = {},
        unregisterViewer = { conv, v -> registry.unregister(conv, v) },
        observerWriteTimeoutMs = 200L,
        observerWrites = observerWrites,
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

    private fun eventSeqs(frames: List<String>): List<Long> = frames.map {
        json.parseToJsonElement(it).jsonObject["event_seq"]!!.jsonPrimitive.long
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun slowInitiatorDoesNotDelayOrdinaryDeltaBroadcasts() = runTest {
        val registry = ConnectionRegistry()
        // An initiator on a slow link: every write costs 5s of wire time.
        val sinkInit = FakeSink(delayMs = 5_000L)
        val initiator = viewer("conn-init", sinkInit)
        registry.register(conversationId, initiator)

        val fanout = fanoutFor(registry, initiator, ObserverWriteQueue(backgroundScope))

        val start = testScheduler.currentTime
        fanout.onDraft(assistantDelta("Hel"))
        fanout.onDraft(assistantDelta("lo wor"))
        fanout.onDraft(assistantDelta("ld"))
        val elapsed = testScheduler.currentTime - start

        // Before this change each of these awaited a 5s write: 15s of drain
        // stall, which is what overflowed the transport's stream queue.
        assertEquals(0L, elapsed, "a slow initiator must not delay ordinary delta broadcasts")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun terminalDeltaIsOnTheWireBeforeTheBroadcastReturns() = runTest {
        val registry = ConnectionRegistry()
        val sinkInit = FakeSink(delayMs = 5_000L)
        val initiator = viewer("conn-init", sinkInit)
        registry.register(conversationId, initiator)

        val fanout = fanoutFor(registry, initiator, ObserverWriteQueue(backgroundScope))

        fanout.onDraft(assistantDelta("Hel"))
        fanout.onDraft(assistantDelta("lo world"))
        fanout.onDraft(terminal())

        // The terminal drains the chain, so everything queued ahead of it has
        // landed by the time onDraft returns -- the turn cannot complete with
        // its terminal frame still pending.
        val frames = sinkInit.frames()
        assertEquals(listOf("Hel", "Hello world"), assistantContents(frames), "initiator ordered deltas")
        assertEquals(1, countTerminals(frames), "terminal flushed before broadcast returned")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun initiatorFrameOrderAndEventSeqStayMonotonicThroughTheQueue() = runTest {
        val registry = ConnectionRegistry()
        val sinkInit = FakeSink(delayMs = 3L)
        val initiator = viewer("conn-init", sinkInit)
        registry.register(conversationId, initiator)

        val fanout = fanoutFor(registry, initiator, ObserverWriteQueue(backgroundScope))

        // Assistant text is CUMULATIVE: each delta carries an incremental chunk
        // and the fanout emits the running total, so the wire content is the
        // sequence of prefixes.
        val chunks = listOf("a", "b", "c", "d", "e")
        val expectedOnWire = listOf("a", "ab", "abc", "abcd", "abcde")
        chunks.forEach { fanout.onDraft(assistantDelta(it)) }
        fanout.onDraft(terminal())

        val frames = sinkInit.frames()
        assertEquals(expectedOnWire, assistantContents(frames), "per-viewer frame order preserved")
        val seqs = eventSeqs(frames)
        assertEquals(seqs.sorted(), seqs, "event_seq must be monotonic in wire order")
        assertEquals(seqs.distinct().size, seqs.size, "event_seq must not repeat")
    }

    @Test
    fun withoutAQueueEveryWriteStaysSynchronous() = runTest {
        val registry = ConnectionRegistry()
        val sinkInit = FakeSink()
        val initiator = viewer("conn-init", sinkInit)
        registry.register(conversationId, initiator)

        // Legacy/test construction: no ObserverWriteQueue wired in.
        val fanout = fanoutFor(registry, initiator, observerWrites = null)

        fanout.onDraft(assistantDelta("Hel"))
        // With no queue the write completed inline, before onDraft returned.
        assertTrue(sinkInit.frames().isNotEmpty(), "no-queue path writes synchronously")

        fanout.onDraft(assistantDelta("lo world"))
        fanout.onDraft(terminal())
        val frames = sinkInit.frames()
        assertEquals(listOf("Hel", "Hello world"), assistantContents(frames))
        assertEquals(1, countTerminals(frames))
    }
}
