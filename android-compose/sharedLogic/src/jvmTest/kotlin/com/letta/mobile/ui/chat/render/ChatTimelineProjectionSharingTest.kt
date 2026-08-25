package com.letta.mobile.ui.chat.render

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

class ChatTimelineProjectionSharingTest {
    @Test
    fun nonTailMutationRebuildsChangedRowAndReusesStableRow() {
        val projector = ChatTimelineProjector()
        val firstEvents = listOf(
            confirmed("first", "a1", 1.0),
            confirmed("tail", "a2", 2.0),
        )
        val first = projector.projectLive(firstEvents, version = 10)

        val projection = projector.projectLive(
            listOf(
                confirmed("EDITED", "a1", 1.0),
                confirmed("tail", "a2", 2.0),
            ),
            version = 11,
        )

        assertFalse(projection.noChange)
        assertEquals("EDITED", projection.ui[0].content)
        assertFalse(first.ui[0] === projection.ui[0])
        assertTrue(first.ui[1] === projection.ui[1])
    }

    @Test
    fun sequenceOnlyChurnReusesProjectedListAndRow() {
        val projector = ChatTimelineProjector()
        val event = confirmed("settled", "a1", 1.0)
        val first = projector.projectLive(listOf(event), version = 1)

        val projection = projector.projectLive(listOf(event.copy(seqId = 2)), version = 2)

        assertTrue(projection.noChange)
        assertTrue(first.ui === projection.ui)
        assertTrue(first.ui.single() === projection.ui.single())
    }

    @Test
    fun largeSnapshotAppendProjectsOnlyChangedRowsAndReusesStableInstances() {
        listOf(50, 150, 500).forEach { count ->
            val projector = ChatTimelineProjector()
            val initialEvents = (0 until count).map { index ->
                confirmed("message-$index", "message-$index", index.toDouble())
            }
            val first = projector.projectLive(initialEvents, version = 1)
            val appendedEvents = (count until count + 5).map { index ->
                confirmed("appended-$index", "appended-$index", index.toDouble())
            }

            val projection = projector.projectLive(initialEvents + appendedEvents, version = 2)

            assertEquals(count, projection.eventsReused)
            assertEquals(5, projection.eventsProjected)
            assertEquals(count + 5, projection.ui.size)
            first.ui.forEachIndexed { index, message ->
                assertTrue(message === projection.ui[index], "row $index was rebuilt for $count-event input")
            }
        }
    }

    private fun ChatTimelineProjector.projectLive(
        events: List<TimelineEvent>,
        version: Long,
    ): TimelineProjection = project(
        timeline = Timeline(
            conversationId = CONVERSATION_ID,
            events = events.toPersistentList(),
            stablePrefixVersion = version,
        ),
        prefix = olderPrefixFor(CONVERSATION_ID),
        previousState = ChatUiState(),
        isActiveRunStreaming = false,
    )

    private fun confirmed(content: String, serverId: String, position: Double) = TimelineEvent.Confirmed(
        position = position,
        otid = "server-$serverId",
        content = content,
        serverId = serverId,
        messageType = TimelineMessageType.ASSISTANT,
        runId = null,
        stepId = null,
        date = parseTimelineInstant("2026-08-24T06:00:00Z"),
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

    private companion object {
        const val CONVERSATION_ID = "conv-structural-sharing"
    }
}
