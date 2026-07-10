package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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
 * eaczz.5 (S5-T, fanout seam): asserts the SHAPE, ORDER, and PARKING semantics of
 * the user-echo the fanout emits. The reducer-level render/dedup contract lives
 * in UserEchoFanoutReducerTest (commonTest); this pins that
 * [ConversationTurnFanout.broadcastUserEcho]:
 *  - emits a `user_message` delta carrying `id = cm-user-<otid>` and `otid = <otid>`
 *    (the dedup id scheme observers/initiator collapse on),
 *  - fans it out to EVERY viewer,
 *  - lands BEFORE the assistant stream frames,
 *  - and does NOT consume an initiator parking slot (parking stays scoped to
 *    assistant/tool/terminal deltas).
 */
class ConversationTurnFanoutUserEchoTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val runtime = AppServerRuntimeScope("agent-1", "conv-C")
    private val conversationId = "conv-C"

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

    private fun assistantDrafts(): List<RuntimeEventPayload> {
        val seq = AtomicLong(0)
        fun raw(delta: JsonObject) = RuntimeEventPayload.RemoteStreamFrame(
            frameId = "f-${UUID.randomUUID()}", messageId = null, messageType = null,
            body = rawStreamDeltaBody(seq.incrementAndGet(), delta),
        )
        return listOf(
            raw(buildJsonObject {
                put("message_type", "assistant_message"); put("otid", "otid-1")
                put("id", "letta-msg-1"); put("content", "Hi there")
            }),
            RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Completed, reason = "end_turn"),
        )
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

    private fun deltaMessageType(frameJson: String): String? =
        json.parseToJsonElement(frameJson).jsonObject["delta"]?.jsonObject
            ?.get("message_type")?.jsonPrimitive?.content

    @Test
    fun userEchoIsFannedToEveryViewerBeforeAssistant() = runTest {
        val registry = ConnectionRegistry()
        val sinkInit = CapturingSink()
        val sinkObs = CapturingSink()
        val initiator = viewer("conn-init", sinkInit)
        val observer = viewer("conn-obs", sinkObs)
        registry.register(conversationId, initiator)
        registry.register(conversationId, observer)

        val fanout = fanoutFor(registry, initiator)
        fanout.broadcastUserEcho(clientMessageId = "cm-1", text = "hello", contentParts = null)
        for (payload in assistantDrafts()) fanout.onDraft(payload)

        listOf(sinkInit to "initiator", sinkObs to "observer").forEach { (sink, who) ->
            val frames = sink.frames()
            // user_message echo first, then assistant, then terminal.
            assertEquals("user_message", deltaMessageType(frames.first()), "$who first frame is user echo")
            val delta = json.parseToJsonElement(frames.first()).jsonObject["delta"]!!.jsonObject
            assertEquals("cm-user-cm-1", delta["id"]!!.jsonPrimitive.content, "$who stable echo id")
            assertEquals("cm-1", delta["otid"]!!.jsonPrimitive.content, "$who echo otid == clientMessageId")
            assertEquals("hello", delta["content"]!!.jsonPrimitive.content, "$who echo content")
            // The echo precedes the assistant delta.
            val types = frames.map { deltaMessageType(it) }
            val echoIdx = types.indexOf("user_message")
            val assistantIdx = types.indexOf("assistant_message")
            assertTrue(echoIdx >= 0 && assistantIdx > echoIdx, "$who echo before assistant")
        }
    }

    @Test
    fun userEchoDoesNotConsumeInitiatorParkingSlot() = runTest {
        val registry = ConnectionRegistry()
        val parked = mutableListOf<String>()
        val initiator = viewer("conn-init", CapturingSink())
        registry.register(conversationId, initiator)

        val fanout = fanoutFor(registry, initiator, parked)
        fanout.broadcastUserEcho(clientMessageId = "cm-1", text = "hello", contentParts = null)
        for (payload in assistantDrafts()) fanout.onDraft(payload)

        // assistant + stop_reason = 2 parked deltas; the user echo must NOT park.
        assertEquals(2, parked.size, "user echo must not consume a parking slot")
        assertTrue(parked.none { it.contains("user_message") }, "no user_message in parked frames")
    }

    @Test
    fun userEchoCarriesContentPartsArray() = runTest {
        val registry = ConnectionRegistry()
        val sinkObs = CapturingSink()
        val observer = viewer("conn-obs", sinkObs)
        registry.register(conversationId, observer)

        val parts = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "pic") })
            add(buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64"); put("media_type", "image/png"); put("data", "ZZZ==")
                })
            })
        }

        val fanout = fanoutFor(registry, initiator = null)
        fanout.broadcastUserEcho(clientMessageId = "cm-img", text = "pic", contentParts = parts)

        val delta = json.parseToJsonElement(sinkObs.frames().first()).jsonObject["delta"]!!.jsonObject
        assertEquals("user_message", delta["message_type"]!!.jsonPrimitive.content)
        // content is the forwarded array (not the plain text).
        assertTrue(delta["content"]!!.toString().contains("ZZZ=="), "content-parts array carried")
    }
}
