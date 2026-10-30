package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEvent
import com.letta.mobile.data.timeline.snapshot.toStoredTimelineEvent
import com.letta.mobile.data.transport.iroh.IrohStreamDeltaServerFrameMapper
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadImageMultiplicityTest : TimelineStreamReducerFixture() {
    private val json = Json { ignoreUnknownKeys = true }
    private val image = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="
    private val call = """{"id":"call-row","message_type":"tool_call_message","tool_call":
        {"tool_call_id":"read-call","name":"Read","arguments":"{}"}}"""
    private val result get() = """{"id":"return-row","message_type":"tool_return_message","tool_call_id":"read-call",
        "status":"success","tool_return":[{"type":"text","text":"[Image: pixel.png]"},
        {"type":"image","source":{"type":"base64","media_type":"image/png","data":"$image"}}]}"""

    private fun messages(vararg rows: String): List<LettaMessage> = rows.map {
        json.decodeFromString(LettaMessage.serializer(), it)
    }

    private fun live(vararg rows: String): Timeline {
        var state = timeline()
        rows.forEach { row ->
            val delta = json.parseToJsonElement(row).jsonObject
            val envelope = JsonObject(mapOf("delta" to delta, "type" to kotlinx.serialization.json.JsonPrimitive("stream_delta")))
            IrohStreamDeltaServerFrameMapper.map(
                RuntimeEventPayload.RemoteStreamFrame("frame", null, null, envelope.toString()),
                IrohStreamDeltaServerFrameMapper.Context("agent", "conv-test", "turn", "run", "2026-10-03T00:00:00Z"),
            ).forEach { frame -> state = reduce(state, requireNotNull(WsFrameMapper.toLettaMessage(frame))).next }
        }
        return state
    }

    private fun hydrated(vararg rows: String): Timeline = TimelineHydrationReducer.reduce(
        conversationId = "conv-test", serverMessagesChronological = messages(*rows),
        timelineBeforeFetch = timeline(), currentTimeline = timeline(), diskRecords = emptyList(),
    ).timeline

    private fun assertOneImage(state: Timeline) {
        val ui = requireNotNull(timelineEventToUiMessage(state.events.single()))
        assertEquals(1, ui.toolCalls.orEmpty().sumOf { it.generatedImageAttachments.size } + ui.attachments.size)
        assertEquals(listOf(image), ui.toolCalls.orEmpty().single().generatedImageAttachments.map { it.base64 })
        assertEquals(0, ui.attachments.size, "A tool image must not also be a message image")
    }

    @Test fun liveReadRendersExactlyOneImage() = assertOneImage(live(call, result))

    @Test fun hydratedReadRendersExactlyOneImage() = assertOneImage(hydrated(call, result))

    @Test fun liveThenHydrateRendersExactlyOneImage() {
        val seeded = live(call, result)
        val row = seeded.events.single() as TimelineEvent.Confirmed
        val cached = seeded.replaceByServerId(row.copy(attachments = row.attachments.map {
            it.copy(mediaType = "IMAGE/PNG", storedByteSize = 70)
        }.toTimelinePersistentList()))
        assertOneImage(enrichTimelineFromSnapshot(cached, messages(call, result)))
    }

    @Test fun siblingCallDoesNotBorrowReadImage() {
        val siblings = """{"id":"call-row","message_type":"tool_call_message","tool_calls":[
            {"tool_call_id":"read-call","name":"Read","arguments":"{}"},
            {"tool_call_id":"other-call","name":"Bash","arguments":"{}"}]}"""
        listOf(live(siblings, result), hydrated(siblings, result)).forEach { state ->
            val event = state.events.single() as TimelineEvent.Confirmed
            val stored = TimelineSnapshotCodec.json.encodeToString(StoredTimelineEvent.serializer(), event.toStoredTimelineEvent())
            val reopened = TimelineSnapshotCodec.json.decodeFromString(StoredTimelineEvent.serializer(), stored).toConfirmedTimelineEvent()
            assertOwnedImage(event)
            assertOwnedImage(reopened)
        }
    }

    private fun assertOwnedImage(event: TimelineEvent.Confirmed) {
        val ui = requireNotNull(timelineEventToUiMessage(event))
        val calls = ui.toolCalls.orEmpty().associateBy { it.toolCallId }
        assertEquals(1, calls.getValue("read-call").generatedImageAttachments.size)
        assertEquals(0, calls.getValue("other-call").generatedImageAttachments.size,
            "Only the call whose return carried the image owns it")
        assertEquals(0, ui.attachments.size)
    }
}
