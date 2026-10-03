package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.timeline.TimelineBodyPointer
import com.letta.mobile.data.timeline.TimelineBoundedStore
import com.letta.mobile.data.timeline.TimelineContinuation
import com.letta.mobile.data.timeline.TimelineDurableCheckpoint
import com.letta.mobile.data.timeline.TimelineDurableDeleteReason
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.data.timeline.TimelineMetadataPage
import com.letta.mobile.data.timeline.TimelinePageKey
import com.letta.mobile.data.timeline.TimelineReadPosition
import com.letta.mobile.data.timeline.TimelineStoreReader
import com.letta.mobile.data.timeline.TimelineStoreTransaction
import com.letta.mobile.data.timeline.TimelineStoredRecord
import com.letta.mobile.data.timeline.TimelineToolIndexEntry
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.delay

/**
 * letta-mobile-29sxj: an in-memory [TimelineBoundedStore] for the frame harness. sharedLogic's own
 * in-memory store is internal to that module's tests, so the sharedUI tests cannot reach it.
 *
 * It holds ONE conversation (the harness opens one scope). A transaction that throws restores what
 * it started from. [readLatencyMillis] is the real time every read takes, as a device database
 * does, so Paging's loading states span Compose frames instead of collapsing into one.
 */
internal class UiFrameTimelineStore(private val readLatencyMillis: Long = 0) : TimelineBoundedStore {
    private val ledger = UiFrameLedger()

    override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T {
        if (readLatencyMillis > 0) delay(readLatencyMillis)
        return block(UiFrameAccess(ledger))
    }

    override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T {
        val before = ledger.save()
        return try {
            block(UiFrameAccess(ledger))
        } catch (failure: Throwable) {
            ledger.restore(before)
            throw failure
        }
    }
}

/** The reader and writer view of a [UiFrameLedger]. */
private class UiFrameAccess(private val ledger: UiFrameLedger) : TimelineStoreTransaction {
    override suspend fun toolCall(callId: String) = ledger.toolCall(callId)

    override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int) = ledger.unresolvedTools(afterCallId, maxRows)

    override suspend fun toolSweepGeneration() = ledger.toolSweepGeneration

    override suspend fun checkpoint(): TimelineDurableCheckpoint = ledger.checkpoint

    override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage =
        ledger.page(position, maxRows)

    override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? = ledger.locate(identity)

    override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray {
        require(maxBytes in 0..MAX_BODY_BYTES)
        val bytes = ledger.body(pointer)
        return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + maxBytes))
    }

    override suspend fun evidence(key: String, maxBytes: Int): ByteArray? =
        ledger.evidenceFor(key)?.also { check(it.size <= maxBytes) }

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

    private companion object {
        const val MAX_BODY_BYTES = 65_536
    }
}
