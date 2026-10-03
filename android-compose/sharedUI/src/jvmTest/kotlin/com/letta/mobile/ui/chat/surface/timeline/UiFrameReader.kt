package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.timeline.TimelineBodyPointer
import com.letta.mobile.data.timeline.TimelineDurableCheckpoint
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.data.timeline.TimelineMetadataPage
import com.letta.mobile.data.timeline.TimelinePageKey
import com.letta.mobile.data.timeline.TimelineReadPosition
import com.letta.mobile.data.timeline.TimelineStoreReader

/** The read view of a [UiFrameLedger] (letta-mobile-29sxj). */
internal class UiFrameReader(private val ledger: UiFrameLedger) : TimelineStoreReader {
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

    private companion object {
        const val MAX_BODY_BYTES = 65_536
    }
}
