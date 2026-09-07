package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope

/** Signed order with Kotlin String lexicographic identity ordering; never a floating point offset. */
data class TimelinePageKey(val order: Long, val identity: TimelineMessageId) : Comparable<TimelinePageKey> {
    override fun compareTo(other: TimelinePageKey): Int =
        order.compareTo(other.order).takeIf { it != 0 } ?: identity.value.compareTo(other.identity.value)
}

data class TimelineBodyPointer(val value: String, val encodedBytes: Long) {
    init { require(value.isNotBlank()); require(encodedBytes >= 0) }
}

data class TimelineLedgerMetadata(
    val key: TimelinePageKey,
    val body: TimelineBodyPointer,
    val contentType: String,
    val revision: Long,
)

sealed interface TimelineReadPosition {
    data object Tail : TimelineReadPosition
    data class Before(val key: TimelinePageKey) : TimelineReadPosition
    data class After(val key: TimelinePageKey) : TimelineReadPosition
    data class Around(val key: TimelinePageKey) : TimelineReadPosition
}

/** Ascending rows. Older/newer are exclusive boundary keys, null only at a known end. */
data class TimelineMetadataPage(
    val rows: List<TimelineLedgerMetadata>,
    val older: TimelinePageKey?,
    val newer: TimelinePageKey?,
    val revision: Long,
)

data class TimelineDurableCheckpoint(
    val revision: Long,
    val continuation: TimelineContinuation?,
    val hasMore: Boolean,
)

/** Opaque serialized bytes: platform stores must not project, reconcile or interpret evidence. */
data class TimelineStoredRecord(
    val key: TimelinePageKey,
    val contentType: String,
    val body: ByteArray,
)

enum class TimelineDurableDeleteReason { UserDeletion, ConversationDeletion, AccountRemoval }

/**
 * Primitive indexed storage only. The callback is a consistent snapshot/atomic transaction and must
 * not escape. Cancellation before commit rolls back; commit is the durability linearization point.
 * Read limits apply before allocation/decoding. Evidence is indexed by exact shared semantic keys,
 * not only message IDs. All keys, pointers and revisions are scoped to the supplied TimelineScope.
 * Page release deliberately has no storage operation. Arrays must be copied at persistence boundaries.
 */
interface TimelineBoundedStore {
    suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T
    suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T
}

interface TimelineStoreReader {
    suspend fun checkpoint(): TimelineDurableCheckpoint
    suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage
    suspend fun locate(identity: TimelineMessageId): TimelinePageKey?
    /** Returns at most maxBytes starting at offset; supports bodies larger than a resident page. */
    suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray
    /** Exact point lookup; implementations fail rather than silently truncate an oversized value. */
    suspend fun evidence(key: String, maxBytes: Int): ByteArray?
}

interface TimelineStoreTransaction : TimelineStoreReader {
    /** Shared engine assigns canonical key and serialized body; backend stamps transaction revision. */
    suspend fun put(record: TimelineStoredRecord)
    suspend fun putEvidence(key: String, value: ByteArray)
    suspend fun deleteEvidence(key: String)
    suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean)
    /** Exactly once per changed transaction; durable monotonic revision, fail on Long exhaustion. */
    suspend fun nextRevision(): Long
    suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason)
}
