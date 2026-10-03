package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.timeline.TimelineContinuation
import com.letta.mobile.data.timeline.TimelineDurableDeleteReason
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.data.timeline.TimelineStoreReader
import com.letta.mobile.data.timeline.TimelineStoreTransaction
import com.letta.mobile.data.timeline.TimelineStoredRecord
import com.letta.mobile.data.timeline.TimelineToolIndexEntry

/** The write view of a [UiFrameLedger]; reads are [UiFrameReader]'s (letta-mobile-29sxj). */
internal class UiFrameTransaction(
    private val ledger: UiFrameLedger,
    reader: TimelineStoreReader = UiFrameReader(ledger),
) : TimelineStoreTransaction, TimelineStoreReader by reader {
    override suspend fun putToolCall(entry: TimelineToolIndexEntry) = ledger.putToolCall(entry)

    override suspend fun setToolSweepGeneration(next: Long) {
        require(next > ledger.toolSweepGeneration)
        ledger.toolSweepGeneration = next
    }

    override suspend fun put(record: TimelineStoredRecord) = ledger.put(record)

    override suspend fun putEvidence(key: String, value: ByteArray) = ledger.putEvidence(key, value)

    override suspend fun deleteEvidence(key: String) = ledger.deleteEvidence(key)

    override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean) {
        ledger.checkpoint = ledger.checkpoint.copy(continuation = continuation, hasMore = hasMore)
    }

    override suspend fun nextRevision(): Long {
        ledger.checkpoint = ledger.checkpoint.copy(revision = ledger.checkpoint.revision + 1)
        return ledger.checkpoint.revision
    }

    override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason) = ledger.remove(identity)
}
