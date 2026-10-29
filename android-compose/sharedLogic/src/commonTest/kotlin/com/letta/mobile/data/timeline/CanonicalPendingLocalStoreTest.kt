package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.api.PendingDispatchOwners

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.datetime.Instant

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

    @Test fun lostSendingAndSentRecordsBothRemainRetryableAndOnlyRetryRevivesAFailure() = runTest {
        val pending = CanonicalPendingLocalStore(Store())
        val scope = TimelineScope("backend", "conversation")
        val sending = CanonicalPendingLocalStore.Record("sending", "first", emptyList(), "2026-01-01T00:00:00Z")
        val sent = sending.copy(otid = "sent", content = "second")
        pending.save(scope, sending)
        pending.save(scope, sent)
        pending.mark(scope, sent.otid, CanonicalPendingLocalStore.Delivery.Sent)
        assertEquals(2, pending.retireLostEchoes(scope, pending.load(scope), minutesFromNow(10)))
        // Neither a late Sent nor a Sending report undoes a failure.
        pending.mark(scope, sending.otid, CanonicalPendingLocalStore.Delivery.Sent)
        pending.mark(scope, sending.otid, CanonicalPendingLocalStore.Delivery.Sending)
        assertEquals(listOf(sending.otid, sent.otid), pending.load(scope).map { it.otid })
        assertEquals(listOf(CanonicalPendingLocalStore.Delivery.Failed, CanonicalPendingLocalStore.Delivery.Failed),
            pending.load(scope).map { it.delivery })
        // An explicit retry revives it at the next attempt, whose transport outcome then applies.
        val retry = assertNotNull(pending.retry(scope, sending.otid))
        assertEquals(CanonicalPendingLocalStore.Delivery.Sending, retry.delivery)
        assertEquals(1L, retry.attempt)
        pending.mark(scope, sending.otid, CanonicalPendingLocalStore.Delivery.Sent, expectedAttempt = 0)
        assertEquals(CanonicalPendingLocalStore.Delivery.Sending, pending.load(scope).first().delivery)
        pending.mark(scope, sending.otid, CanonicalPendingLocalStore.Delivery.Sent, expectedAttempt = 1)
        assertEquals(CanonicalPendingLocalStore.Delivery.Sent, pending.load(scope).first().delivery)
    }

    @Test fun aRetriedOldPromptAwaitsItsEchoFromTheRetryNotFromItsFirstSend() = runTest {
        val pending = CanonicalPendingLocalStore(Store())
        val scope = TimelineScope("backend", "conversation")
        val old = CanonicalPendingLocalStore.Record("old", "prompt", emptyList(), "2026-01-01T00:00:00Z")
        pending.save(scope, old)
        pending.mark(scope, old.otid, CanonicalPendingLocalStore.Delivery.Failed)
        assertNotNull(pending.retry(scope, old.otid))
        pending.mark(scope, old.otid, CanonicalPendingLocalStore.Delivery.Sent, expectedAttempt = 1)
        assertEquals(0, pending.retireLostEchoes(scope, pending.load(scope), minutesFromNow(1)))
        assertEquals(CanonicalPendingLocalStore.Delivery.Sent, pending.load(scope).single().delivery)
        assertEquals(1, pending.retireLostEchoes(scope, pending.load(scope), minutesFromNow(10)))
        assertEquals("2026-01-01T00:00:00Z", pending.load(scope).single().sentAt)
    }

    @Test fun horizonRetiresAbandonedSendsButPreservesActiveDispatchesAndRetries() = runTest {
        val dispatchOwners = PendingDispatchOwners()
        val pending = CanonicalPendingLocalStore(Store(), dispatchOwners)
        val scope = TimelineScope("horizon-backend", "conversation")
        val record = CanonicalPendingLocalStore.Record("active", "prompt", emptyList(), "2026-01-01T00:00:00Z")
        pending.save(scope, record)
        pending.save(scope, record.copy(otid = "retry", attempt = 1))
        pending.save(scope, record.copy(otid = "abandoned"))
        var active = true
        dispatchOwners.retain(scope.backendId, scope.conversationId, "active") { active }
        dispatchOwners.retain(scope.backendId, scope.conversationId, "retry") { active }
        val now = minutesFromNow(10)
        assertEquals(1, pending.retireLostEchoes(scope, pending.load(scope), now))
        assertEquals(listOf(CanonicalPendingLocalStore.Delivery.Sending, CanonicalPendingLocalStore.Delivery.Sending,
            CanonicalPendingLocalStore.Delivery.Failed), pending.load(scope).map { it.delivery })
        active = false
        assertEquals(2, pending.retireLostEchoes(scope, pending.load(scope), now))
    }

    @Test fun anOlderPendingStoreIsReadAndReplacedOnTheFirstWrite() = runTest {
        val ledger = Store()
        val scope = TimelineScope("backend", "conversation")
        val legacy = """{"version":1,"records":[{"otid":"legacy","content":"hi","attachments":[],""" +
            """"sentAt":"2026-01-01T00:00:00Z","delivery":"Sent","attempt":2}]}"""
        ledger.seed(scope, "pending/local/v1", legacy.encodeToByteArray())
        val pending = CanonicalPendingLocalStore(ledger)
        val expected = CanonicalPendingLocalStore.Record(
            "legacy", "hi", emptyList(), "2026-01-01T00:00:00Z", CanonicalPendingLocalStore.Delivery.Sent, attempt = 2,
        )
        assertEquals(listOf(expected), pending.load(scope))
        pending.save(scope, expected.copy(otid = "new", delivery = CanonicalPendingLocalStore.Delivery.Sending, attempt = 0))
        assertEquals(null, ledger.value(scope, "pending/local/v1"))
        assertEquals(listOf("legacy", "new"), pending.load(scope).map { it.otid })
        assertEquals(expected, pending.load(scope).first())
    }

    private fun minutesFromNow(minutes: Long): TimelineInstant =
        parseTimelineInstant(Instant.fromEpochMilliseconds(timelineCurrentTimeMillis() + minutes * 60_000L).toString())

    private class Store : TimelineBoundedStore {
        private val values = mutableMapOf<TimelineScope, MutableMap<String, ByteArray>>()

        fun seed(scope: TimelineScope, key: String, value: ByteArray) {
            values.getOrPut(scope) { mutableMapOf() }[key] = value
        }

        fun value(scope: TimelineScope, key: String): ByteArray? = values[scope]?.get(key)
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
            private var allocated = false
            override suspend fun nextRevision(): Long {
                check(!allocated) { "Revision already allocated" }
                allocated = true
                return 1L
            }
            override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason): Unit = error("No history deletion expected")
        }
    }
}
