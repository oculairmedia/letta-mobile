package com.letta.mobile.data.local

import androidx.room.withTransaction
import com.letta.mobile.data.timeline.*
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** Post-open maintenance only. Each step audits one metadata item or <=64KiB of one body. */
internal class RoomTimelineValidation(
    private val legacy: LettaDatabase,
    private val target: TimelineLedgerDatabase,
    private val mapping: TimelineOwnershipAuthority.Mapping? = null,
) {
    data class Progress(val complete: Boolean, val metadataRows: Int, val bodyBytes: Int, val certifiedRevision: Long?)
    @Serializable private data class State(
        val identity: String, val certificate: String = UUID.randomUUID().toString(),
        val phase: Int = 0, val after: String? = null, val order: Long = Long.MIN_VALUE,
        val count: Long = 0, val digest: String = "", val chain: String = "",
        val pointer: String? = null, val size: Long = 0, val offset: Long = 0, val checksum: String = "",
    )
    private val source = RoomLegacyLedgerCopySource(legacy, mapping)
    private val store = RoomTimelineBoundedStore(target)

    suspend fun step(lease: TimelineOwnershipAuthority.Lease): Progress = source.snapshot(lease.scope) {
        val head = head()
        check(head.supported)
        val sourceScope = mapping?.source ?: lease.scope
        val normalized = legacy.confirmedTimelineSnapshotDao().getNormalizedHead(sourceScope.backendId, sourceScope.conversationId)
        if (normalized == null) check(head.rowCount == 0L) { "Empty source claimed rows" }
        val reader = this
        target.withTransaction {
            val scope = ledgerScopeKey(lease.scope)
            val dao = target.ledger()
            val copy = checkNotNull(dao.migration(scope))
            check(copy.complete && copy.sourceToken == head.token && copy.copiedRows == head.rowCount)
            val revision = checkNotNull(dao.head(scope)).revision
            checkConversion(lease.scope, copy.generation, head.rowCount, revision)
            val identity = identity(lease, head.token, copy.generation, revision)
            val loaded = load(lease.scope) ?: State(identity)
            check(loaded.identity == identity) { "Stale validation identity; explicit recovery required" }
            if (loaded.phase == 4) return@withTransaction Progress(true, 24, MAX_STATE + 4096, revision)
            val audit = Audit(AuditSource(reader, head, normalized), AuditTarget(dao, scope, revision), loaded)
            if (loaded.pointer != null) audit.readBodySlice() else audit.advancePhases()
            currentCoroutineContext().ensureActive()
            save(scope, audit.state)
            // Conservative aggregate bounds include point reads and progress/conversion evidence.
            val complete = audit.state.phase == 4
            Progress(complete, audit.rows + 24, audit.bytesRead + MAX_STATE + 4096, revision.takeIf { complete })
        }
    }

    /** What the legacy side of one step reads: its snapshot reader, head and normalized root. */
    private class AuditSource(
        val reader: LegacyLedgerCopyReader,
        val head: LegacyLedgerCopyHead,
        val normalized: NormalizedTimelineSnapshotHeadEntity?,
    )

    /** What the target side of one step reads: the ledger, the scope key and the head revision. */
    private class AuditTarget(val dao: TimelineLedgerDao, val scope: ByteArray, val revision: Long)

    /**
     * One step's audit, inside the step's snapshot and target transaction: either the next slice of a
     * staged body, or the current phase's next item. A phase that finds nothing left hands straight on
     * to the next phase in the same step - its end probe is one point read, so an empty phase need not
     * cost a whole step, i.e. a fresh lease, legacy snapshot and target transaction
     * (letta-mobile-qfrer: an empty source used to pay that five times). A step still audits at most
     * one item or one body slice.
     */
    private inner class Audit(
        private val source: AuditSource,
        target: AuditTarget,
        var state: State,
    ) {
        private val dao = target.dao
        private val scope = target.scope
        private val revision = target.revision

        var rows = 0
            private set
        var bytesRead = 0
            private set

        suspend fun readBodySlice() {
            val pointer = checkNotNull(state.pointer)
            val blob = checkNotNull(dao.blob(scope, pointer))
            check(blob.bytes == state.size && blob.checksum == state.checksum)
            // SQL slices avoid materializing a full 64KiB chunk alongside audit-state bodies.
            val bytes = if (state.size == 0L) ByteArray(0) else checkNotNull(dao.auditChunk(
                scope, pointer, state.offset / 65536, (state.offset % 65536).toInt(), 32768,
            ))
            bytesRead = bytes.size
            check(bytes.size.toLong() == minOf(32768L, state.size - state.offset))
            val digest = ResumableLedgerSha256.restore(state.digest)
            digest.update(bytes)
            val offset = state.offset + bytes.size
            state = if (offset == state.size) {
                check(digest.finish() == state.checksum) { "Audit body checksum mismatch" }
                state.copy(pointer = null, size = 0, offset = 0, checksum = "", digest = "")
            } else state.copy(offset = offset, digest = digest.checkpoint())
        }

        suspend fun advancePhases() {
            do {
                val phaseBefore = state.phase
                when (phaseBefore) {
                    0 -> auditSourceRow()
                    1 -> auditLedgerRow()
                    2 -> auditEvidence()
                    3 -> auditTool()
                    else -> error("Invalid audit phase")
                }
            } while (state.phase != phaseBefore && state.phase < 4)
        }

        /** Phase 0: the next legacy metadata row folded into the root digest; at the end, the root checked. */
        private suspend fun auditSourceRow() {
            val row = source.reader.metadata(state.order, 1).singleOrNull()
            if (row == null) {
                checkSourceRoot()
                state = state.copy(phase = 1, order = Long.MIN_VALUE, count = 0, digest = "", chain = "")
                return
            }
            val normalized = checkNotNull(source.normalized) { "Empty source produced rows" }
            rows++
            check(row.order == state.count && row.order <= Int.MAX_VALUE)
            state = if (normalized.rowDigest.startsWith(CHAIN_ROW_DIGEST_PREFIX)) chainRow(row) else digestRow(row)
        }

        private fun checkSourceRoot() {
            check(state.count == source.head.rowCount)
            val normalized = source.normalized
            if (normalized == null) {
                check(source.head.rowCount == 0L) { "Empty source claimed rows" }
                return
            }
            val actual = if (normalized.rowDigest.startsWith(CHAIN_ROW_DIGEST_PREFIX))
                state.chain.ifEmpty { normalizedRowDigest(emptyList()) }
            else ResumableLedgerSha256.restore(state.digest).finish()
            check(actual == normalized.rowDigest.removePrefix(CHAIN_ROW_DIGEST_PREFIX).lowercase()) { "Source root mismatch" }
        }

        private fun chainRow(row: LegacyLedgerCopyRow): State {
            val fields = object : NormalizedTimelineRowDigestFields {
                override val identityPrimary = row.primary
                override val identitySecondary = row.secondary
                override val eventOrder = row.order.toInt()
                override val checksum = row.checksum
            }
            val chain = incrementalNormalizedRowDigest(state.chain.ifEmpty { normalizedRowDigest(emptyList()) }, listOf(fields))
                .removePrefix(CHAIN_ROW_DIGEST_PREFIX)
            return state.copy(order = row.order, count = state.count + 1, chain = chain)
        }

        private fun digestRow(row: LegacyLedgerCopyRow): State {
            val digest = ResumableLedgerSha256.restore(state.digest)
            val encoded = listOf(row.primary.toString(), row.secondary.toString(), row.order.toString(), row.checksum)
                .joinToString("") { "${it.length}:$it;" }.toByteArray(Charsets.UTF_8)
            digest.update(encoded)
            return state.copy(order = row.order, count = state.count + 1, digest = digest.checkpoint())
        }

        /** Phase 1: the ledger's rows, newest first, each body staged for slicing. */
        private suspend fun auditLedgerRow() {
            val after = state.after
            val row = if (after == null) dao.tail(scope, 1).singleOrNull()
                else dao.before(scope, state.order, ledgerKey(after), 1).singleOrNull()
            if (row == null) {
                state = state.copy(phase = 2, after = null)
                return
            }
            rows++
            check(row.revision in 1..revision)
            state = stage(state.copy(after = ledgerString(row.identity), order = row.position), row.pointer, row.bytes, scope)
        }

        /** Phase 2: evidence entries other than the audit's own checkpoint key, each body staged. */
        private suspend fun auditEvidence() {
            val after = state.after
            val entry = if (after == null) dao.auditEvidenceFirst(scope, ledgerKey(KEY))
                else dao.auditEvidence(scope, ledgerKey(KEY), ledgerKey(after))
            if (entry == null) {
                state = state.copy(phase = 3, after = null)
                return
            }
            rows++
            state = stage(state.copy(after = ledgerString(entry.identity)), entry.pointer, entry.bytes, scope)
        }

        /** Phase 3: tool-call records, each owner resolved; at the end the audit is complete. */
        private suspend fun auditTool() {
            val after = state.after
            val entry = if (after == null) dao.auditToolFirst(scope) else dao.auditTool(scope, ledgerKey(after))
            if (entry == null) {
                check((dao.toolSweepGeneration(scope) ?: 0) >= 0)
                state = state.copy(phase = 4)
                return
            }
            rows++
            ledgerString(entry.callId)
            check(entry.unresolved == (entry.owner != null && !entry.returned))
            entry.owner?.let { checkNotNull(dao.locate(scope, it)) { "Tool owner missing" } }
            state = state.copy(after = ledgerString(entry.callId))
        }
    }

    /** Point reads only. Must be called while the factory holds the authority lock. */
    suspend fun receipt(lease: TimelineOwnershipAuthority.Lease, revision: Long): TimelineOwnershipAuthority.Receipt = source.snapshot(lease.scope) {
        val head = head()
        check(head.supported)
        target.withTransaction {
            val scope = ledgerScopeKey(lease.scope)
            val copy = checkNotNull(target.ledger().migration(scope))
            check(copy.complete && copy.sourceToken == head.token && copy.copiedRows == head.rowCount)
            check(target.ledger().head(scope)?.revision == revision)
            val state = checkNotNull(load(lease.scope)) { "Validation required" }
            check(state.phase == 4 && state.pointer == null && state.identity == identity(lease, head.token, copy.generation, revision))
            TimelineOwnershipAuthority.Receipt(head.token, copy.generation, revision, state.certificate)
        }
    }

    private suspend fun checkConversion(scope: TimelineScope, generation: String, count: Long, revision: Long) {
        store.read(scope) {
            val fields = checkNotNull(evidence("migration/legacy-canonical/v1", 4096))
                .decodeToString(throwOnInvalidSequence = true).split(':')
            check(fields.size == 5 && fields[0] == generation && fields[2].toLong() == count &&
                fields[3] == "true" && fields[4].toLong() == revision) { "Conversion incomplete or stale" }
        }
    }

    private suspend fun stage(state: State, pointer: String, size: Long, scope: ByteArray): State {
        val blob = checkNotNull(target.ledger().blob(scope, pointer))
        check(blob.bytes == size)
        return state.copy(pointer = pointer, size = size, offset = 0, checksum = blob.checksum, digest = "")
    }

    private suspend fun load(scope: TimelineScope): State? {
        val row = target.ledger().validation(ledgerScopeKey(scope)) ?: return null
        val bytes = row.payload
        check(bytes.size <= MAX_STATE && checksum(bytes) == row.checksum) { "Corrupt validation checkpoint" }
        val state = Json.decodeFromString<State>(bytes.decodeToString(throwOnInvalidSequence = true))
        check(state.phase in 0..4 && state.count >= 0 && state.size >= 0 && state.offset in 0..state.size)
        check(UUID.fromString(state.certificate).toString() == state.certificate)
        return state
    }

    // A single maintenance row per scope; no canonical revision change or blob deletion.
    private suspend fun save(scope: ByteArray, state: State) {
        val bytes = Json.encodeToString(State.serializer(), state).encodeToByteArray()
        require(bytes.size <= MAX_STATE)
        target.ledger().validation(LedgerValidationState(scope, bytes, checksum(bytes)))
    }

    private fun identity(lease: TimelineOwnershipAuthority.Lease, token: String, generation: String, revision: Long) =
        checksum(listOf(lease.scope.backendId, lease.scope.conversationId, lease.scope.agentId, lease.epoch.toString(), token, generation, revision.toString(), "audit-v1")
            .joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }.encodeToByteArray())

    companion object { const val KEY = "migration/validation/v1"; private const val MAX_STATE = 16 * 1024 }
}
