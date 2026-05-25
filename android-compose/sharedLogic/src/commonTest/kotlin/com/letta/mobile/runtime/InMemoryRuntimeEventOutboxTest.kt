package com.letta.mobile.runtime

import com.letta.mobile.data.model.AgentId
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun outbox(): InMemoryRuntimeEventOutbox = InMemoryRuntimeEventOutbox(
        eventIdFactory = { _, offset -> RuntimeEventId("event-${offset.value}") },
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
