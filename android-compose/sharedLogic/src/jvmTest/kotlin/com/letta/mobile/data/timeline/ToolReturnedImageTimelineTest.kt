package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.controller.node.iroh.MessageListWireProjection
import com.letta.mobile.data.controller.node.iroh.LocalBackendMessageProjection
import com.letta.mobile.data.controller.node.iroh.LocalBackendStoreSupport
import com.letta.mobile.data.controller.node.iroh.MessageSidecars
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.transport.iroh.IrohStreamDeltaServerFrameMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolReturnedImageTimelineTest : TimelineStreamReducerFixture() {
    private val json = Json { ignoreUnknownKeys = true }
    private val image = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="
    private val call = json.parseToJsonElement(
        """{"id":"call-row","message_type":"tool_call_message",""" +
            """"tool_call":{"tool_call_id":"read-call","name":"Read","arguments":"{}"}}""",
    ).jsonObject

    private fun result(text: String): JsonObject = buildJsonObject {
        put("id", "return-row")
        put("message_type", "tool_return_message")
        put("tool_call_id", "read-call")
        put("status", "success")
        put("tool_return", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", text) })
            add(buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64"); put("media_type", "image/png"); put("data", image)
                })
            })
        })
    }

    private fun assertRendered(timeline: Timeline) {
        val ui = requireNotNull(timelineEventToUiMessage(timeline.events.single()))
        val attachments = ui.toolCalls.orEmpty().single().generatedImageAttachments
        assertEquals(listOf(image), attachments.map { it.base64 }, "Read tool row must expose returned image bytes")
        assertEquals(listOf("image/png"), attachments.map { it.mediaType })
        assertTrue(ui.attachments.isEmpty(), "tool image must not also render as a message attachment")
    }

    @Test
    fun liveReadEnvelopeRendersImageOnToolRow() {
        assertRendered(reduceEnvelopes(listOf(call, result("[Image: pixel.png]"))))
    }

    @Test
    fun liveCanonicalListedReadReturnRendersImageOnToolRow() {
        val result = result("[Image: pixel.png]")
        val listed = buildJsonObject {
            put("id", "return-row")
            put("message_type", "tool_return_message")
            put("tool_returns", buildJsonArray {
                add(buildJsonObject {
                    put("tool_call_id", "read-call")
                    put("status", "success")
                    put("tool_return", result.getValue("tool_return"))
                })
            })
        }
        assertRendered(reduceEnvelopes(listOf(call, listed)))
    }

    private fun reduceEnvelopes(deltas: List<JsonObject>): Timeline {
        var timeline = timeline()
        for (delta in deltas) {
            val envelope = buildJsonObject { put("type", "stream_delta"); put("delta", delta) }
            val frames = IrohStreamDeltaServerFrameMapper.map(
                RuntimeEventPayload.RemoteStreamFrame("frame", null, null, envelope.toString()),
                IrohStreamDeltaServerFrameMapper.Context("agent", "conv-test", "turn", "run", "2026-10-03T00:00:00Z"),
            )
            for (frame in frames) timeline = reduce(timeline, requireNotNull(WsFrameMapper.toLettaMessage(frame))).next
        }
        return timeline
    }

    @Test
    fun nativeStoredReadResultPreservesImageThroughHydration() {
        val directory = kotlin.io.path.createTempDirectory("read-image").toFile()
        try {
            val projection = LocalBackendMessageProjection(LocalBackendStoreSupport(directory, "http://localhost"))
            val row = buildJsonObject {
                put("id", "return-row")
                put("role", "toolResult")
                put("toolCallId", "read-call")
                put("toolName", "Read")
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "[Image: pixel.png]") })
                    add(buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", image) })
                })
            }
            val projected = projection.localMessageToConversationMessages(
                row, MessageSidecars(emptyMap(), emptyMap(), emptyMap(), emptyMap()),
            )
            val page = MessageListWireProjection.projectMessageList(JsonArray(listOf(call) + projected), "conv-test").jsonArray
            val hydrated = hydrate(page.map {
                json.decodeFromJsonElement(LettaMessage.serializer(), it)
            })
            assertRendered(hydrated)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun hydratedProjectedReadPageRendersImageAndPreviewsLargeText() {
        val original = result("x".repeat(6000))
        val page = MessageListWireProjection.projectMessageList(JsonArray(listOf(call, original)), "conv-test").jsonArray
        val projected = page[1].jsonObject
        val parts = projected.getValue("tool_return").jsonArray
        assertEquals(original.getValue("tool_return").jsonArray[1], parts[1])
        assertTrue(parts[0].jsonObject.getValue("text").jsonPrimitive.content.contains("[truncated"))
        val messages = page.map { json.decodeFromJsonElement(LettaMessage.serializer(), it) }
        assertRendered(hydrate(messages))
    }

    @Test
    fun snapshotEnrichmentRecoversImagesMissingFromLiveTextOnlyReturn() {
        val textOnlyReturn = json.parseToJsonElement(
            """{"id":"return-row","message_type":"tool_return_message",""" +
                """"tool_returns":[{"tool_call_id":"read-call","status":"success","tool_return":"[Image: pixel.png]"}]}""",
        ).jsonObject
        val seeded = reduceEnvelopes(listOf(call, textOnlyReturn))
        assertTrue(seeded.events.single().attachments.isEmpty())
        val snapshot = listOf(call, result("[Image: pixel.png]")).map {
            json.decodeFromJsonElement(LettaMessage.serializer(), it)
        }
        assertRendered(enrichTimelineFromSnapshot(seeded, snapshot))
    }

    private fun hydrate(messages: List<LettaMessage>): Timeline = TimelineHydrationReducer.reduce(
        conversationId = "conv-test",
        serverMessagesChronological = messages,
        timelineBeforeFetch = timeline(),
        currentTimeline = timeline(),
        diskRecords = emptyList(),
    ).timeline
}
