package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A connection that reads a conversation through the feed takes its turns from `feed_delta` alone: the turn fanout sends it
 * no `stream_delta` for that conversation (no double bytes, no double fold). A connection that does not read the feed, the
 * turn's own initiator, and a feed reader of ANOTHER conversation keep the stream exactly as before. Real registry, real
 * [IrohViewerHandle]s whose gate is set the way IrohNodeConnection sets it, real [ConversationTurnFanout].
 */
class FeedViewerStreamSplitTest {
    private val conversation = "conv-split"
    private val runtime = AppServerRuntimeScope("agent-1", conversation)

    private class Sink : ViewerFrameSink {
        val chunks = mutableListOf<ByteArray>()
        override suspend fun writeAll(bytes: ByteArray) {
            chunks += bytes
        }

        fun streamDeltas(): Int {
            val decoder = IrohFrameCodec.Decoder(
                IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES, IrohFrameCodec.DEFAULT_MAX_REASSEMBLED_BYTES,
            )
            return chunks.flatMap { decoder.feed(it) }
                .count { Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.content == "stream_delta" }
        }
    }

    private fun viewer(id: String, sink: Sink, feedReads: Set<String> = emptySet()) = IrohViewerHandle(
        connectionId = id,
        sink = sink,
        eventSeq = IrohEventSeqAllocator.newConnectionSeq(),
        streamWriteMutex = Mutex(),
        frameParts = { false },
        maxFrameBytes = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
    ).also { handle -> handle.feedReaderGate = { it.value in feedReads } }

    private fun frame(seq: Long, delta: kotlinx.serialization.json.JsonObject) = RuntimeEventPayload.RemoteStreamFrame(
        frameId = "f-$seq", messageId = null, messageType = null,
        body = buildJsonObject {
            put("type", "stream_delta")
            put("runtime", buildJsonObject { put("agent_id", runtime.agentId); put("conversation_id", runtime.conversationId) })
            put("event_seq", seq)
            put("delta", delta)
        }.toString(),
    )

    private suspend fun runTurn(registry: ConnectionRegistry, initiator: ViewerHandle) {
        val fanout = ConversationTurnFanout(
            conversationId = conversation,
            runtime = runtime,
            delivery = TurnFanoutDelivery(viewersFor = { registry.viewersFor(it) }),
            initiatorViewer = initiator,
        )
        fanout.broadcastUserEcho(clientMessageId = "cm-1", text = "hi", contentParts = null)
        listOf("Hel", "Hello").forEachIndexed { index, text ->
            fanout.onStampedDraft(
                frame(index + 1L, buildJsonObject {
                    put("message_type", "assistant_message"); put("otid", "cm-1"); put("id", "msg-1"); put("content", text)
                }),
            )
        }
        fanout.onStampedDraft(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Completed, reason = "end_turn"))
    }

    @Test
    fun aFeedReaderOfTheConversationReceivesNoStreamDeltaForItWhileEveryOtherViewerKeepsTheStream() = runTest {
        val registry = ConnectionRegistry()
        val initiatorSink = Sink()
        val streamSink = Sink()
        val feedSink = Sink()
        val otherFeedSink = Sink()
        val initiator = viewer("initiator", initiatorSink, feedReads = setOf(conversation))
        registry.register(conversation, initiator)
        registry.register(conversation, viewer("stream-reader", streamSink))
        registry.register(conversation, viewer("feed-reader", feedSink, feedReads = setOf(conversation)))
        registry.register(conversation, viewer("reads-another-feed", otherFeedSink, feedReads = setOf("conv-other")))

        runTurn(registry, initiator)

        assertEquals(0, feedSink.streamDeltas(), "a feed reader of this conversation is pushed feed_delta, never stream_delta")
        val streamed = streamSink.streamDeltas()
        assertTrue(streamed >= 3, "a stream reader keeps the old fanout (echo, reply, terminal); got $streamed")
        assertEquals(streamed, otherFeedSink.streamDeltas(), "reading another conversation's feed changes nothing here")
        assertEquals(streamed, initiatorSink.streamDeltas(), "the initiator keeps its own turn's stream (send, redial parking)")
    }
}
