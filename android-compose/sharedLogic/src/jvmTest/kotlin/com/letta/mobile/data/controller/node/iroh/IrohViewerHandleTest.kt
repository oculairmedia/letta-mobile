package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * eaczz.2 (S2-T): per-observer frame shaping. Two viewers receive the SAME
 * broadcast delta bodies but each re-wraps with ITS OWN monotonic event_seq +
 * fresh idempotency_key, preserving cm-stream tagging, honoring ITS OWN
 * frame-part capability.
 */
class IrohViewerHandleTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val runtime = AppServerRuntimeScope("agent-1", "conv-1")

    /** Capturing sink: records every writeAll (decoded back to a frame string). */
    private class CapturingSink : ViewerFrameSink {
        val chunks = mutableListOf<ByteArray>()
        override suspend fun writeAll(bytes: ByteArray) { chunks.add(bytes) }
        /** Decode captured chunks back to frame strings (single-part path only). */
        fun frames(maxBytes: Int = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES): List<String> {
            val decoder = IrohFrameCodec.Decoder(maxBytes, IrohFrameCodec.DEFAULT_MAX_REASSEMBLED_BYTES)
            val out = mutableListOf<String>()
            chunks.forEach { out += decoder.feed(it) }
            return out
        }
        fun partCount(): Int = chunks.size
    }

    private fun viewer(sink: CapturingSink, frameParts: Boolean) = IrohViewerHandle(
        connectionId = if (frameParts) "conn-A" else "conn-B",
        sink = sink,
        eventSeq = IrohEventSeqAllocator.newConnectionSeq(),
        streamWriteMutex = Mutex(),
        frameParts = { frameParts },
        maxFrameBytes = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
    )

    private fun taggedAssistantDelta(otid: String, text: String): JsonObject = buildJsonObject {
        // Already cm-stream tagged (the initiator tags once; viewers do not re-tag).
        put("id", "cm-stream-$otid")
        put("message_type", "assistant_message")
        put("content", text)
    }

    @Test
    fun bothViewersReceiveOrderedFramesWithIndependentMonotonicSeq() = runTest {
        val sinkA = CapturingSink()
        val sinkB = CapturingSink()
        val a = viewer(sinkA, frameParts = true)
        val b = viewer(sinkB, frameParts = false)

        val sequence = listOf(
            taggedAssistantDelta("otid-1", "Hel"),
            taggedAssistantDelta("otid-1", "Hello"),
            buildJsonObject { put("message_type", "tool_call_message"); put("id", "tc-1") },
            buildJsonObject { put("message_type", "tool_return_message"); put("id", "tc-1") },
            buildJsonObject { put("message_type", "stop_reason"); put("stop_reason", "end_turn") },
        )

        // Broadcast each body to BOTH viewers (the fanout core in S4 does this).
        sequence.forEach { delta ->
            assertTrue(a.writeBroadcastFrame(runtime, delta))
            assertTrue(b.writeBroadcastFrame(runtime, delta))
        }

        listOf(sinkA, sinkB).forEach { sink ->
            val frames = sink.frames().map { json.parseToJsonElement(it).jsonObject }
            assertEquals(5, frames.size)
            // event_seq strictly monotonic within this viewer.
            val seqs = frames.map { it["event_seq"]!!.jsonPrimitive.content.toLong() }
            assertEquals(seqs.sorted(), seqs, "event_seq must be monotonic per viewer")
            assertEquals(seqs.toSet().size, seqs.size, "event_seq must be unique per viewer")
            // idempotency_key unique per frame.
            val keys = frames.map { it["idempotency_key"]!!.jsonPrimitive.content }
            assertEquals(keys.toSet().size, keys.size)
            // cm-stream tag preserved on the assistant deltas.
            val assistantIds = frames.mapNotNull {
                it["delta"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            }.filter { it.startsWith("cm-stream-") }
            assertEquals(2, assistantIds.size)
            // exactly one terminal (stop_reason).
            val terminals = frames.count {
                it["delta"]?.jsonObject?.get("message_type")?.jsonPrimitive?.content == "stop_reason"
            }
            assertEquals(1, terminals)
        }

        // The two viewers have INDEPENDENT seq counters (A's first != B's first
        // only by allocator base, but both are internally monotonic — already
        // asserted; here assert they didn't share a counter by checking A's seqs
        // are disjoint from B's, since allocator strides per connection).
        val aSeqs = sinkA.frames().map { json.parseToJsonElement(it).jsonObject["event_seq"]!!.jsonPrimitive.content.toLong() }.toSet()
        val bSeqs = sinkB.frames().map { json.parseToJsonElement(it).jsonObject["event_seq"]!!.jsonPrimitive.content.toLong() }.toSet()
        assertTrue(aSeqs.intersect(bSeqs).isEmpty(), "viewers must not share event_seq ranges")
    }

    @Test
    fun observerWithoutFramePartCapabilityNeverGetsChunkedFrame() = runTest {
        // A frame larger than one part, sent to a NON-frame-part viewer, must be
        // written as a single (large) frame, never chunked.
        val sink = CapturingSink()
        val v = viewer(sink, frameParts = false)
        val big = buildJsonObject {
            put("id", "cm-stream-x")
            put("message_type", "assistant_message")
            put("content", "x".repeat(200_000))
        }
        assertTrue(v.writeBroadcastFrame(runtime, big))
        // Single writeAll (no frame_part chunking) for a non-capable peer.
        assertEquals(1, sink.partCount())
    }

    @Test
    fun writeFrameReturnsFalseOnSinkFailureWithoutThrowing() = runTest {
        val failing = object : ViewerFrameSink {
            override suspend fun writeAll(bytes: ByteArray) { throw RuntimeException("dead stream") }
        }
        val v = IrohViewerHandle(
            connectionId = "conn-dead",
            sink = failing,
            eventSeq = IrohEventSeqAllocator.newConnectionSeq(),
            streamWriteMutex = Mutex(),
            frameParts = { false },
            maxFrameBytes = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
        )
        // Must NOT throw (a dead observer can't block the initiator) and returns false.
        assertFalse(v.writeBroadcastFrame(runtime, buildJsonObject { put("message_type", "assistant_message") }))
    }
}
