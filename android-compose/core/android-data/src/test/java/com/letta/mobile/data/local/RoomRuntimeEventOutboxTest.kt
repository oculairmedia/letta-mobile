package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventId
import com.letta.mobile.runtime.RuntimeEventOffset
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class RoomRuntimeEventOutboxTest {
    private var database: LettaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
    }

    @Test
    fun `append persists envelope and replays after offset`() = runTest {
        val outbox = outbox()

        val first = outbox.append(draft(localMessageId = "local-1"))
        val second = outbox.append(draft(localMessageId = "local-2"))

        assertEquals(RuntimeEventOffset(1), first.offset)
        assertEquals(RuntimeEventOffset(2), second.offset)
        assertEquals(
            listOf(second),
            outbox.events(first.offset).take(1).toList(),
        )
    }

    @Test
    fun `append persists generated event metadata`() = runTest {
        val outbox = outbox()

        val appended = outbox.append(draft(localMessageId = "local-1"))
        val replayed = outbox.events(RuntimeEventOffset(0)).take(1).toList().single()

        assertEquals(RuntimeEventId("test-runtime-event-1"), replayed.eventId)
        assertEquals(EpochMillis(1_000), replayed.createdAt)
        assertEquals(appended, replayed)
    }

    @Test
    fun `appendAll persists ordered contiguous offsets`() = runTest {
        val outbox = outbox()

        val appended = outbox.appendAll(
            listOf(
                draft(localMessageId = "local-1"),
                draft(localMessageId = "local-2"),
                draft(localMessageId = "local-3"),
            ),
        )

        assertEquals(listOf(1L, 2L, 3L), appended.map { it.offset.value })
        assertEquals(appended, outbox.events(RuntimeEventOffset(0)).take(3).toList())
    }

    @Test
    fun `appendAll rolls back the batch and propagates conflicts`() = runTest {
        val outbox = outbox(eventIdFactory = { _, _ -> RuntimeEventId("duplicate-event") })

        repeat(2) {
            var failure: Throwable? = null
            try {
                outbox.appendAll(
                    listOf(
                        draft(localMessageId = "local-1"),
                        draft(localMessageId = "local-2"),
                    ),
                )
            } catch (caught: Throwable) {
                failure = caught
            }

            assertNotNull(failure)
            assertEquals(emptyList<RuntimeEventEntity>(), database!!.runtimeEventDao().listAfterOffset(0))
        }

        assertEquals(RuntimeEventOffset(1), outbox.append(draft("local-3")).offset)
    }

    @Test
    fun `appendAll propagates cancellation and rolls back the batch`() = runTest {
        val outbox = outbox(
            eventIdFactory = { eventDraft, offset ->
                val localMessageId =
                    (eventDraft.payload as RuntimeEventPayload.LocalUserAppend).localMessageId
                if (localMessageId == "local-2") throw CancellationException("cancel batch")
                RuntimeEventId("test-runtime-event-${offset.value}")
            },
        )

        var failure: Throwable? = null
        try {
            outbox.appendAll(listOf(draft("local-1"), draft("local-2")))
        } catch (caught: CancellationException) {
            failure = caught
        }

        assertNotNull(failure)
        assertEquals(emptyList<RuntimeEventEntity>(), database!!.runtimeEventDao().listAfterOffset(0))
        assertEquals(RuntimeEventOffset(1), outbox.append(draft("local-3")).offset)
    }

    @Test
    fun `concurrent appendAll calls persist unique contiguous batches`() = runTest {
        val outbox = outbox()

        val batches = listOf(
            async { outbox.appendAll(List(4) { draft("batch-a-$it") }) },
            async { outbox.appendAll(List(4) { draft("batch-b-$it") }) },
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
    fun `appendAll with no drafts returns empty`() = runTest {
        var factoryCalls = 0
        val outbox = outbox { _, offset ->
            factoryCalls += 1
            RuntimeEventId("test-runtime-event-${offset.value}")
        }

        assertEquals(0, outbox.appendAll(emptyList()).size)
        assertEquals(0, factoryCalls)
        assertEquals(emptyList<RuntimeEventEntity>(), database!!.runtimeEventDao().listAfterOffset(0))
        assertEquals(RuntimeEventOffset(1), outbox.append(draft("local-1")).offset)
    }

    @Test
    fun `events flow observes live appends without duplicate replay`() = runTest {
        val outbox = outbox()

        outbox.events(RuntimeEventOffset(0)).test {
            val first = outbox.append(draft(localMessageId = "local-1"))
            val second = outbox.append(draft(localMessageId = "local-2"))

            assertEquals(first, awaitItem())
            assertEquals(second, awaitItem())
            expectNoEvents()
        }
    }

    private fun outbox(
        eventIdFactory: (RuntimeEventDraft, RuntimeEventOffset) -> RuntimeEventId = { _, offset ->
            RuntimeEventId("test-runtime-event-${offset.value}")
        },
    ): RoomRuntimeEventOutbox {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = db
        return RoomRuntimeEventOutbox(
            database = db,
            eventIdFactory = eventIdFactory,
            clock = { EpochMillis(1_000) },
        )
    }

    private fun draft(localMessageId: String): RuntimeEventDraft = RuntimeEventDraft(
        backendId = BackendId("backend-1"),
        runtimeId = RuntimeId("runtime-1"),
        agentId = AgentId("agent-1"),
        conversationId = ConversationId("conversation-1"),
        source = RuntimeEventSource.LocalUser,
        payload = RuntimeEventPayload.LocalUserAppend(
            localMessageId = localMessageId,
            text = "hello",
        ),
    )
}
