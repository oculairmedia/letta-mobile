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
 * real store's transaction has. Paging reads on its own threads while the engine writes, so every
 * access is synchronized; the real stores get that from their database.
 */
internal class UiFrameLedger {
    @Volatile var checkpoint = TimelineDurableCheckpoint(0, TimelineContinuation.Initial, true)
    @Volatile var toolSweepGeneration = 0L
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

    @Synchronized fun save() = Saved(checkpoint, toolSweepGeneration, TreeMap(rows), HashMap(evidence), HashMap(tools))

    @Synchronized fun restore(saved: Saved) {
        checkpoint = saved.checkpoint
        toolSweepGeneration = saved.toolSweepGeneration
        rows = saved.rows
        evidence = saved.evidence
        tools = saved.tools
    }

    @Synchronized fun put(record: TimelineStoredRecord) {
        rows[record.key] = record.copy(body = record.body.copyOf())
    }

    @Synchronized fun remove(identity: TimelineMessageId) {
        rows.keys.removeAll { it.identity == identity }
    }

    @Synchronized fun locate(identity: TimelineMessageId): TimelinePageKey? = rows.keys.singleOrNull { it.identity == identity }

    @Synchronized fun body(pointer: TimelineBodyPointer): ByteArray =
        rows.values.single { it.key.identity.value == pointer.value }.body

    /**
     * The rows a read at [position] selects, under the page contract the shipped stores keep
     * (RoomTimelineBoundedStore, DesktopTimelineBoundedStore): [TimelineMetadataPage.older] and
     * [TimelineMetadataPage.newer] are the page's own first and last keys, null at a real end, and
     * [TimelineReadPosition.Before] / [TimelineReadPosition.After] exclude the key they are given.
     *
     * letta-mobile-8p8mj: this used to return the neighbour OUTSIDE the page instead. Paging then
     * asked for Before(neighbour) and After(neighbour), each excluding that neighbour, so every page
     * boundary lost one row. It only bit when Paging refreshed around an anchor (a single-row page
     * with a boundary on each side), which is timing: on a loaded runner the turn's prompt and the
     * one before it went missing from the settled list, the overlay never drained, and the frame
     * tests failed with "settle never finished".
     */
    @Synchronized fun page(position: TimelineReadPosition, limit: Int): TimelineMetadataPage {
        val selected = select(position, limit)
        val older = selected.firstOrNull()?.key?.takeIf { rows.lowerKey(it) != null }
        val newer = selected.lastOrNull()?.key?.takeIf { rows.higherKey(it) != null }
        val revision = checkpoint.revision
        val metadata = selected.map {
            TimelineLedgerMetadata(it.key, TimelineBodyPointer(it.key.identity.value, it.body.size.toLong()), it.contentType, revision)
        }
        return TimelineMetadataPage(metadata, older, newer, revision)
    }

    private fun select(position: TimelineReadPosition, limit: Int): List<TimelineStoredRecord> = when (position) {
        TimelineReadPosition.Tail -> rows.values.toList().takeLast(limit)
        is TimelineReadPosition.Before -> before(position.key, limit)
        is TimelineReadPosition.After -> after(position.key, limit)
        is TimelineReadPosition.Around -> around(position.key, limit)
    }

    private fun before(key: TimelinePageKey, limit: Int) = rows.headMap(key, false).values.toList().takeLast(limit)

    private fun after(key: TimelinePageKey, limit: Int) = rows.tailMap(key, false).values.take(limit)

    /** A window centred on [key], as Room reads it: up to half before, the row itself, the rest after. */
    private fun around(key: TimelinePageKey, limit: Int): List<TimelineStoredRecord> {
        val exact = listOfNotNull(rows[key])
        val older = before(key, (limit - 1) / 2)
        return older + exact + after(key, limit - older.size - exact.size)
    }

    @Synchronized fun evidenceFor(key: String): ByteArray? = evidence[key]?.copyOf()

    @Synchronized fun putEvidence(key: String, value: ByteArray) {
        evidence[key] = value.copyOf()
    }

    @Synchronized fun deleteEvidence(key: String) {
        evidence.remove(key)
    }

    @Synchronized fun toolCall(callId: String): TimelineToolIndexEntry? = tools[callId]

    @Synchronized fun putToolCall(entry: TimelineToolIndexEntry) {
        tools[entry.callId] = entry
    }

    /** Owned, unreturned tool calls strictly after [afterCallId], ascending, at most [limit]. */
    @Synchronized fun unresolvedTools(afterCallId: String?, limit: Int): List<TimelineToolIndexEntry> =
        tools.values.filter { it.owner != null && !it.returned && (afterCallId == null || it.callId > afterCallId) }
            .sortedBy { it.callId }.take(limit)
}
