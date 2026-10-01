package com.letta.mobile.data.timeline

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.chat.projection.CanvasArtifactStatus
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.dedupeReasoningAssistantEchoes
import com.letta.mobile.data.chat.projection.hasNoRenderableContent
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.snapshot.toStoredTimelineEvent
import com.letta.mobile.ui.chat.render.ChatTimelineProjector
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.13: every chat projection path carries the compose receipt on the narrating
 * message: the streaming projector (Android/desktop ChatUiState), the canonical live overlay and
 * the settled pager, and the render predicates never fold such a message away.
 */
class CanvasArtifactProjectionPathsTest {
    private val receiptJson = checkNotNull(javaClass.getResource("/canvas/compose/v1/receipt.json")).readText()
    private val requestJson = checkNotNull(javaClass.getResource("/canvas/compose/v1/request.json")).readText()

    private val user = confirmed(1.0, "u1", TimelineMessageType.USER, "Plan my weekend", run = null)
    private val pendingCall = call(2.0, result = null)
    private val publishedCall = call(2.0, result = receiptJson)
    private val narration = confirmed(3.0, "a1", TimelineMessageType.ASSISTANT, "It's on the board.", step = "s2")
    private val followUp = confirmed(4.0, "a2", TimelineMessageType.ASSISTANT, "Anything else?", step = "s3")

    @Test
    fun theStreamingProjectorMovesThePartOntoTheNarrationAndFlipsItInPlace() {
        val projector = ChatTimelineProjector()
        fun project(events: List<TimelineEvent>, version: Long) = projector.project(
            Timeline(conversationId = CONV, events = events.toPersistentList(), stablePrefixVersion = version),
            emptyList(), ChatUiState(), isActiveRunStreaming = true,
        ).ui

        // In flight with nothing narrating it yet: the part waits on the tool call.
        val first = project(listOf(user, pendingCall), 1)
        assertEquals(CanvasArtifactStatus.Pending, first.byId("m-t1").artifacts.single().status)

        // The narration arrives as an appended tail: the part moves, never shown twice.
        val second = project(listOf(user, pendingCall, narration), 2)
        assertTrue(second.byId("m-t1").artifacts.isEmpty())
        assertEquals(CanvasArtifactStatus.Pending, second.byId("a1").artifacts.single().status)

        // The return lands on the call: the same part on the same message, now published.
        val third = project(listOf(user, publishedCall, narration), 3)
        assertEquals(CanvasArtifactStatus.Published, third.byId("a1").artifacts.single().status)
        assertEquals(1, third.sumOf { it.artifacts.size })

        // An unrelated tail tick (the fast path) keeps it.
        val fourth = project(listOf(user, publishedCall, narration, followUp), 4)
        assertEquals(CanvasArtifactStatus.Published, fourth.byId("a1").artifacts.single().status)
        assertEquals(1, fourth.sumOf { it.artifacts.size })
    }

    @Test
    fun theSettledPagerAttachesThePartOnceOverThePage() {
        val events = listOf(user, publishedCall, narration)
        val input = TimelinePageProjectionInput(
            context = TimelineProjectionContext(TimelineScope("backend-1", CONV), ownAgentId = null),
            records = events.mapIndexed { index, event ->
                val body = TimelineSnapshotCodec.json.encodeToString(StoredTimelineEvent.serializer(), event.toStoredTimelineEvent())
                TimelineProjectionRecord(
                    record = TimelineSettledRecord(
                        key = TimelinePageKey(index.toLong(), TimelineMessageId(event.serverId)),
                        contentType = TIMELINE_EVENT_CONTENT_TYPE,
                        body = body.encodeToByteArray(),
                        revision = 1,
                    ),
                    event = event,
                    excluded = false,
                )
            },
            envelope = TimelineRunEnvelope(TimelineRunBoundary.Ends, TimelineRunBoundary.Ends),
        )
        val prepared = input.aggregatePreparedRuns(input.project(DefaultTimelineSettledProjectionAdapter))
        val messages = prepared.mapNotNull { (it.preparedPresentation as? TimelineSettledPresentation.Render)?.item }
            .flatMap { item ->
                when (item) {
                    is ChatRenderItem.Single -> listOf(item.message)
                    is ChatRenderItem.RunBlock -> item.messages.map { it.first }
                }
            }
        assertEquals(1, messages.sumOf { it.artifacts.size })
        assertEquals("weekend-plan", messages.byId("a1").artifacts.single().artifactId)
    }

    @Test
    fun aMessageCarryingAPartIsNeverAnEchoOrAContentlessSegment() {
        val part = com.letta.mobile.data.chat.projection.CanvasArtifactReceipts.attach(listOf(user, publishedCall, narration)).values.single()
        val reasoning = UiMessage(id = "r", role = "assistant", content = "Done.", timestamp = T0, isReasoning = true)
        val echo = UiMessage(id = "e", role = "assistant", content = "Done.", timestamp = T0)
        assertEquals(1, dedupeReasoningAssistantEchoes(listOf(reasoning, echo)).size)
        assertEquals(2, dedupeReasoningAssistantEchoes(listOf(reasoning, echo.copy(artifacts = part))).size)
        val blank = UiMessage(id = "b", role = "assistant", content = " ", timestamp = T0)
        assertTrue(blank.hasNoRenderableContent())
        assertFalse(blank.copy(artifacts = part).hasNoRenderableContent())
        assertTrue(timelineEventToUiMessage(narration, artifacts = part)!!.artifacts.isNotEmpty())
    }

    private fun List<UiMessage>.byId(id: String): UiMessage = single { it.id == id }

    private fun confirmed(
        position: Double,
        id: String,
        type: TimelineMessageType,
        text: String,
        run: String? = "run-1",
        step: String? = "s1",
    ) = TimelineEvent.Confirmed(
        position = position, otid = "otid-$id", content = text, serverId = id, messageType = type,
        date = parseTimelineInstant(T0), runId = run, stepId = step,
    )

    private fun call(position: Double, result: String?) = TimelineEvent.Confirmed(
        position = position, otid = "otid-t1", content = "", serverId = "m-t1",
        messageType = TimelineMessageType.TOOL_CALL, date = parseTimelineInstant(T0), runId = "run-1", stepId = "s1",
        toolCalls = persistentListOf(ToolCall(id = "t1", name = CanvasToolContract.COMPOSE, arguments = requestJson)),
        toolReturnContentByCallId = if (result == null) persistentMapOf() else persistentMapOf("t1" to result),
        toolReturnIsErrorByCallId = if (result == null) persistentMapOf() else persistentMapOf("t1" to false),
    )

    private companion object {
        const val CONV = "conv-123"
        const val T0 = "2026-10-01T12:00:00Z"
    }
}
