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

    @Test fun fullBodyUsesBoundedChunksWithinOneReadSnapshot() = runTest {
        val store = Store(listOf(row(1, 150_000)))
        val result = load(store, 150_000)
        assertEquals(150_000, result.bodies.single().size)
        assertEquals(listOf(0L, 65_536L, 131_072L), store.offsets)
        assertEquals(3, store.bodyReads)
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

    @Test fun oversizedPreviewRetainsExactPointerAndBoundsAllocation() = runTest {
        val metadata = listOf(row(1, 28_000_000), row(2, 20_000_000))
        val result = TimelineBoundedReader(Store(metadata)).preview(
            TimelineScope("backend", "conversation"), TimelineReadPosition.Tail, TimelinePageBudget(4, 20_000),
        )
        assertEquals(metadata, result.metadata.rows)
        assertEquals(listOf(16_384, 3_616), result.bodies.map { it.size })
        assertEquals(28_000_000L, result.metadata.rows.first().body.encodedBytes)
    }

    private suspend fun load(store: Store, bytes: Long) = TimelineBoundedReader(store).load(
        TimelineScope("backend", "conversation"), TimelineReadPosition.Tail, TimelinePageBudget(4, bytes),
    )

    private class Store(private val rows: List<TimelineLedgerMetadata>) : TimelineBoundedStore, TimelineStoreReader {
        var bodyReads = 0
        val offsets = mutableListOf<Long>()
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
            require(maxBytes in 0..65_536)
            offsets += offset
            return ByteArray(maxBytes)
        }
    }

    companion object {
        private fun key(order: Long) = TimelinePageKey(order, TimelineMessageId("id-$order"))
        private fun row(order: Long, bytes: Long) = TimelineLedgerMetadata(key(order), TimelineBodyPointer("body-$order", bytes), "message", 1)
    }
}
