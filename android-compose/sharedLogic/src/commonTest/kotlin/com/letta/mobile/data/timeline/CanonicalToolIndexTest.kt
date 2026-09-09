package com.letta.mobile.data.timeline

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CanonicalToolIndexTest {
    @Test fun distantReturnReplayAndGenerationFenceUseOnlyExactIndex() = runTest {
        val tx = Index()
        repeat(28_000) { tx.rows["unrelated-$it"] = TimelineToolIndexEntry("unrelated-$it", TimelineMessageId("row-$it"), true) }
        val owner = TimelineMessageId("old-owner")
        assertTrue(CanonicalToolIndex.observe(tx, "old-call", owner, false))
        val generation = CanonicalToolIndex.advanceGeneration(tx)
        assertTrue(CanonicalToolIndex.stillUnresolved(tx, generation, "old-call", owner))
        assertTrue(CanonicalToolIndex.observe(tx, "old-call", null, true))
        assertFalse(CanonicalToolIndex.stillUnresolved(tx, generation, "old-call", owner))
        assertFalse(CanonicalToolIndex.observe(tx, "old-call", owner, false))
        CanonicalToolIndex.observe(tx, "new-call", owner, false)
        CanonicalToolIndex.advanceGeneration(tx)
        assertFalse(CanonicalToolIndex.stillUnresolved(tx, generation, "new-call", owner))
        assertEquals(6, tx.lookups)
        assertEquals(0, tx.bodyReads)
    }

    @Test fun returnBeforeOwnerRemainsResolvedAndConflictingAliasFails() = runTest {
        val tx = Index()
        CanonicalToolIndex.observe(tx, "call", null, true)
        val owner = TimelineMessageId("canonical")
        CanonicalToolIndex.observe(tx, "call", owner, false)
        assertEquals(TimelineToolIndexEntry("call", owner, true), tx.toolCall("call"))
        assertFailsWith<IllegalStateException> {
            CanonicalToolIndex.observe(tx, "call", TimelineMessageId("other"), false)
        }
        assertEquals(owner, tx.toolCall("call")?.owner)
    }

    private class Index : TimelineStoreTransaction {
        val rows = mutableMapOf<String, TimelineToolIndexEntry>()
        var lookups = 0
        var bodyReads = 0
        var generation = 0L
        override suspend fun toolCall(callId: String): TimelineToolIndexEntry? { lookups++; return rows[callId] }
        override suspend fun putToolCall(entry: TimelineToolIndexEntry) { rows[entry.callId] = entry }
        override suspend fun toolSweepGeneration() = generation
        override suspend fun setToolSweepGeneration(next: Long) { require(next > generation); generation = next }
        override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int): List<TimelineToolIndexEntry> =
            TestToolIndexState(rows, generation).unresolved(afterCallId, maxRows)
        override suspend fun checkpoint(): TimelineDurableCheckpoint = error("No ledger access")
        override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage = error("No ledger access")
        override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? = error("No ledger access")
        override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray { bodyReads++; error("No bodies") }
        override suspend fun evidence(key: String, maxBytes: Int): ByteArray? = error("No evidence access")
        override suspend fun put(record: TimelineStoredRecord): Unit = error("No ledger access")
        override suspend fun putEvidence(key: String, value: ByteArray): Unit = error("No evidence access")
        override suspend fun deleteEvidence(key: String): Unit = error("No evidence access")
        override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean): Unit = error("No cursor access")
        override suspend fun nextRevision(): Long = error("Caller owns revision")
        override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason): Unit = error("No deletion")
    }
}
