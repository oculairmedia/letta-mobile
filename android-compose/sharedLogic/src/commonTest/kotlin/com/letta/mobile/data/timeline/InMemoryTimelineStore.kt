package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope

/**
 * The in-memory ledger the store-backed timeline tests run against.
 *
 * Shared rather than re-implemented per test: a store fake that disagrees with the real store about
 * read positions or transaction rollback makes its test prove something the product never does, and
 * three near-identical copies drift apart one override at a time.
 *
 * Reads honour [TimelineReadPosition] exactly as Room does: Around splits the page around the
 * anchor, and the older/newer edges are the page's own first/last rows, so Paging's keys mean what
 * they say. A transaction that throws restores the rows, evidence and checkpoint it
 * started from, which is the durability contract the writer tests depend on.
 */
internal class InMemoryTimelineStore(
    initial: TimelineDurableCheckpoint = TimelineDurableCheckpoint(0, TimelineContinuation.Initial, true),
) : TimelineBoundedStore {
    var current: TimelineDurableCheckpoint = initial
    var bodyReads = 0
    var reads = 0
    var puts = 0
    val rows = mutableMapOf<TimelinePageKey, TimelineStoredRecord>()
    val evidence = mutableMapOf<String, ByteArray>()
    private val tools = mutableMapOf<TimelineScope, TestToolIndexState>()

    /** Seeds evidence a test needs present before the first transaction. */
    fun putEvidence(key: String, value: ByteArray) {
        evidence[key] = value.copyOf()
    }

    override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T {
        reads++
        return block(Tx(tools[scope]?.snapshot() ?: TestToolIndexState()))
    }

    override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T {
        val before = current
        val oldRows = rows.toMap()
        val oldEvidence = evidence.toMap()
        val toolCopy = tools[scope]?.snapshot() ?: TestToolIndexState()
        return try {
            val transaction = Tx(toolCopy)
            block(transaction).also {
                check(!transaction.changed || transaction.revisionRequested) {
                    "Changed transaction needs nextRevision"
                }
                tools[scope] = toolCopy
            }
        } catch (failure: Throwable) {
            current = before
            rows.clear(); rows.putAll(oldRows)
            evidence.clear(); evidence.putAll(oldEvidence)
            throw failure
        }
    }

    private inner class Tx(private val tools: TestToolIndexState) : TimelineStoreTransaction {
        var changed = false
            private set
        var revisionRequested = false
            private set

        override suspend fun toolCall(callId: String) = tools.entries[callId]
        override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int) = tools.unresolved(afterCallId, maxRows)
        override suspend fun toolSweepGeneration() = tools.generation
        override suspend fun putToolCall(entry: TimelineToolIndexEntry) { changed = true; tools.put(entry) }
        override suspend fun setToolSweepGeneration(next: Long) { changed = true; tools.advance(next) }
        override suspend fun checkpoint() = current
        override suspend fun locate(identity: TimelineMessageId) = rows.keys.singleOrNull { it.identity == identity }

        override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage {
            require(maxRows in 1..MAX_PAGE_ROWS)
            val ascending = rows.values.sortedBy { it.key }
            val selected = when (position) {
                TimelineReadPosition.Tail -> ascending.takeLast(maxRows)
                is TimelineReadPosition.Before -> ascending.filter { it.key < position.key }.takeLast(maxRows)
                is TimelineReadPosition.After -> ascending.filter { it.key > position.key }.take(maxRows)
                is TimelineReadPosition.Around -> around(ascending, position.key, maxRows)
            }
            // Like Room and the desktop store, an edge is the page's OWN first/last row, reported only
            // when the ledger holds more beyond it. Paging passes it to Before/After, which exclude it.
            val older = selected.firstOrNull()?.takeIf { first -> ascending.any { it.key < first.key } }?.key
            val newer = selected.lastOrNull()?.takeIf { last -> ascending.any { it.key > last.key } }?.key
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

        /** Room's Around split: (maxRows - 1) / 2 older rows, the exact row if present, then newer rows. */
        private fun around(
            ascending: List<TimelineStoredRecord>,
            key: TimelinePageKey,
            maxRows: Int,
        ): List<TimelineStoredRecord> {
            val older = ascending.filter { it.key < key }.takeLast((maxRows - 1) / 2)
            val exact = ascending.filter { it.key == key }
            val newer = ascending.filter { it.key > key }.take(maxRows - older.size - exact.size)
            return older + exact + newer
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
            changed = true
            puts++
            rows[record.key] = record.copy(body = record.body.copyOf())
        }

        override suspend fun putEvidence(key: String, value: ByteArray) { changed = true; evidence[key] = value.copyOf() }
        override suspend fun deleteEvidence(key: String) { changed = true; evidence.remove(key) }

        override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean) {
            changed = true
            current = current.copy(continuation = continuation, hasMore = hasMore)
        }

        override suspend fun nextRevision(): Long {
            revisionRequested = true
            current = current.copy(revision = current.revision + 1)
            return current.revision
        }

        override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason) {
            changed = true
            rows.keys.removeAll { it.identity == identity }
        }
    }
}

/** Room's page bound (its DAO clamps every read to 128 rows). */
private const val MAX_PAGE_ROWS = 128

/** Runs the same change/revision contract the canonical engine applies around a writer. */
internal suspend fun InMemoryTimelineStore.engineTransaction(
    scope: TimelineScope,
    block: suspend TimelineStoreTransaction.() -> Boolean,
): Boolean = transaction(scope) {
    block().also { changed -> if (changed) nextRevision() }
}
