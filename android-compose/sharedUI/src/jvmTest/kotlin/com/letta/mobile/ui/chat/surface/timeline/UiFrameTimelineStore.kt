package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.timeline.TimelineBodyPointer
import com.letta.mobile.data.timeline.TimelineBoundedStore
import com.letta.mobile.data.timeline.TimelineContinuation
import com.letta.mobile.data.timeline.TimelineDurableCheckpoint
import com.letta.mobile.data.timeline.TimelineDurableDeleteReason
import com.letta.mobile.data.timeline.TimelineLedgerMetadata
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.data.timeline.TimelineMetadataPage
import com.letta.mobile.data.timeline.TimelinePageKey
import com.letta.mobile.data.timeline.TimelineReadPosition
import com.letta.mobile.data.timeline.TimelineStoreReader
import com.letta.mobile.data.timeline.TimelineStoreTransaction
import com.letta.mobile.data.timeline.TimelineStoredRecord
import com.letta.mobile.data.timeline.TimelineToolIndexEntry

import com.letta.mobile.data.timeline.snapshot.TimelineScope

/**
 * letta-mobile-29sxj: a copy of sharedLogic's test-only InMemoryTimelineStore (internal to that
 * module's tests, so unreachable from here), kept identical in behaviour.
 *
 * The in-memory ledger the store-backed timeline tests run against.
 *
 * Shared rather than re-implemented per test: a store fake that disagrees with the real store about
 * read positions or transaction rollback makes its test prove something the product never does, and
 * three near-identical copies drift apart one override at a time.
 *
 * Reads honour [TimelineReadPosition] exactly, including the older/newer neighbours, so Paging's
 * keys mean what they say. A transaction that throws restores the rows, evidence and checkpoint it
 * started from, which is the durability contract the writer tests depend on.
 */
internal class UiFrameTimelineStore(
    initial: TimelineDurableCheckpoint = TimelineDurableCheckpoint(0, TimelineContinuation.Initial, true),
) : TimelineBoundedStore {
    var current: TimelineDurableCheckpoint = initial
    var bodyReads = 0
    var reads = 0
    var puts = 0
    val rows = mutableMapOf<TimelinePageKey, TimelineStoredRecord>()
    val evidence = mutableMapOf<String, ByteArray>()
    private val tools = mutableMapOf<TimelineScope, UiFrameToolIndexState>()

    /** Seeds evidence a test needs present before the first transaction. */
    fun putEvidence(key: String, value: ByteArray) {
        evidence[key] = value.copyOf()
    }

    override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T {
        reads++
        return block(Tx(tools[scope]?.snapshot() ?: UiFrameToolIndexState()))
    }

    override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T {
        val before = current
        val oldRows = rows.toMap()
        val oldEvidence = evidence.toMap()
        val toolCopy = tools[scope]?.snapshot() ?: UiFrameToolIndexState()
        return try {
            block(Tx(toolCopy)).also { tools[scope] = toolCopy }
        } catch (failure: Throwable) {
            current = before
            rows.clear(); rows.putAll(oldRows)
            evidence.clear(); evidence.putAll(oldEvidence)
            throw failure
        }
    }

    private inner class Tx(private val tools: UiFrameToolIndexState) : TimelineStoreTransaction {
        override suspend fun toolCall(callId: String) = tools.entries[callId]
        override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int) = tools.unresolved(afterCallId, maxRows)
        override suspend fun toolSweepGeneration() = tools.generation
        override suspend fun putToolCall(entry: TimelineToolIndexEntry) = tools.put(entry)
        override suspend fun setToolSweepGeneration(next: Long) = tools.advance(next)
        override suspend fun checkpoint() = current
        override suspend fun locate(identity: TimelineMessageId) = rows.keys.singleOrNull { it.identity == identity }

        override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage {
            val ascending = rows.values.sortedBy { it.key }
            val selected = when (position) {
                TimelineReadPosition.Tail -> ascending.takeLast(maxRows)
                is TimelineReadPosition.Before -> ascending.filter { it.key < position.key }.takeLast(maxRows)
                is TimelineReadPosition.After -> ascending.filter { it.key > position.key }.take(maxRows)
                is TimelineReadPosition.Around -> ascending.filter { it.key == position.key }
            }
            val older = selected.firstOrNull()?.let { first -> ascending.lastOrNull { it.key < first.key }?.key }
            val newer = selected.lastOrNull()?.let { last -> ascending.firstOrNull { it.key > last.key }?.key }
            return TimelineMetadataPage(
                selected.map {
                    TimelineLedgerMetadata(
                        it.key,
                        TimelineBodyPointer(it.key.identity.value, it.body.size.toLong()),
                        it.contentType,
                        current.revision,
                    )
                },
                older, newer, current.revision,
            )
        }

        override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray {
            require(maxBytes in 0..65_536)
            bodyReads++
            val bytes = rows.values.single { it.key.identity.value == pointer.value }.body
            return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + maxBytes))
        }

        override suspend fun evidence(key: String, maxBytes: Int): ByteArray? =
            evidence[key]?.also { check(it.size <= maxBytes) }?.copyOf()

        override suspend fun put(record: TimelineStoredRecord) {
            puts++
            rows[record.key] = record.copy(body = record.body.copyOf())
        }

        override suspend fun putEvidence(key: String, value: ByteArray) { evidence[key] = value.copyOf() }
        override suspend fun deleteEvidence(key: String) { evidence.remove(key) }

        override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean) {
            current = current.copy(continuation = continuation, hasMore = hasMore)
        }

        override suspend fun nextRevision(): Long {
            current = current.copy(revision = current.revision + 1)
            return current.revision
        }

        override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason) {
            rows.keys.removeAll { it.identity == identity }
        }
    }
}

/** Copy-on-transaction tool state used by scoped storage fakes. */
internal class UiFrameToolIndexState(
    val entries: MutableMap<String, TimelineToolIndexEntry> = mutableMapOf(),
    var generation: Long = 0,
) {
    fun snapshot() = UiFrameToolIndexState(entries.toMutableMap(), generation)
    fun unresolved(after: String?, limit: Int): List<TimelineToolIndexEntry> {
        require(limit > 0)
        return entries.values.asSequence()
            .filter { it.owner != null && !it.returned && (after == null || it.callId > after) }
            .sortedBy { it.callId }.take(limit).toList()
    }
    fun put(entry: TimelineToolIndexEntry) {
        require(entry.callId.isNotBlank())
        entries[entry.callId] = entry
    }
    fun advance(next: Long) { require(next > generation); generation = next }
}
