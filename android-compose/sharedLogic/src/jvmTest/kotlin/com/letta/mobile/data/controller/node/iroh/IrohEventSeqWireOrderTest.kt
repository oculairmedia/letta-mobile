package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.50: every frame on one Iroh connection's stream carries an event_seq from
 * one counter, and several write chains share the connection's [IrohViewerHandle]: the
 * initiator queue, the node-owned observer queue, the initiator-only busy rejection (a direct
 * write) and redial replay. The seq must be drawn under the stream lock, or a writer that is
 * preempted between drawing its seq and taking the lock lands behind a later seq, which the
 * client's EventSeqGapDetector reads as Gap then Stale.
 *
 * [PreemptedFirstLock] makes that preemption deterministic: the first writer to reach the
 * lock parks there until the test releases it, after the racing chain has written.
 */
class IrohEventSeqWireOrderTest {

    private val runtime = AppServerRuntimeScope("agent-1", "conv-1")

    @Test
    fun busyRejectionRacingALiveDeltaBroadcastKeepsWireSeqStrictlyIncreasing() = runTest {
        val wire = WireCapture()
        val lock = PreemptedFirstLock()
        val viewer = wire.viewer(lock)
        val busyTurn = initiatorFanout(viewer, initiatorWrites = null)
        val liveTurn = initiatorFanout(viewer, initiatorWrites = ObserverWriteQueue(backgroundScope))

        launch { busyTurn.emitInitiatorOnlyBusyRejection("turn already active") }
        runCurrent()
        assertTrue(lock.holdingFirstWriter, "the busy rejection must be parked at the lock")

        liveTurn.relayServerDelta(assistantFrame("Hel"))
        liveTurn.relayServerDelta(assistantFrame("Hello"))
        advanceUntilIdle()
        lock.release()
        advanceUntilIdle()

        val frames = wire.frames()
        assertEquals(listOf("assistant_message", "assistant_message", "error_message"), frames.map(::messageType))
        assertSeqsStrictlyIncreasing(frames)
    }

    @Test
    fun parkedFrameReplayRacingLiveDeltasKeepsWireSeqStrictlyIncreasing() = runTest {
        val wire = WireCapture()
        val lock = PreemptedFirstLock()
        val viewer = wire.viewer(lock)
        val liveTurn = initiatorFanout(viewer, initiatorWrites = ObserverWriteQueue(backgroundScope))
        val parked = listOf(
            buildJsonObject { put("message_type", "assistant_message"); put("otid", "otid-old"); put("content", "old") },
            buildJsonObject { put("message_type", "stop_reason"); put("stop_reason", "end_turn") },
        ).joinToString("\n")

        launch { replayParkedDeltas(viewer, runtime, parked) }
        runCurrent()
        assertTrue(lock.holdingFirstWriter, "the replay must be parked at the lock")

        liveTurn.relayServerDelta(assistantFrame("new"))
        liveTurn.relayServerDelta(assistantFrame("newer"))
        advanceUntilIdle()
        lock.release()
        advanceUntilIdle()

        val frames = wire.frames()
        assertEquals(
            listOf("assistant_message", "assistant_message", "assistant_message", "stop_reason"),
            frames.map(::messageType),
        )
        assertEquals("cm-stream-otid-old", frames[2]["delta"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertSeqsStrictlyIncreasing(frames)
    }

    @Test
    fun protocolFrameRacingABroadcastKeepsWireSeqStrictlyIncreasing() = runTest {
        val wire = WireCapture()
        val lock = PreemptedFirstLock()
        val viewer = wire.viewer(lock)

        launch { viewer.writeProtocolFrame("turn_finished", runtime, buildJsonObject { put("status", "completed") }) }
        runCurrent()
        assertTrue(lock.holdingFirstWriter, "the protocol frame must be parked at the lock")

        assertTrue(viewer.writeBroadcastFrame(runtime, buildJsonObject { put("message_type", "assistant_message") }))
        lock.release()
        advanceUntilIdle()

        val frames = wire.frames()
        assertEquals(listOf("stream_delta", "turn_finished"), frames.map { it["type"]!!.jsonPrimitive.content })
        assertSeqsStrictlyIncreasing(frames)
    }

    @Test
    fun failedReplayWriteThrowsSoTheCallerReparks() = runTest {
        val viewer = IrohViewerHandle(
            connectionId = "phone-dead",
            sink = object : ViewerFrameSink {
                override suspend fun writeAll(bytes: ByteArray) = throw IllegalStateException("stream reset")
            },
            eventSeq = IrohEventSeqAllocator.newConnectionSeq(),
            streamWriteMutex = Mutex(),
            frameParts = { false },
            maxFrameBytes = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
        )
        val parked = buildJsonObject { put("message_type", "stop_reason") }.toString()

        val outcome = runCatching { replayParkedDeltas(viewer, runtime, parked) }

        assertFalse(outcome.isSuccess, "a failed replay write must surface so the tail is re-parked")
    }

    private fun TestScope.initiatorFanout(viewer: IrohViewerHandle, initiatorWrites: ObserverWriteQueue?) =
        ConversationTurnFanout(
            conversationId = runtime.conversationId,
            runtime = runtime,
            viewersFor = { setOf(viewer) },
            initiatorViewer = viewer,
            observerWrites = ObserverWriteQueue(backgroundScope),
            initiatorWrites = initiatorWrites,
        )

    private fun assistantFrame(text: String): JsonObject = buildJsonObject {
        put("type", "stream_delta")
        put(
            "delta",
            buildJsonObject {
                put("message_type", "assistant_message")
                put("id", "cm-stream-otid-live")
                put("content", text)
            },
        )
    }

    private fun messageType(frame: JsonObject): String =
        frame["delta"]!!.jsonObject["message_type"]!!.jsonPrimitive.content

    private fun assertSeqsStrictlyIncreasing(frames: List<JsonObject>) {
        val seqs = frames.map { it["event_seq"]!!.jsonPrimitive.long }
        assertEquals(
            (seqs.first() until seqs.first() + seqs.size).toList(),
            seqs,
            "wire event_seq must be contiguous and increasing in wire order",
        )
    }

    /** Decodes everything the connection's stream receives, in wire order. */
    private class WireCapture {
        private val decoder = IrohFrameCodec.Decoder(
            IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
            IrohFrameCodec.DEFAULT_MAX_REASSEMBLED_BYTES,
        )
        private val raw = mutableListOf<String>()

        fun viewer(lock: Mutex) = IrohViewerHandle(
            connectionId = "phone-1",
            sink = object : ViewerFrameSink {
                override suspend fun writeAll(bytes: ByteArray) {
                    raw += decoder.feed(bytes)
                }
            },
            eventSeq = IrohEventSeqAllocator.newConnectionSeq(),
            streamWriteMutex = lock,
            frameParts = { false },
            maxFrameBytes = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
        )

        fun frames(): List<JsonObject> = raw.map { AppServerProtocol.json.parseToJsonElement(it).jsonObject }
    }

    /**
     * The stream lock, except that the first writer to call [lock] parks until [release]: the
     * deterministic stand-in for a thread being preempted between entering its write and
     * acquiring the lock.
     */
    private class PreemptedFirstLock(private val delegate: Mutex = Mutex()) : Mutex by delegate {
        private val gate = CompletableDeferred<Unit>()
        private var armed = true
        var holdingFirstWriter = false
            private set

        override suspend fun lock(owner: Any?) {
            if (armed) {
                armed = false
                holdingFirstWriter = true
                gate.await()
                holdingFirstWriter = false
            }
            delegate.lock(owner)
        }

        fun release() {
            gate.complete(Unit)
        }
    }
}
