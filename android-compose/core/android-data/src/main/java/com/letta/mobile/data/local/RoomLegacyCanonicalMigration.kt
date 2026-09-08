package com.letta.mobile.data.local

import androidx.room.withTransaction
import com.letta.mobile.data.timeline.*
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

sealed interface RoomCanonicalMigrationResult {
    data class Progress(val convertedRows: Long, val complete: Boolean) : RoomCanonicalMigrationResult
    data class LegacyFallback(val reason: String) : RoomCanonicalMigrationResult
    /** The source row is retained and the resume position has NOT advanced. Never display as complete. */
    data class Deferred(val order: Long, val encodedBytes: Long, val budgetBytes: Int) : RoomCanonicalMigrationResult
}

/**
 * Post-open conversion of verified raw rows using the engine-owned writer. Progress, canonical rows,
 * aliases and terminal evidence commit together. The separately named database is still dormant:
 * a successful validation is NOT a cross-file write lease and must never select the startup store.
 */
class RoomLegacyCanonicalMigration(
    private val source: LegacyLedgerCopySource,
    private val target: TimelineLedgerDatabase,
    private val validateRoot: suspend (TimelineScope, String) -> Boolean,
    private val maxEventBytes: Int = RoomTimelineBoundedStore.MAX_BODY_READ,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(source: RoomLegacyLedgerCopySource, target: TimelineLedgerDatabase) :
        this(source, target, source::validateRoot)

    init { require(maxEventBytes in 1..RoomTimelineBoundedStore.MAX_BODY_READ) }

    private val store = RoomTimelineBoundedStore(target)

    suspend fun step(scope: TimelineScope): RoomCanonicalMigrationResult = guarded {
        source.snapshot(scope) {
            val head = head()
            if (!head.supported) return@snapshot fallback("source_schema")
            val observed = store.read(scope) { progress() }
            val sourceRow = metadata(observed?.after ?: Long.MIN_VALUE, 1).singleOrNull()
            // Validate on the source transaction context, never by re-entering legacy from target.
            val rootVerified = (observed?.count ?: 0) == head.rowCount && validateRoot(scope, head.token)
            target.withTransaction {
                val copy = target.ledger().migration(ledgerScopeKey(scope))
                    ?: return@withTransaction fallback("raw_copy_missing")
                if (!copy.complete) return@withTransaction fallback("raw_copy_incomplete")
                if (copy.sourceToken != head.token || copy.copiedRows != head.rowCount) {
                    return@withTransaction fallback("source_changed")
                }
                store.transaction(scope) {
                    val progress = progress()
                    if (progress != observed) return@transaction fallback("canonical_revision_changed")
                    if (progress == null && checkpoint().revision != 0L) {
                        return@transaction fallback("canonical_scope_occupied")
                    }
                    if (progress != null && progress.generation != copy.generation) {
                        return@transaction fallback("generation_changed")
                    }
                    val previous = progress ?: ConversionProgress(copy.generation, Long.MIN_VALUE, 0, false, 0)
                    if (previous.revision != checkpoint().revision) return@transaction fallback("canonical_revision_changed")
                    if (previous.complete) {
                        check(rootVerified) { "Source ordered root mismatch" }
                        return@transaction RoomCanonicalMigrationResult.Progress(previous.count, true)
                    }
                    val row = target.ledger().migrationRows(ledgerScopeKey(scope), copy.generation, previous.after, 1).singleOrNull()
                    if (row == null) {
                        check(previous.count == head.rowCount) { "Conversion count mismatch" }
                        check(rootVerified) { "Source ordered root mismatch" }
                        val revision = nextRevision()
                        // A legacy backfill cursor is not a typed remote continuation. Reconcile remotely.
                        cursor(null, true)
                        putEvidence(PROGRESS, previous.copy(complete = true, revision = revision).encode())
                        return@transaction RoomCanonicalMigrationResult.Progress(previous.count, true)
                    }
                    if (row.bytes > maxEventBytes) return@transaction RoomCanonicalMigrationResult.Deferred(row.position, row.bytes, maxEventBytes)
                    check(row.position == previous.count) { "Conversion order mismatch" }
                    check(sourceRow == LegacyLedgerCopyRow(row.position, row.primaryIdentity, row.secondaryIdentity, row.bytes, row.checksum)) {
                        "Raw copy differs from source manifest"
                    }
                    val bytes = body(TimelineBodyPointer(row.pointer, row.bytes), 0, maxEventBytes)
                    check(bytes.size.toLong() == row.bytes && checksum(bytes) == row.checksum) { "Raw body mismatch" }
                    val stored = TimelineSnapshotCodec.json.decodeFromString(
                        StoredTimelineEvent.serializer(), bytes.decodeToString(throwOnInvalidSequence = true),
                    )
                    // The legacy UI decoder substitutes now() for bad dates; durable ordering cannot.
                    check(parseTimelineInstantOrNull(stored.dateIso) != null && stored.position.isFinite()) {
                        "Invalid legacy ordering"
                    }
                    val event = stored.toConfirmedTimelineEvent()
                    val revision = nextRevision()
                    TimelineExactCanonicalWriter(scope, maxEventBytes).mergeEvent(this, event)
                    val next = previous.copy(after = row.position, count = previous.count + 1, revision = revision)
                    putEvidence(PROGRESS, next.encode())
                    RoomCanonicalMigrationResult.Progress(next.count, false)
                }
            }
        }
    }

    /** Rechecks source and canonical CAS on every attempt; no cached success or activation marker. */
    suspend fun validateForActivation(scope: TimelineScope, expectedRevision: Long): RoomCanonicalMigrationResult = guarded {
        source.snapshot(scope) {
            val head = head()
            if (!head.supported) return@snapshot fallback("source_schema")
            val rootVerified = validateRoot(scope, head.token)
            target.withTransaction {
                val copy = target.ledger().migration(ledgerScopeKey(scope))
                    ?: return@withTransaction fallback("raw_copy_missing")
                if (!copy.complete || copy.sourceToken != head.token || copy.copiedRows != head.rowCount) {
                    return@withTransaction fallback("source_changed")
                }
                store.read(scope) {
                    val progress = progress() ?: return@read fallback("conversion_missing")
                    if (!progress.complete || progress.count != head.rowCount || progress.generation != copy.generation) {
                        return@read fallback("conversion_incomplete")
                    }
                    if (checkpoint().revision != expectedRevision || progress.revision != expectedRevision) {
                        return@read fallback("canonical_revision_changed")
                    }
                    check(rootVerified) { "Source ordered root mismatch" }
                    verifyCanonicalBodies(scope)
                    verifyEvidenceBodies(scope)
                    // Deliberately no enabled parameter. Architecture must establish write ownership first.
                    fallback("activation_dormant")
                }
            }
        }
    }

    /** Post-open audit only: bounded SQL/body chunks, no history-wide decoded collection. */
    private suspend fun TimelineStoreReader.verifyCanonicalBodies(scope: TimelineScope) {
        var position: TimelineReadPosition = TimelineReadPosition.Tail
        while (true) {
            val page = metadata(position, 128)
            for (row in page.rows) {
                verifyBody(scope, row.body)
            }
            val older = page.older ?: return
            position = TimelineReadPosition.Before(older)
        }
    }

    private suspend fun TimelineStoreReader.verifyEvidenceBodies(scope: TimelineScope) {
        var after: ByteArray? = null
        while (true) {
            val page = target.ledger().evidencePage(ledgerScopeKey(scope), after)
            if (page.isEmpty()) return
            for (entry in page) verifyBody(scope, TimelineBodyPointer(entry.pointer, entry.bytes))
            after = page.last().identity
        }
    }

    private suspend fun TimelineStoreReader.verifyBody(scope: TimelineScope, pointer: TimelineBodyPointer) {
        val blob = checkNotNull(target.ledger().blob(ledgerScopeKey(scope), pointer.value))
        val digest = ResumableLedgerSha256.restore("")
        var offset = 0L
        do {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val chunk = body(pointer, offset, RoomTimelineBoundedStore.CHUNK_BYTES)
            check(chunk.isNotEmpty() || pointer.encodedBytes == 0L) { "Incomplete canonical body" }
            digest.update(chunk)
            offset += chunk.size
        } while (offset < pointer.encodedBytes)
        check(digest.finish() == blob.checksum) { "Canonical body checksum mismatch" }
    }

    private suspend fun guarded(block: suspend () -> RoomCanonicalMigrationResult): RoomCanonicalMigrationResult = withContext(io) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fallback("integrity_or_storage_failure") }
    }

    private suspend fun TimelineStoreReader.progress(): ConversionProgress? =
        evidence(PROGRESS, 4096)?.let(ConversionProgress::decode)

    private data class ConversionProgress(val generation: String, val after: Long, val count: Long, val complete: Boolean, val revision: Long) {
        fun encode() = "$generation:$after:$count:$complete:$revision".encodeToByteArray()
        companion object {
            fun decode(bytes: ByteArray): ConversionProgress {
                val fields = bytes.decodeToString(throwOnInvalidSequence = true).split(':')
                require(fields.size == 5)
                return ConversionProgress(fields[0], fields[1].toLong(), fields[2].toLong(), fields[3].toBooleanStrict(), fields[4].toLong())
                    .also { require(it.count >= 0 && it.revision > 0) }
            }
        }
    }

    private companion object {
        const val PROGRESS = "migration/legacy-canonical/v1"
        fun fallback(reason: String) = RoomCanonicalMigrationResult.LegacyFallback(reason)
    }
}
