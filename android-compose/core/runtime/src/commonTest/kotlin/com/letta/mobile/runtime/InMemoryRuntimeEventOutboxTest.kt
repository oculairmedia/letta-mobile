package com.letta.mobile.runtime

import com.letta.mobile.data.model.AgentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryRuntimeEventOutboxTest {
    private val backendId = BackendId("local")
    private val runtimeId = RuntimeId("koog")

    @Test
    fun appendAssignsMonotonicOffsetsAndEnvelopeMetadata() = runTest {
        val outbox = outbox()

        val first = outbox.append(localAppend("local-1"))
        val second = outbox.append(localAppend("local-2"))

        assertEquals(RuntimeEventOffset(1), first.offset)
        assertEquals(RuntimeEventOffset(2), second.offset)
        assertEquals(RuntimeEventId("event-1"), first.eventId)
        assertEquals(EpochMillis(1_700_000_000_000), first.createdAt)
        assertEquals(backendId, first.backendId)
        assertEquals(runtimeId, first.runtimeId)
    }

    @Test
    fun singleAppendIsImmediatelyReplayable() = runTest {
        val outbox = outbox()

        val appended = outbox.append(localAppend("local-1"))

        assertEquals(
            listOf(appended),
            outbox.events(RuntimeEventOffset(0)).take(1).toList(),
        )
    }

    @Test
    fun appendAllAssignsOrderedContiguousOffsets() = runTest {
        val outbox = outbox()

        val appended = outbox.appendAll(
            listOf(
                localAppend("local-1"),
                localAppend("local-2"),
                localAppend("local-3"),
            ),
        )

        assertEquals(listOf(1L, 2L, 3L), appended.map { it.offset.value })
        assertEquals(
            listOf("local-1", "local-2", "local-3"),
            appended.map { (it.payload as RuntimeEventPayload.LocalUserAppend).localMessageId },
        )
    }

    @Test
    fun appendAllIsAtomicWhenEnvelopeCreationFails() = runTest {
        val outbox = outbox(
            eventIdFactory = { draft, offset ->
                val localMessageId = (draft.payload as RuntimeEventPayload.LocalUserAppend).localMessageId
                check(localMessageId != "local-2")
                RuntimeEventId("event-${offset.value}")
            },
        )

        repeat(2) {
            assertFailsWith<IllegalStateException> {
                outbox.appendAll(listOf(localAppend("local-1"), localAppend("local-2")))
            }
        }

        assertEquals(RuntimeEventOffset(1), outbox.append(localAppend("local-3")).offset)
    }

    @Test
    fun appendAllPropagatesCancellationWithoutPublishingPartialBatch() = runTest {
        val outbox = outbox(
            eventIdFactory = { draft, offset ->
                val localMessageId = (draft.payload as RuntimeEventPayload.LocalUserAppend).localMessageId
                if (localMessageId == "local-2") throw CancellationException("cancel batch")
                RuntimeEventId("event-${offset.value}")
            },
        )

        assertFailsWith<CancellationException> {
            outbox.appendAll(listOf(localAppend("local-1"), localAppend("local-2")))
        }

        assertEquals(RuntimeEventOffset(1), outbox.append(localAppend("local-3")).offset)
    }

    @Test
    fun concurrentAppendAllCallsProduceUniqueOffsets() = runTest {
        val outbox = outbox()

        val batches = listOf(
            async { outbox.appendAll(List(4) { localAppend("batch-a-$it") }) },
            async { outbox.appendAll(List(4) { localAppend("batch-b-$it") }) },
        ).awaitAll()
        val offsets = batches.flatten().map { it.offset.value }

        assertEquals((1L..8L).toList(), offsets.sorted())
        assertEquals(offsets.size, offsets.toSet().size)
        batches.forEach { batch ->
            val firstOffset = batch.first().offset.value
            assertEquals((firstOffset until firstOffset + batch.size).toList(), batch.map { it.offset.value })
        }
    }

    @Test
    fun appendAllWithNoDraftsReturnsEmpty() = runTest {
        var factoryCalls = 0
        val outbox = outbox { _, offset ->
            factoryCalls += 1
            RuntimeEventId("event-${offset.value}")
        }

        assertEquals(emptyList(), outbox.appendAll(emptyList()))
        assertEquals(0, factoryCalls)
        assertEquals(RuntimeEventOffset(1), outbox.append(localAppend("local-1")).offset)
    }

    @Test
    fun eventsReplaysOnlyAfterRequestedOffset() = runTest {
        val outbox = outbox()
        outbox.append(localAppend("local-1"))
        val second = outbox.append(localAppend("local-2"))

        val replayed = outbox.events(RuntimeEventOffset(1)).take(1).toList()

        assertEquals(listOf(second), replayed)
    }

    @Test
    fun eventsStreamsAppendsAfterCollectionStarts() = runTest {
        val outbox = outbox()
        val collection = async {
            outbox.events(RuntimeEventOffset(0)).take(2).toList()
        }

        val first = outbox.append(localAppend("local-1"))
        val second = outbox.append(localAppend("local-2"))

        assertEquals(listOf(first, second), collection.await())
    }

    private fun outbox(
        eventIdFactory: (RuntimeEventDraft, RuntimeEventOffset) -> RuntimeEventId = { _, offset ->
            RuntimeEventId("event-${offset.value}")
        },
    ): InMemoryRuntimeEventOutbox = InMemoryRuntimeEventOutbox(
        eventIdFactory = eventIdFactory,
        clock = { EpochMillis(1_700_000_000_000) },
    )

    private fun localAppend(localMessageId: String): RuntimeEventDraft = RuntimeEventDraft(
        backendId = backendId,
        runtimeId = runtimeId,
        agentId = AgentId("agent-1"),
        conversationId = ConversationId("conversation-1"),
        source = RuntimeEventSource.LocalUser,
        payload = RuntimeEventPayload.LocalUserAppend(
            localMessageId = localMessageId,
            text = "hello",
        ),
    )
}
