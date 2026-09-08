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
        assertEquals(listOf(0, 0), result.bodies.map { it.size })
        assertEquals(28_000_000L, result.metadata.rows.first().body.encodedBytes)
    }

    @Test fun largeJsonFixturesDeferWithoutReadingPrefixesAndAllowBoundedChunks() = runTest {
        for (mib in listOf(1, 2, 4, 8)) {
            val size = mib * 1024 * 1024
            val overhead = canonicalBody("x").size - 1
            val json = canonicalBody("x".repeat(size - overhead))
            assertEquals(size, json.size)
            val row = row(1, size.toLong()).copy(contentType = canonicalType)
            val store = Store(listOf(row)).apply { payload = json }
            val reader = TimelineBoundedReader(store)
            val page = reader.preview(scope, TimelineReadPosition.Tail, TimelinePageBudget(1, 2L * 1024 * 1024))
            assertEquals(0, store.bodyReads)
            assertTrue(page.bodies.single().isEmpty())
            val record = TimelineSettledRecord(row.key, row.contentType, page.bodies.single(), page.metadata.revision, row.body)
            val deferred = kotlin.test.assertIs<TimelineSettledProjection.Deferred>(record.projectBounded(scope))
            assertEquals(row.body, deferred.reference.pointer)
            assertFailsWith<IllegalArgumentException> { record.toRenderItem() }
            val first = reader.readChunk(deferred.reference, 0, 65_536)
            kotlin.test.assertContentEquals(json.copyOfRange(0, 65_536), first.bytes)
            assertEquals(65_536L, first.nextOffset)
            assertTrue(!first.isLast)
            val last = reader.readChunk(deferred.reference, size.toLong() - 7, 65_536)
            assertEquals(7, last.bytes.size)
            assertTrue(last.isLast)
            val eof = reader.readChunk(deferred.reference, size.toLong(), 1)
            assertTrue(eof.bytes.isEmpty() && eof.isLast)
            assertEquals(2, store.bodyReads)
            assertTrue(store.metadataLimits.all { it == 1 })
        }
    }

    @Test fun chunkRejectsStaleReferencesAndInvalidLimitsBeforeBodyRead() = runTest {
        val row = row(1, 100)
        val store = Store(listOf(row))
        val reader = TimelineBoundedReader(store)
        val ref = TimelineBodyReference(scope, row.key, row.body, row.contentType, 1)
        for (stale in listOf(ref.copy(revision = 2), ref.copy(key = key(2)),
            ref.copy(pointer = row.body.copy(value = "replaced")),
            ref.copy(pointer = row.body.copy(encodedBytes = 101)), ref.copy(contentType = "wrong"))) {
            assertFailsWith<IllegalStateException> { reader.readChunk(stale, 0, 10) }
        }
        for (offset in listOf(-1L, 101L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { reader.readChunk(ref, offset, 10) }
        }
        for (limit in listOf(0, -1, 65_537, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { reader.readChunk(ref, 0, limit) }
        }
        assertEquals(0, store.bodyReads)
    }

    @Test fun previewNeverSplitsSmallBodiesAndCapsAggregateIndependently() = runTest {
        val rows = (1L..140L).map { row(it, 16_384) }
        val result = TimelineBoundedReader(Store(rows)).preview(
            scope, TimelineReadPosition.Tail, TimelinePageBudget(140, 8L * 1024 * 1024),
        )
        assertEquals(140, result.metadata.rows.size)
        assertEquals(2 * 1024 * 1024, result.bodies.sumOf { it.size })
        assertTrue(result.bodies.all { it.size == 16_384 || it.isEmpty() })
        val limited = TimelineBoundedReader(Store(listOf(row(1, 5), row(2, 6), row(3, 4)))).preview(
            scope, TimelineReadPosition.Tail, TimelinePageBudget(3, 9),
        )
        assertEquals(listOf(5, 0, 4), limited.bodies.map { it.size })
        val tooMany = Store(rows)
        assertFailsWith<IllegalArgumentException> {
            TimelineBoundedReader(tooMany).preview(scope, TimelineReadPosition.Tail, TimelinePageBudget(1, 100))
        }
        assertEquals(0, tooMany.bodyReads)
        val oversized = Store(listOf(row(1, 2L * 1024 * 1024 + 1)))
        assertFailsWith<IllegalArgumentException> { load(oversized, 8L * 1024 * 1024) }
        assertEquals(0, oversized.bodyReads)
    }

    @Test fun chunkRejectsBrokenBackendAndPropagatesCancellation() = runTest {
        val row = row(1, 100)
        val store = Store(listOf(row))
        val ref = TimelineBodyReference(scope, row.key, row.body, row.contentType, 1)
        val reader = TimelineBoundedReader(store)
        store.responseSize = 11
        assertFailsWith<IllegalStateException> { reader.readChunk(ref, 0, 10) }
        store.responseSize = 0
        assertFailsWith<IllegalStateException> { reader.readChunk(ref, 0, 10) }
        store.responseSize = 3
        assertEquals(3L, reader.readChunk(ref, 0, 10).nextOffset)
        store.cancel = true
        assertFailsWith<CancellationException> { reader.readChunk(ref, 0, 10) }
    }

    @Test fun completeCanonicalPreviewRendersWhileTruncatedJsonIsTypedDeferred() = runTest {
        val bytes = canonicalBody("hello")
        val row = row(1, bytes.size.toLong()).copy(contentType = canonicalType)
        val store = Store(listOf(row)).apply { payload = bytes }
        val page = TimelineBoundedReader(store).preview(scope, TimelineReadPosition.Tail, TimelinePageBudget(1, 16_384))
        kotlin.test.assertContentEquals(bytes, page.bodies.single())
        val record = TimelineSettledRecord(row.key, row.contentType, page.bodies.single(), 1, row.body)
        val rendered = kotlin.test.assertIs<TimelineSettledProjection.Rendered>(record.projectBounded(scope))
        val single = kotlin.test.assertIs<com.letta.mobile.data.chat.projection.ChatRenderItem.Single>(rendered.item)
        assertEquals("hello", single.message.content)
        kotlin.test.assertIs<TimelineSettledProjection.Deferred>(record.copy(body = bytes.copyOf(7)).projectBounded(scope))
    }

    private suspend fun load(store: Store, bytes: Long) = TimelineBoundedReader(store).load(
        TimelineScope("backend", "conversation"), TimelineReadPosition.Tail, TimelinePageBudget(4, bytes),
    )

    private class Store(private val rows: List<TimelineLedgerMetadata>) : TimelineBoundedStore, TimelineStoreReader {
        var bodyReads = 0
        val offsets = mutableListOf<Long>()
        var cancel = false
        var payload: ByteArray? = null
        var responseSize: Int? = null
        val metadataLimits = mutableListOf<Int>()
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T = block(this)
        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T =
            error("Read must never mutate durable history")
        override suspend fun checkpoint() = TimelineDurableCheckpoint(1, null, false)
        override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage {
            metadataLimits += maxRows
            return TimelineMetadataPage(rows, null, null, 1)
        }
        override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? = null
        override suspend fun evidence(key: String, maxBytes: Int): ByteArray? = null
        override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray {
            if (cancel) throw CancellationException("cancelled")
            bodyReads++
            require(maxBytes in 0..65_536)
            offsets += offset
            return responseSize?.let { ByteArray(it) }
                ?: payload?.copyOfRange(offset.toInt(), offset.toInt() + maxBytes)
                ?: ByteArray(maxBytes)
        }
    }

    companion object {
        private val scope = TimelineScope("backend", "conversation")
        private const val canonicalType = "application/vnd.letta.timeline-event+json;version=1"
        private fun canonicalBody(content: String): ByteArray {
            val event = com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent(
                position = 1.0, otid = "otid", content = content, serverId = "id-1",
                messageType = "assistant_message", dateIso = "2026-01-01T00:00:00Z",
            )
            return com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.json.encodeToString(
                com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent.serializer(), event,
            ).encodeToByteArray()
        }
        private fun key(order: Long) = TimelinePageKey(order, TimelineMessageId("id-$order"))
        private fun row(order: Long, bytes: Long) = TimelineLedgerMetadata(key(order), TimelineBodyPointer("body-$order", bytes), "message", 1)
    }
}
