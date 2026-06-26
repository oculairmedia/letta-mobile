package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.timeline.MessageSource
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineHydrationReducer
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.TimelineReducerInput
import com.letta.mobile.data.timeline.parseTimelineInstant
import com.letta.mobile.data.timeline.reduceStreamFrame
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatTimelineRestartOverlapTest {
    private val conv = "conv-1"
    private val projector = ChatTimelineProjector()

    private fun confirmed(
        type: TimelineMessageType,
        content: String,
        serverId: String,
        position: Double,
        runId: String? = null,
        seqId: Int? = null,
    ) = TimelineEvent.Confirmed(
        position = position,
        otid = "server-$serverId",
        content = content,
        serverId = serverId,
        messageType = type,
        runId = runId,
        stepId = null,
        date = parseTimelineInstant("2026-04-19T06:00:00Z"),
        toolCalls = persistentListOf(),
        approvalRequestId = null,
        approvalDecided = false,
        toolReturnContent = null,
        toolReturnIsError = false,
        toolReturnContentByCallId = persistentMapOf(),
        toolReturnIsErrorByCallId = persistentMapOf(),
        attachments = persistentListOf(),
        source = MessageSource.LETTA_SERVER,
        seqId = seqId,
    )

    @Test
    fun restartReconnectReplayOverlapIsIdempotent() {
        // 1. Simulate persisted timeline page (e.g. loaded from DB on restart)
        val persistedEvents = listOf(
            confirmed(TimelineMessageType.USER, "hello", "user-1", 1.0),
            confirmed(TimelineMessageType.ASSISTANT, "I am here.", "assistant-1", 2.0, runId = "run-1", seqId = 1),
            confirmed(TimelineMessageType.ASSISTANT, "What do you need?", "assistant-2", 3.0, runId = "run-1", seqId = 2)
        )
        val initialTimeline = Timeline(conversationId = conv, events = persistedEvents.toPersistentList(), stablePrefixVersion = 1)

        // First projection
        val initialProjection = projector.project(initialTimeline, projector.olderPrefixFor(conv), ChatUiState())
        assertEquals(3, initialProjection.ui.size)
        
        // Find the index of assistant messages, order may change because older messages could prepend or projector re-orders
        val userIdx = initialProjection.ui.indexOfFirst { it.role == "user" }
        val a1Idx = initialProjection.ui.indexOfFirst { it.content == "I am here." }
        val a2Idx = initialProjection.ui.indexOfFirst { it.content == "What do you need?" }
        
        val a1Id = initialProjection.ui[a1Idx].id
        val a2Id = initialProjection.ui[a2Idx].id

        // 2. Hydrate from server sync/backfill with the exact same content (overlap)
        val backfillMessages = listOf(
            AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("I am here."), runId = "run-1", seqId = 1, date = "2026-04-19T06:00:00Z"),
            AssistantMessage(id = "assistant-2", contentRaw = JsonPrimitive("What do you need?"), runId = "run-1", seqId = 2, date = "2026-04-19T06:00:00Z")
        )
        val hydratedResult = TimelineHydrationReducer.reduce(
            conversationId = conv,
            serverMessagesChronological = backfillMessages,
            timelineBeforeFetch = Timeline(conv),
            currentTimeline = initialTimeline, // merging into existing
            diskRecords = emptyList()
        )
        val hydratedTimeline = hydratedResult.timeline
        assertEquals(3, hydratedTimeline.events.size, "No duplicates after hydration. Expected 3 events.")

        // 3. Replay live stream events for the same items (e.g. late websocket delivery)
        val frame1 = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("I am here."), runId = "run-1", seqId = 1)
        val stream1 = reduceStreamFrame(TimelineReducerInput(prev = hydratedTimeline, frame = frame1, pendingToolReturnsByCallId = persistentMapOf()))
        
        val frame2 = AssistantMessage(id = "assistant-2", contentRaw = JsonPrimitive("What do you need?"), runId = "run-1", seqId = 2)
        val stream2 = reduceStreamFrame(TimelineReducerInput(prev = stream1.next, frame = frame2, pendingToolReturnsByCallId = persistentMapOf()))

        val overlappedTimeline = stream2.next
        assertEquals(3, overlappedTimeline.events.size, "No duplicates after stream reducer")

        // 4. Project the fully overlapped/replayed timeline
        val overlappedProjection = projector.project(overlappedTimeline, projector.olderPrefixFor(conv), ChatUiState())
        
        // Assert idempotency: stable IDs, no duplicates, no reordered text
        assertEquals(3, overlappedProjection.ui.size)
        
        val newA1Idx = overlappedProjection.ui.indexOfFirst { it.content == "I am here." }
        val newA2Idx = overlappedProjection.ui.indexOfFirst { it.content == "What do you need?" }
        
        assertEquals("I am here.", overlappedProjection.ui[newA1Idx].content)
        assertEquals("What do you need?", overlappedProjection.ui[newA2Idx].content)
        assertEquals(true, newA1Idx < newA2Idx, "First assistant message should be before the second assistant message")
        assertEquals(a1Id, overlappedProjection.ui[newA1Idx].id, "Stable ID for first assistant message")
        assertEquals(a2Id, overlappedProjection.ui[newA2Idx].id, "Stable ID for second assistant message")
    }
}
