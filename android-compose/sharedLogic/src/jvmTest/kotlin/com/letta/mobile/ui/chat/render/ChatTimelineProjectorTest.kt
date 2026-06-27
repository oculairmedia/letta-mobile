package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.MessageSource
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.parseTimelineInstant
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Headless coverage for the shared streaming projection extracted from Android's
 * ChatTimelineObserver. Exercises the four projection outcomes that drive
 * incremental chat rendering — full, append-tail, replace-tail, no-change — plus
 * the pagination prefix. `stablePrefixVersion` is set explicitly to drive the
 * fast paths deterministically; the pagination prefix is fetched via
 * [ChatTimelineProjector.olderPrefixFor] so its referential identity is stable
 * across calls (the fast path requires `previous.prefix === prefix`).
 */
class ChatTimelineProjectorTest {
    private val conv = "conv-1"

    private fun confirmed(
        type: TimelineMessageType,
        content: String,
        serverId: String,
        position: Double,
    ) = TimelineEvent.Confirmed(
        position = position,
        otid = "server-$serverId",
        content = content,
        serverId = serverId,
        messageType = type,
        runId = null,
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
    )

    private fun timeline(events: List<TimelineEvent>, version: Long) =
        Timeline(conversationId = conv, events = events.toPersistentList(), stablePrefixVersion = version)

    private fun ChatTimelineProjector.projectLive(events: List<TimelineEvent>, version: Long): TimelineProjection =
        project(timeline(events, version), olderPrefixFor(conv), ChatUiState())

    @Test
    fun fullProjectionOrdersMessagesAndFlagsAssistantTail() {
        val projector = ChatTimelineProjector()
        val projection = projector.projectLive(
            listOf(
                confirmed(TimelineMessageType.USER, "hi", "u1", 1.0),
                confirmed(TimelineMessageType.ASSISTANT, "hello", "a1", 2.0),
            ),
            version = 1,
        )
        assertEquals(2, projection.ui.size)
        assertEquals("user", projection.ui[0].role)
        assertEquals("assistant", projection.ui[1].role)
        assertEquals("hello", projection.ui[1].content)
        assertTrue(projection.tailIsAssistant)
        assertEquals(ChatMessageListChange.Full, projection.messageListChange)
        assertFalse(projection.noChange)
        assertFalse(projection.fastPath)
    }

    @Test
    fun appendTailWhenEventGrowsByOne() {
        val projector = ChatTimelineProjector()
        projector.projectLive(listOf(confirmed(TimelineMessageType.USER, "hi", "u1", 1.0)), version = 1)
        val projection = projector.projectLive(
            listOf(
                confirmed(TimelineMessageType.USER, "hi", "u1", 1.0),
                confirmed(TimelineMessageType.ASSISTANT, "hello", "a1", 2.0),
            ),
            version = 2,
        )
        assertEquals(ChatMessageListChange.AppendTail, projection.messageListChange)
        assertTrue(projection.fastPath)
        assertEquals(2, projection.ui.size)
        assertEquals("hello", projection.ui[1].content)
    }

    @Test
    fun replaceTailWhenStreamingTailContentChanges() {
        val projector = ChatTimelineProjector()
        projector.projectLive(
            listOf(
                confirmed(TimelineMessageType.USER, "hi", "u1", 1.0),
                confirmed(TimelineMessageType.ASSISTANT, "Hel", "a1", 2.0),
            ),
            version = 5,
        )
        // Same size + same stablePrefixVersion, growing tail text → replace-tail.
        val projection = projector.projectLive(
            listOf(
                confirmed(TimelineMessageType.USER, "hi", "u1", 1.0),
                confirmed(TimelineMessageType.ASSISTANT, "Hello", "a1", 2.0),
            ),
            version = 5,
        )
        assertEquals(ChatMessageListChange.ReplaceTail, projection.messageListChange)
        assertTrue(projection.fastPath)
        assertFalse(projection.noChange)
        assertEquals("Hello", projection.ui[1].content)
    }

    @Test
    fun noChangeWhenTailRendersIdentically() {
        val projector = ChatTimelineProjector()
        val events = listOf(
            confirmed(TimelineMessageType.USER, "hi", "u1", 1.0),
            confirmed(TimelineMessageType.ASSISTANT, "Hello", "a1", 2.0),
        )
        projector.projectLive(events, version = 5)
        // Identical content re-emitted (a no-op streaming tick) → suppressed.
        val projection = projector.projectLive(events, version = 5)
        assertTrue(projection.noChange)
        assertEquals(ChatMessageListChange.None, projection.messageListChange)
    }

    @Test
    fun nonTailMutationIsReprojectedNotSuppressed() {
        // A reconcile can change a NON-tail event (e.g. attach a tool return to
        // an earlier tool call) while size + tail stay the same. The reducers
        // recompute stablePrefixVersion in that case, so the version differs and
        // the projector must do a full re-projection rather than take its
        // replace-tail/no-change fast path and suppress the update (Codex review
        // on the desktop projector adoption).
        val projector = ChatTimelineProjector()
        projector.projectLive(
            listOf(
                confirmed(TimelineMessageType.ASSISTANT, "first", "a1", 1.0),
                confirmed(TimelineMessageType.ASSISTANT, "tail", "a2", 2.0),
            ),
            version = 10,
        )
        // Non-tail event a1 changed; the (fixed) reconcile bumped the version.
        val projection = projector.projectLive(
            listOf(
                confirmed(TimelineMessageType.ASSISTANT, "EDITED", "a1", 1.0),
                confirmed(TimelineMessageType.ASSISTANT, "tail", "a2", 2.0),
            ),
            version = 11,
        )
        assertFalse(projection.noChange)
        assertEquals("EDITED", projection.ui[0].content)
    }

    @Test
    fun mergeOlderPagePrependsOlderMessages() {
        val projector = ChatTimelineProjector()
        val first = projector.projectLive(
            listOf(confirmed(TimelineMessageType.ASSISTANT, "newest", "a1", 2.0)),
            version = 1,
        )
        assertEquals(1, first.ui.size)

        val older = listOf(
            UiMessage(id = "old1", role = "user", content = "older", timestamp = "2026-04-19T05:00:00Z"),
        )
        val merged = projector.mergeOlderPage(conv, olderMessages = older, existingMessages = first.ui)
        assertEquals(2, merged.size)
        assertEquals("old1", merged[0].id)
        assertEquals(first.ui[0], merged[1])

        // A subsequent projection prepends the held prefix ahead of live messages.
        val projection = projector.projectLive(
            listOf(confirmed(TimelineMessageType.ASSISTANT, "newest", "a1", 2.0)),
            version = 1,
        )
        assertEquals(2, projection.ui.size)
        assertEquals("old1", projection.ui[0].id)
        assertEquals("newest", projection.ui[1].content)
    }

    @Test
    fun zeroMessageOpenCloseCycle_limitation_heartbeatsDoNotMutateProjectorState() {
        val projector = ChatTimelineProjector()
        
        // Simulating stream open with 0 messages
        val firstEmptyProjection = projector.projectLive(emptyList(), version = 1)
        
        assertEquals(0, firstEmptyProjection.ui.size)
        assertEquals(ChatMessageListChange.Full, firstEmptyProjection.messageListChange)
        assertFalse(firstEmptyProjection.noChange)

        // Simulating stream close with 0 messages, same stablePrefixVersion
        val secondEmptyProjection = projector.projectLive(emptyList(), version = 1)

        assertEquals(0, secondEmptyProjection.ui.size)
        // Limitation: ChatTimelineProjector.tailProjectionFastPath explicitly bails on empty event lists,
        // so it falls through to a full projection rather than taking the no-op fast path.
        // The render model (UI list) remains unchanged (empty), but noChange is false.
        assertFalse(secondEmptyProjection.noChange)
    }
}
