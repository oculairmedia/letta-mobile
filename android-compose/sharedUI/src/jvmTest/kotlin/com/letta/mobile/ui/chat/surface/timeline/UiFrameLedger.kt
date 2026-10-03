package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.timeline.TimelineBodyPointer
import com.letta.mobile.data.timeline.TimelineContinuation
import com.letta.mobile.data.timeline.TimelineDurableCheckpoint
import com.letta.mobile.data.timeline.TimelineLedgerMetadata
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.data.timeline.TimelineMetadataPage
import com.letta.mobile.data.timeline.TimelinePageKey
import com.letta.mobile.data.timeline.TimelineReadPosition
import com.letta.mobile.data.timeline.TimelineStoredRecord
import com.letta.mobile.data.timeline.TimelineToolIndexEntry
import java.util.TreeMap

/**
 * letta-mobile-29sxj: the rows, evidence, tool index and checkpoint of one conversation, held in
 * memory for the frame harness. [save] and [restore] give [UiFrameTimelineStore] the rollback a
 * real store's transaction has.
 */
internal class UiFrameLedger {
    var checkpoint = TimelineDurableCheckpoint(0, TimelineContinuation.Initial, true)
    var toolSweepGeneration = 0L
    private var rows = TreeMap<TimelinePageKey, TimelineStoredRecord>()
    private var evidence = HashMap<String, ByteArray>()
    private var tools = HashMap<String, TimelineToolIndexEntry>()

    /** A copy of everything a transaction may change. */
    class Saved(
        val checkpoint: TimelineDurableCheckpoint,
        val toolSweepGeneration: Long,
        val rows: TreeMap<TimelinePageKey, TimelineStoredRecord>,
        val evidence: HashMap<String, ByteArray>,
        val tools: HashMap<String, TimelineToolIndexEntry>,
    )

    fun save() = Saved(checkpoint, toolSweepGeneration, TreeMap(rows), HashMap(evidence), HashMap(tools))

    fun restore(saved: Saved) {
        checkpoint = saved.checkpoint
        toolSweepGeneration = saved.toolSweepGeneration
        rows = saved.rows
        evidence = saved.evidence
        tools = saved.tools
    }

    fun put(record: TimelineStoredRecord) {
        rows[record.key] = record.copy(body = record.body.copyOf())
    }

    fun remove(identity: TimelineMessageId) {
        rows.keys.removeAll { it.identity == identity }
    }

    fun locate(identity: TimelineMessageId): TimelinePageKey? = rows.keys.singleOrNull { it.identity == identity }

    fun body(pointer: TimelineBodyPointer): ByteArray =
        rows.values.single { it.key.identity.value == pointer.value }.body

    /** The rows a read at [position] selects, with the exclusive neighbours either side. */
    fun page(position: TimelineReadPosition, limit: Int): TimelineMetadataPage {
        val selected = select(position, limit)
        val older = selected.firstOrNull()?.let { rows.lowerKey(it.key) }
        val newer = selected.lastOrNull()?.let { rows.higherKey(it.key) }
        val revision = checkpoint.revision
        val metadata = selected.map {
            TimelineLedgerMetadata(it.key, TimelineBodyPointer(it.key.identity.value, it.body.size.toLong()), it.contentType, revision)
        }
        return TimelineMetadataPage(metadata, older, newer, revision)
    }

    private fun select(position: TimelineReadPosition, limit: Int): List<TimelineStoredRecord> = when (position) {
        TimelineReadPosition.Tail -> rows.values.toList().takeLast(limit)
        is TimelineReadPosition.Before -> rows.headMap(position.key, false).values.toList().takeLast(limit)
        is TimelineReadPosition.After -> rows.tailMap(position.key, false).values.take(limit)
        is TimelineReadPosition.Around -> listOfNotNull(rows[position.key])
    }

    fun evidenceFor(key: String): ByteArray? = evidence[key]?.copyOf()

    fun putEvidence(key: String, value: ByteArray) {
        evidence[key] = value.copyOf()
    }

    fun deleteEvidence(key: String) {
        evidence.remove(key)
    }

    fun toolCall(callId: String): TimelineToolIndexEntry? = tools[callId]

    fun putToolCall(entry: TimelineToolIndexEntry) {
        tools[entry.callId] = entry
    }

    /** Owned, unreturned tool calls strictly after [afterCallId], ascending, at most [limit]. */
    fun unresolvedTools(afterCallId: String?, limit: Int): List<TimelineToolIndexEntry> =
        tools.values.filter { it.owner != null && !it.returned && (afterCallId == null || it.callId > afterCallId) }
            .sortedBy { it.callId }.take(limit)
}
