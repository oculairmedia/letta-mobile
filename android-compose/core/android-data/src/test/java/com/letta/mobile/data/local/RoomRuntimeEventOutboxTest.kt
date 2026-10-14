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
            assertEquals(emptyList<RuntimeEventEntity>(), database!!.runtimeEventDao().listAfterOffset(0, Int.MAX_VALUE))
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
        assertEquals(emptyList<RuntimeEventEntity>(), database!!.runtimeEventDao().listAfterOffset(0, Int.MAX_VALUE))
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
        val outbox = outbox(
            eventIdFactory = { _, offset ->
                factoryCalls += 1
                RuntimeEventId("test-runtime-event-${offset.value}")
            },
        )

        assertEquals(0, outbox.appendAll(emptyList()).size)
        assertEquals(0, factoryCalls)
        assertEquals(emptyList<RuntimeEventEntity>(), database!!.runtimeEventDao().listAfterOffset(0, Int.MAX_VALUE))
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

    @Test
    fun `listAfterOffset respects the limit`() = runTest {
        val outbox = outbox()
        outbox.appendAll(List(5) { draft("local-$it") })

        val page = database!!.runtimeEventDao().listAfterOffset(1, 2)

        assertEquals(listOf(2L, 3L), page.map { it.eventOffset })
    }

    @Test
    fun `prune caps row count and keeps the newest rows`() = runTest {
        val outbox = outbox(limits = Limits(maxRows = 3))

        outbox.appendAll(List(5) { draft("local-$it") })

        assertEquals(listOf(3L, 4L, 5L), allOffsets())
    }

    @Test
    fun `prune drops aged rows but never the newest row`() = runTest {
        var now = 1_000L
        val outbox = outbox(limits = Limits(maxAgeMillis = 100), clock = { EpochMillis(now) })
        outbox.appendAll(List(3) { draft("old-$it") })

        now = 2_000L
        outbox.prune()
        assertEquals(listOf(3L), allOffsets())

        outbox.append(draft("fresh"))
        assertEquals(listOf(4L), allOffsets())
    }

    @Test
    fun `prune keeps unexpired rows`() = runTest {
        var now = 1_000L
        val outbox = outbox(limits = Limits(maxAgeMillis = 100), clock = { EpochMillis(now) })
        outbox.append(draft("old"))
        now = 1_150L
        outbox.append(draft("recent-1"))
        outbox.append(draft("recent-2"))

        assertEquals(listOf(2L, 3L), allOffsets())
    }

    @Test
    fun `ids never collide after a prune`() = runTest {
        val outbox = outbox(
            eventIdFactory = { _, offset -> RuntimeEventId("runtime-event-${offset.value}") },
            limits = Limits(maxRows = 1),
        )

        val ids = (1..6).flatMap { batch ->
            outbox.appendAll(List(2) { draft("batch-$batch-$it") }).map { it.eventId.value }
        }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(listOf(12L), allOffsets())
        assertEquals(RuntimeEventOffset(13), outbox.append(draft("next")).offset)
    }

    @Test
    fun `first use prunes rows left over from a previous process`() = runTest {
        val writer = outbox()
        writer.appendAll(List(4) { draft("local-$it") })

        val reader = RoomRuntimeEventOutbox(database = database!!, clock = { EpochMillis(1_000) }, maxRows = 2)
        reader.events(RuntimeEventOffset(0)).take(2).toList()

        assertEquals(listOf(3L, 4L), allOffsets())
    }

    @Test
    fun `events pages through a backlog larger than the page size`() = runTest {
        val outbox = outbox(limits = Limits(pageSize = 2))
        val appended = outbox.appendAll(List(5) { draft("local-$it") })

        assertEquals(appended, outbox.events(RuntimeEventOffset(0)).take(5).toList())
    }

    private data class Limits(
        val maxRows: Int = RoomRuntimeEventOutbox.DEFAULT_MAX_ROWS,
        val maxAgeMillis: Long = RoomRuntimeEventOutbox.DEFAULT_MAX_AGE_MILLIS,
        val pageSize: Int = RoomRuntimeEventOutbox.DEFAULT_PAGE_SIZE,
    )

    private suspend fun allOffsets(): List<Long> =
        database!!.runtimeEventDao().listAfterOffset(0, Int.MAX_VALUE).map { it.eventOffset }

    private fun outbox(
        eventIdFactory: (RuntimeEventDraft, RuntimeEventOffset) -> RuntimeEventId = { _, offset ->
            RuntimeEventId("test-runtime-event-${offset.value}")
        },
        limits: Limits = Limits(),
        clock: () -> EpochMillis = { EpochMillis(1_000) },
    ): RoomRuntimeEventOutbox {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = db
        return RoomRuntimeEventOutbox(
            database = db,
            eventIdFactory = eventIdFactory,
            clock = clock,
            maxRows = limits.maxRows,
            maxAgeMillis = limits.maxAgeMillis,
            pageSize = limits.pageSize,
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
