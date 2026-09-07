package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimelineBoundedReaderTest {
    @Test fun signedOrderDoesNotOverflow() {
        assertTrue(key(Long.MIN_VALUE) < key(Long.MAX_VALUE))
        assertTrue(TimelinePageKey(0, TimelineMessageId("a")) < TimelinePageKey(0, TimelineMessageId("b")))
    }

    @Test fun rejectsWholePageBeforeReadingAnyBody() = runTest {
        val store = Store(listOf(row(1, 5), row(2, 6)))
        assertFailsWith<IllegalArgumentException> { load(store, 10) }
        assertEquals(0, store.bodyReads)
    }

    @Test fun boundedReadUsesExactDeclaredLengths() = runTest {
        val store = Store(listOf(row(1, 5), row(2, 6)))
        val result = load(store, 11)
        assertEquals(listOf(5, 6), result.bodies.map { it.size })
        assertEquals(2, store.bodyReads)
    }

    @Test fun cancellationIsNotConvertedToEmptyPage() = runTest {
        val store = Store(listOf(row(1, 5)))
        store.cancel = true
        assertFailsWith<CancellationException> { load(store, 10) }
    }

    @Test fun rejectsDuplicateKeysBeforeBodyRead() = runTest {
        val store = Store(listOf(row(1, 5), row(1, 5)))
        assertFailsWith<IllegalArgumentException> { load(store, 10) }
        assertEquals(0, store.bodyReads)
    }

    private suspend fun load(store: Store, bytes: Long) = TimelineBoundedReader(store).load(
        TimelineScope("backend", "conversation"), TimelineReadPosition.Tail, TimelinePageBudget(4, bytes),
    )

    private class Store(private val rows: List<TimelineLedgerMetadata>) : TimelineBoundedStore, TimelineStoreReader {
        var bodyReads = 0
        var cancel = false
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T = block(this)
        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T =
            error("Read must never mutate durable history")
        override suspend fun checkpoint() = TimelineDurableCheckpoint(1, null, false)
        override suspend fun metadata(position: TimelineReadPosition, maxRows: Int) = TimelineMetadataPage(rows, null, null, 1)
        override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? = null
        override suspend fun evidence(key: String, maxBytes: Int): ByteArray? = null
        override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray {
            if (cancel) throw CancellationException("cancelled")
            bodyReads++
            assertEquals(0L, offset)
            return ByteArray(maxBytes)
        }
    }

    companion object {
        private fun key(order: Long) = TimelinePageKey(order, TimelineMessageId("id-$order"))
        private fun row(order: Long, bytes: Long) = TimelineLedgerMetadata(key(order), TimelineBodyPointer("body-$order", bytes), "message", 1)
    }
}
