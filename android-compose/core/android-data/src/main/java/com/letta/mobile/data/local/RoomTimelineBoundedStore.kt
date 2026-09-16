package com.letta.mobile.data.local

import androidx.room.withTransaction
import com.letta.mobile.data.timeline.*
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Codec is supplied by the shared engine; the Room backend never interprets continuation semantics. */
interface RoomTimelineCheckpointCodec {
    fun encode(checkpoint: TimelineDurableCheckpoint): ByteArray
    fun decode(bytes: ByteArray): TimelineDurableCheckpoint
}

object SharedRoomTimelineCheckpointCodec : RoomTimelineCheckpointCodec {
    override fun encode(checkpoint: TimelineDurableCheckpoint): ByteArray = TimelineDurableCheckpointCodec.encode(checkpoint)
    override fun decode(bytes: ByteArray): TimelineDurableCheckpoint = TimelineDurableCheckpointCodec.decode(bytes)
}

/**
 * Separately named canonical backend; legacy rollback remains the default until rollout reconciliation.
 * Blobs remain durable after replacement/deletion: pointers can outlive a metadata callback in the
 * shared body-resolution path. Reclamation requires an explicit pointer lease/retention protocol;
 * page release and speculative reference scans must not delete bodies.
 */
class RoomTimelineBoundedStore(
    private val database: TimelineLedgerDatabase,
    private val codec: RoomTimelineCheckpointCodec = SharedRoomTimelineCheckpointCodec,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TimelineBoundedStore {
    override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T =
        snapshot(scope, false) { block() }

    override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T =
        snapshot(scope, true, block)

    private suspend fun <T> snapshot(
        scope: TimelineScope,
        writable: Boolean,
        block: suspend TimelineStoreTransaction.() -> T,
    ): T = withContext(io) {
        database.withTransaction {
            val session = Session(ledgerScopeKey(scope), writable)
            try {
                val result = session.block()
                session.finish()
                result
            } finally {
                session.open = false
            }
        }
    }

    private inner class Session(val scope: ByteArray, val writable: Boolean) : TimelineStoreTransaction {
        val dao = database.ledger()
        var open = true
        var changed = false
        var revision: Long? = null
        var pending: TimelineDurableCheckpoint? = null
        val stagedIdentities = mutableListOf<ByteArray>()

        override suspend fun toolCall(callId: String): TimelineToolIndexEntry? {
            checkOpen()
            return dao.toolCall(scope, ledgerKey(callId))?.shared()
        }

        override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int): List<TimelineToolIndexEntry> {
            checkOpen()
            require(maxRows in 1..128)
            return (if (afterCallId == null) dao.unresolvedTools(scope, maxRows)
                else dao.unresolvedToolsAfter(scope, ledgerKey(afterCallId), maxRows)).map { it.shared() }
        }

        override suspend fun toolSweepGeneration(): Long {
            checkOpen()
            return dao.toolSweepGeneration(scope) ?: 0L
        }

        override suspend fun putToolCall(entry: TimelineToolIndexEntry) {
            write()
            require(entry.callId.length <= 4096 && (entry.owner?.value?.length ?: 0) <= 4096)
            dao.toolCall(LedgerToolCall(scope, ledgerKey(entry.callId), entry.owner?.let { ledgerKey(it.value) },
                entry.returned, entry.owner != null && !entry.returned))
        }

        override suspend fun setToolSweepGeneration(next: Long) {
            write()
            check(next > toolSweepGeneration()) { "Tool sweep generation must increase without overflow" }
            dao.toolSweep(LedgerToolSweep(scope, next))
        }

        private fun LedgerToolCall.shared() = TimelineToolIndexEntry(
            ledgerString(callId), owner?.let { TimelineMessageId(ledgerString(it)) }, returned,
        )

        fun checkOpen() { check(open) { "Snapshot callback escaped" } }
        fun write() { checkOpen(); check(writable) { "Read-only snapshot" }; changed = true }

        override suspend fun checkpoint(): TimelineDurableCheckpoint {
            checkOpen()
            return pending ?: dao.head(scope)?.let {
                codec.decode(it.checkpoint.copyOf()).also { decoded -> check(decoded.revision == it.revision) }
            } ?: TimelineDurableCheckpoint(0, null, true)
        }

        override suspend fun nextRevision(): Long {
            write()
            check(revision == null) { "Revision already allocated" }
            val old = checkpoint()
            check(old.revision < Long.MAX_VALUE) { "Ledger revision exhausted" }
            return (old.revision + 1).also { revision = it; pending = old.copy(revision = it) }
        }

        suspend fun finish() {
            if (!changed) return
            val next = checkNotNull(revision) { "Changed transaction requires nextRevision" }
            for (identity in stagedIdentities) dao.stampIdentity(scope, identity, -1, next)
            dao.head(LedgerHead(scope, next, codec.encode(checkpoint()).copyOf()))
        }

        override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? {
            checkOpen()
            return dao.locate(scope, ledgerKey(identity.value))?.key()
        }

        override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage {
            checkOpen()
            require(maxRows in 1..128)
            val rows = when (position) {
                TimelineReadPosition.Tail -> dao.tail(scope, maxRows).reversed()
                is TimelineReadPosition.Before -> dao.before(scope, position.key.order, ledgerKey(position.key.identity.value), maxRows).reversed()
                is TimelineReadPosition.After -> dao.after(scope, position.key.order, ledgerKey(position.key.identity.value), maxRows)
                is TimelineReadPosition.Around -> {
                    val key = position.key
                    val exact = dao.locate(scope, ledgerKey(key.identity.value))?.takeIf { it.key() == key }
                    val older = dao.before(scope, key.order, ledgerKey(key.identity.value), (maxRows - 1) / 2).reversed()
                    older + listOfNotNull(exact) + dao.after(scope, key.order, ledgerKey(key.identity.value), maxRows - older.size - if (exact == null) 0 else 1)
                }
            }
            val first = rows.firstOrNull()
            val last = rows.lastOrNull()
            return TimelineMetadataPage(
                rows.map { TimelineLedgerMetadata(it.key(), TimelineBodyPointer(it.pointer, it.bytes), it.contentType, if (it.revision == -1L) revision ?: checkpoint().revision else it.revision) },
                first?.takeIf { dao.hasBefore(scope, it.position, it.identity) }?.key(),
                last?.takeIf { dao.hasAfter(scope, it.position, it.identity) }?.key(),
                checkpoint().revision,
            )
        }

        override suspend fun put(record: TimelineStoredRecord) {
            write()
            require(record.key.identity.value.length <= 4096 && record.contentType.length <= 256)
            val blob = persist(record.body)
            val identity = ledgerKey(record.key.identity.value)
            dao.row(LedgerRow(scope, identity, record.key.order, blob.pointer, blob.bytes, record.contentType, -1))
            stagedIdentities += identity
        }

        suspend fun persist(value: ByteArray): LedgerBlob {
            val pointer = UUID.randomUUID().toString()
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0
            while (offset < value.size) {
                val chunk = value.copyOfRange(offset, minOf(value.size, offset + CHUNK_BYTES))
                digest.update(chunk)
                dao.chunk(LedgerChunk(scope, pointer, offset.toLong() / CHUNK_BYTES, chunk, checksum(chunk)))
                offset += chunk.size
            }
            return LedgerBlob(scope, pointer, value.size.toLong(), hex(digest.digest())).also { dao.blob(it) }
        }

        override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray {
            checkOpen()
            require(offset >= 0 && maxBytes in 0..MAX_BODY_READ)
            val blob = checkNotNull(dao.blob(scope, pointer.value)) { "Missing scoped body" }
            check(blob.bytes == pointer.encodedBytes) { "Body size mismatch" }
            if (offset >= blob.bytes || maxBytes == 0) return ByteArray(0)
            val result = ByteArray(minOf(maxBytes.toLong(), blob.bytes - offset).toInt())
            var copied = 0
            while (copied < result.size) {
                val at = offset + copied
                val chunk = checkNotNull(dao.chunk(scope, pointer.value, at / CHUNK_BYTES)) { "Missing body chunk" }
                check(chunk.payload.size == minOf(CHUNK_BYTES.toLong(), blob.bytes - chunk.ordinal * CHUNK_BYTES).toInt())
                check(checksum(chunk.payload) == chunk.checksum) { "Corrupt body chunk" }
                val start = (at % CHUNK_BYTES).toInt()
                val count = minOf(chunk.payload.size - start, result.size - copied)
                chunk.payload.copyInto(result, copied, start, start + count)
                copied += count
            }
            if (offset == 0L && result.size.toLong() == blob.bytes) check(checksum(result) == blob.checksum)
            return result
        }

        override suspend fun evidence(key: String, maxBytes: Int): ByteArray? {
            checkOpen()
            require(maxBytes in 0..MAX_BODY_READ)
            val entry = dao.evidence(scope, ledgerKey(key)) ?: return null
            check(entry.bytes <= maxBytes) { "Evidence exceeds budget" }
            return body(TimelineBodyPointer(entry.pointer, entry.bytes), 0, maxBytes)
        }

        override suspend fun putEvidence(key: String, value: ByteArray) {
            write()
            require(key.length <= 4096)
            val blob = persist(value)
            dao.evidence(LedgerEvidence(scope, ledgerKey(key), blob.pointer, blob.bytes))
        }

        override suspend fun deleteEvidence(key: String) { write(); dao.deleteEvidence(scope, ledgerKey(key)) }
        override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason) {
            write(); dao.deleteRow(scope, ledgerKey(identity.value))
        }
        override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean) {
            write(); pending = checkpoint().copy(continuation = continuation, hasMore = hasMore)
        }
    }

    companion object {
        const val CHUNK_BYTES = 64 * 1024
        const val MAX_BODY_READ = 2 * 1024 * 1024
    }
}

private fun LedgerRow.key() = TimelinePageKey(position, TimelineMessageId(ledgerString(identity)))
internal fun checksum(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
