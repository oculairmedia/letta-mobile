package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineHydrationProcessorIntegrationTest {
    @Test
    fun delayedHydrationRebasesStreamAndPendingDiskRecordsAndRepairsCursorAfterPublication() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = SequencedDelayedTransport(
            listOf(DelayedResponse(listOf(UserMessage("server", JsonPrimitive("history"), seqId = 7)), gate)),
        )
        lateinit var loop: TimelineSyncLoop
        val cursorStore = RecordingCursorStore { sequence ->
            val confirmed = loop.state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
            assertEquals(setOf("server", "stream"), confirmed.map { it.serverId }.toSet())
            assertTrue(loop.state.value.events.any { it.otid == "disk-local" })
            "cursor:$sequence"
        }
        val pendingStore = RecordingPendingStore(
            listOf(
                PendingLocalRecord(
                    otid = "disk-local",
                    conversationId = "conversation",
                    content = "pending on disk",
                    attachments = emptyList(),
                    sentAt = parseTimelineInstant("2026-01-01T00:00:00Z"),
                ),
            ),
        )
        loop = TimelineSyncLoop(
            messageApi = transport,
            conversationId = "conversation",
            scope = backgroundScope,
            pendingLocalStore = pendingStore,
            conversationCursorStore = cursorStore,
            startStreamSubscriber = false,
        )

        try {
            val hydration = async { loop.hydrate(recordConversationCursor = true) }
            transport.started.single().await()
            loop.ingestStreamEvent(AssistantMessage("stream", JsonPrimitive("arrived while fetching")))
            gate.complete(Unit)
            hydration.await()

            val events = loop.state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
            assertEquals(setOf("server", "stream"), events.map { it.serverId }.toSet())
            assertTrue(loop.state.value.events.any { it.otid == "disk-local" })
            assertEquals(listOf("cursor:7"), cursorStore.order)
        } finally {
            loop.close()
        }
    }

    @Test
    fun olderDelayedHydrationCannotOverwriteNewerStateOrRepairItsCursor() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val transport = SequencedDelayedTransport(
            responses = listOf(
                DelayedResponse(
                    listOf(UserMessage("old", JsonPrimitive("old"), seqId = 1)),
                    firstGate,
                ),
                DelayedResponse(
                    listOf(UserMessage("new", JsonPrimitive("new"), seqId = 2)),
                    secondGate,
                ),
            ),
        )
        val cursorEffects = mutableListOf<Long>()
        val committedGenerations = mutableListOf<Long>()
        val processor = hydrationProcessor(backgroundScope, cursorEffects)
        val hydrator = TimelineHydrator(
            conversationId = "conversation",
            messageApi = transport,
            pendingLocalStore = NoOpPendingLocalStore,
            events = MutableSharedFlow(extraBufferCapacity = 4),
            timelineProcessor = processor,
            onHydrationCommitted = { committedGenerations += processor.state.value.hydrateGeneration },
        )

        val older = async { hydrator.hydrate(recordConversationCursor = true) }
        transport.started[0].await()
        val newer = async { hydrator.hydrate(recordConversationCursor = true) }
        transport.started[1].await()

        secondGate.complete(Unit)
        assertEquals(TimelineHydrationOutcome.Accepted, newer.await())
        firstGate.complete(Unit)
        assertEquals(TimelineHydrationOutcome.Rejected, older.await())

        assertEquals(listOf("new"), confirmedServerIds(processor.state.value.timeline))
        assertEquals(listOf(2L), cursorEffects)
        assertEquals(listOf(2L), committedGenerations)
        assertEquals(2L, processor.state.value.hydrateGeneration)
    }

    @Test
    fun delayedHydrationPreservesInitialMetadataAndConcurrentLocalMutation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = SequencedDelayedTransport(
            listOf(DelayedResponse(listOf(UserMessage("server", JsonPrimitive("fresh history"), seqId = 9)), gate)),
        )
        val initial = Timeline(
            conversationId = "conversation",
            events = persistentListOf(persistedEvent()),
            liveCursor = "persisted-live",
            backfillCursor = "persisted-backfill",
            releasedOlderCount = 11,
        )
        val processor = hydrationProcessor(backgroundScope, initialTimeline = initial)
        val hydrator = TimelineHydrator(
            conversationId = "conversation",
            messageApi = transport,
            pendingLocalStore = NoOpPendingLocalStore,
            events = MutableSharedFlow(extraBufferCapacity = 4),
            timelineProcessor = processor,
        )

        val hydration = async { hydrator.hydrate() }
        transport.started.single().await()
        val localAck = processor.submit(
            TimelineMutation.LocalAppend(
                pending = PendingSend("local-otid", "local while fetching", persistentListOf()),
                sentAt = parseTimelineInstant("2026-01-01T00:00:00Z"),
                mode = TimelineLocalAppendMode.OPTIMISTIC,
            ),
        )
        assertTrue(localAck is TimelineProcessorAck.Applied)
        gate.complete(Unit)
        assertEquals(TimelineHydrationOutcome.Accepted, hydration.await())

        val timeline = processor.state.value.timeline
        assertTrue(timeline.events.any { it.otid == "local-otid" })
        assertEquals(setOf("persisted", "server"), confirmedServerIds(timeline).toSet())
        assertEquals("server", timeline.liveCursor)
        assertEquals("persisted", timeline.backfillCursor)
        assertEquals(11, timeline.releasedOlderCount)
        assertFalse(timeline.events.isEmpty())
    }

    private fun hydrationProcessor(
        scope: CoroutineScope,
        cursorEffects: MutableList<Long> = mutableListOf(),
        initialTimeline: Timeline = Timeline("conversation"),
    ) = TimelineProcessor(
        initialState = TimelineReducerState(initialTimeline),
        scope = scope,
        effectHandler = { effect ->
            if (effect is TimelineReductionEffect.RepairHydrationCursor) cursorEffects += effect.sequence
        },
    )

    private fun confirmedServerIds(timeline: Timeline): List<String> =
        timeline.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId }

    private fun persistedEvent() = TimelineEvent.Confirmed(
        position = 1.0,
        otid = "server-persisted-user",
        content = "persisted content",
        serverId = "persisted",
        messageType = TimelineMessageType.USER,
        date = parseTimelineInstant("2026-01-01T00:00:00Z"),
        runId = null,
        stepId = null,
    )

    private data class DelayedResponse(
        val messages: List<LettaMessage>,
        val gate: CompletableDeferred<Unit>,
    )

    private class SequencedDelayedTransport(
        private val responses: List<DelayedResponse>,
    ) : TimelineTransport by EmptyTimelineTransport {
        val started = responses.map { CompletableDeferred<Unit>() }
        private var next = 0

        override suspend fun listConversationMessages(
            conversationId: String,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> {
            val index = next++
            started[index].complete(Unit)
            responses[index].gate.await()
            val messages = responses[index].messages
            return if (order == "desc") messages.reversed() else messages
        }
    }

    private class RecordingPendingStore(
        private val records: List<PendingLocalRecord>,
    ) : PendingLocalStore by NoOpPendingLocalStore {
        override suspend fun load(conversationId: String): List<PendingLocalRecord> = records
    }

    private class RecordingCursorStore(
        private val record: (Long) -> String,
    ) : ConversationCursorStore by NoOpConversationCursorStore {
        val order = mutableListOf<String>()

        override suspend fun recordFrame(conversationId: String, seq: Long) {
            order += record(seq)
        }
    }

}
