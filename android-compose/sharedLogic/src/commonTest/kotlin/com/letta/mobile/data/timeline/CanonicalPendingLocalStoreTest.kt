package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalPendingLocalStoreTest {
    @Test fun optimisticProjectionPreservesDeliveryAndAttachments() {
        val record = CanonicalPendingLocalStore.Record("otid", "hello",
            listOf(MessageContentPart.Image("aGVsbG8=", "image/png")), "2026-09-08T00:00:00Z")
        val sending = record.toRenderItem().message
        assertEquals("otid", sending.clientMessageId)
        assertEquals(true, sending.isPending)
        assertEquals("image/png", sending.attachments.single().mediaType)
        val failed = record.copy(delivery = CanonicalPendingLocalStore.Delivery.Failed).toRenderItem().message
        assertEquals(true, failed.isSendFailed)
        assertEquals(false, failed.isError)
        assertEquals("user", failed.role)
    }

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

    @Test fun attachmentsRoundTripAndOversizedImageDoesNotReplacePending() = runTest {
        val ledger = Store()
        val pending = CanonicalPendingLocalStore(ledger)
        val scope = TimelineScope("backend", "conversation")
        val record = CanonicalPendingLocalStore.Record(
            "image", "caption", listOf(MessageContentPart.Image("aGVsbG8=", "image/png")), "2026-09-08T00:00:00Z",
        )
        pending.save(scope, record)
        assertEquals(listOf(record), CanonicalPendingLocalStore(ledger).load(scope))
        assertFailsWith<IllegalArgumentException> {
            pending.save(scope, record.copy(
                otid = "oversized-image",
                attachments = listOf(MessageContentPart.Image("x".repeat(CanonicalPendingLocalStore.MAX_BYTES + 1), "image/png")),
            ))
        }
        assertEquals(listOf(record), pending.load(scope))
    }

    private class Store : TimelineBoundedStore {
        private val values = mutableMapOf<TimelineScope, MutableMap<String, ByteArray>>()
        private val tools = mutableMapOf<TimelineScope, TestToolIndexState>()
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T =
            block(Transaction(values[scope].orEmpty().toMutableMap(), tools[scope]?.snapshot() ?: TestToolIndexState()))

        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T {
            val copy = values[scope].orEmpty().toMutableMap()
            val toolCopy = tools[scope]?.snapshot() ?: TestToolIndexState()
            val result = block(Transaction(copy, toolCopy))
            values[scope] = copy
            tools[scope] = toolCopy
            return result
        }

        private class Transaction(private val values: MutableMap<String, ByteArray>, private val tools: TestToolIndexState) : TimelineStoreTransaction {
            override suspend fun toolCall(callId: String) = tools.entries[callId]
            override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int) = tools.unresolved(afterCallId, maxRows)
            override suspend fun toolSweepGeneration() = tools.generation
            override suspend fun putToolCall(entry: TimelineToolIndexEntry) = tools.put(entry)
            override suspend fun setToolSweepGeneration(next: Long) = tools.advance(next)
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
