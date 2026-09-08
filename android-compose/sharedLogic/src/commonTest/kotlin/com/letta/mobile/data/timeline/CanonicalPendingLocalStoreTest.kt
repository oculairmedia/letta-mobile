package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalPendingLocalStoreTest {
    @Test fun scopeIsolationRestartAndTransactionalConfirmation() = runTest {
        val ledger = Store()
        val pending = CanonicalPendingLocalStore(ledger)
        val first = TimelineScope("backend-a", "conversation")
        val second = TimelineScope("backend-b", "conversation")
        val record = CanonicalPendingLocalStore.Record("otid", "hello", emptyList(), "2026-09-08T00:00:00Z")
        pending.save(first, record)
        pending.save(first, record)
        assertEquals(listOf(record), CanonicalPendingLocalStore(ledger).load(first))
        assertEquals(emptyList(), pending.load(second))
        pending.mark(first, record.otid, CanonicalPendingLocalStore.Delivery.Failed)
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, pending.load(first).single().delivery)
        ledger.transaction(first) { pending.confirm(this, record.otid) }
        assertEquals(emptyList(), pending.load(first))
    }

    @Test fun oversizedAndConflictingMessagesLeaveDurablePendingUntouched() = runTest {
        val ledger = Store()
        val pending = CanonicalPendingLocalStore(ledger)
        val scope = TimelineScope("backend", "conversation")
        val record = CanonicalPendingLocalStore.Record("otid", "hello", emptyList(), "2026-09-08T00:00:00Z")
        pending.save(scope, record)
        assertFailsWith<IllegalArgumentException> { pending.save(scope, record.copy(content = "different")) }
        assertFailsWith<IllegalArgumentException> {
            pending.save(scope, record.copy(otid = "large", content = "x".repeat(CanonicalPendingLocalStore.MAX_BYTES + 1)))
        }
        assertEquals(listOf(record), pending.load(scope))
    }

    private class Store : TimelineBoundedStore {
        private val values = mutableMapOf<TimelineScope, MutableMap<String, ByteArray>>()
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T =
            block(Transaction(values[scope].orEmpty().toMutableMap()))

        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T {
            val copy = values[scope].orEmpty().toMutableMap()
            val result = block(Transaction(copy))
            values[scope] = copy
            return result
        }

        private class Transaction(private val values: MutableMap<String, ByteArray>) : TimelineStoreTransaction {
            override suspend fun evidence(key: String, maxBytes: Int): ByteArray? = values[key]?.also { require(it.size <= maxBytes) }
            override suspend fun putEvidence(key: String, value: ByteArray) { values[key] = value }
            override suspend fun deleteEvidence(key: String) { values.remove(key) }
            override suspend fun checkpoint(): TimelineDurableCheckpoint = error("No history read expected")
            override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage = error("No history read expected")
            override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? = error("No history read expected")
            override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray = error("No history read expected")
            override suspend fun put(record: TimelineStoredRecord): Unit = error("No settled write expected")
            override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean): Unit = error("No cursor write expected")
            override suspend fun nextRevision(): Long = error("No settled invalidation expected")
            override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason): Unit = error("No history deletion expected")
        }
    }
}
