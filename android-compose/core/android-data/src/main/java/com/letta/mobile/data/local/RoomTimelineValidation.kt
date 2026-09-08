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
    copySource: RoomLegacyLedgerCopySource? = null,
) {
    data class Progress(val complete: Boolean, val metadataRows: Int, val bodyBytes: Int, val certifiedRevision: Long?)
    @Serializable private data class State(
        val identity: String, val certificate: String = UUID.randomUUID().toString(),
        val phase: Int = 0, val after: String? = null, val order: Long = Long.MIN_VALUE,
        val count: Long = 0, val digest: String = "", val chain: String = "",
        val pointer: String? = null, val size: Long = 0, val offset: Long = 0, val checksum: String = "",
    )
    private val source = copySource ?: RoomLegacyLedgerCopySource(legacy, mapping)
    private val store = RoomTimelineBoundedStore(target)

    suspend fun step(lease: TimelineOwnershipAuthority.Lease): Progress = source.snapshot(lease.scope) {
        val head = head()
        check(head.supported)
        val sourceScope = mapping?.source ?: lease.scope
        val normalized = legacy.confirmedTimelineSnapshotDao().getNormalizedHead(sourceScope.backendId, sourceScope.conversationId)
        if (normalized == null) {
            check(head.kind == LegacyLedgerCopyKind.ManifestOnly || head.rowCount == 0L) {
                "Empty source claimed rows"
            }
        }
        target.withTransaction {
            val scope = ledgerScopeKey(lease.scope)
            val dao = target.ledger()
            val copy = checkNotNull(dao.migration(scope))
            check(copy.complete && copy.sourceToken == head.token && copy.copiedRows == head.rowCount)
            val revision = checkNotNull(dao.head(scope)).revision
            checkConversion(lease.scope, copy.generation, head.rowCount, revision)
            val identity = identity(lease, head.token, copy.generation, revision)
            var state = load(lease.scope) ?: State(identity)
            check(state.identity == identity) { "Stale validation identity; explicit recovery required" }
            if (state.phase == 4) return@withTransaction Progress(true, 24, MAX_STATE + 4096, revision)
            var rows = 0
            var bytesRead = 0
            if (state.pointer != null) {
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
            } else when (state.phase) {
                0 -> {
                    val row = metadata(state.order, 1).singleOrNull()
                    if (row == null) {
                        check(state.count == head.rowCount)
                        if (normalized == null) {
                            check(head.kind == LegacyLedgerCopyKind.ManifestOnly || head.rowCount == 0L) {
                                "Empty source claimed rows"
                            }
                        } else {
                            val actual = if (normalized.rowDigest.startsWith(CHAIN_ROW_DIGEST_PREFIX))
                                state.chain.ifEmpty { normalizedRowDigest(emptyList()) }
                            else ResumableLedgerSha256.restore(state.digest).finish()
                            check(actual == normalized.rowDigest.removePrefix(CHAIN_ROW_DIGEST_PREFIX).lowercase()) { "Source root mismatch" }
                        }
                        state = state.copy(phase = 1, order = Long.MIN_VALUE, count = 0, digest = "", chain = "")
                    } else if (normalized == null) {
                        check(head.kind == LegacyLedgerCopyKind.ManifestOnly) { "Empty source produced rows" }
                        rows++
                        check(row.order == state.count && row.order <= Int.MAX_VALUE)
                        state = state.copy(order = row.order, count = state.count + 1)
                    } else {
                        rows++
                        check(row.order == state.count && row.order <= Int.MAX_VALUE)
                        val fields = object : NormalizedTimelineRowDigestFields {
                            override val identityPrimary = row.primary
                            override val identitySecondary = row.secondary
                            override val eventOrder = row.order.toInt()
                            override val checksum = row.checksum
                        }
                        if (normalized.rowDigest.startsWith(CHAIN_ROW_DIGEST_PREFIX)) {
                            val chain = incrementalNormalizedRowDigest(state.chain.ifEmpty { normalizedRowDigest(emptyList()) }, listOf(fields)).removePrefix(CHAIN_ROW_DIGEST_PREFIX)
                            state = state.copy(order = row.order, count = state.count + 1, chain = chain)
                        } else {
                            val digest = ResumableLedgerSha256.restore(state.digest)
                            val encoded = listOf(row.primary.toString(), row.secondary.toString(), row.order.toString(), row.checksum)
                                .joinToString("") { "${it.length}:$it;" }.toByteArray(Charsets.UTF_8)
                            digest.update(encoded)
                            state = state.copy(order = row.order, count = state.count + 1, digest = digest.checkpoint())
                        }
                    }
                }
                1 -> {
                    val row = if (state.after == null) dao.tail(scope, 1).singleOrNull()
                        else dao.before(scope, state.order, ledgerKey(state.after!!), 1).singleOrNull()
                    if (row == null) state = state.copy(phase = 2, after = null)
                    else {
                        rows++
                        check(row.revision in 1..revision)
                        state = stage(state.copy(after = ledgerString(row.identity), order = row.position), row.pointer, row.bytes, scope)
                    }
                }
                2 -> {
                    val entry = if (state.after == null) dao.auditEvidenceFirst(scope, ledgerKey(KEY))
                        else dao.auditEvidence(scope, ledgerKey(KEY), ledgerKey(state.after!!))
                    if (entry == null) state = state.copy(phase = 3, after = null)
                    else {
                        rows++
                        state = stage(state.copy(after = ledgerString(entry.identity)), entry.pointer, entry.bytes, scope)
                    }
                }
                3 -> {
                    val entry = if (state.after == null) dao.auditToolFirst(scope)
                        else dao.auditTool(scope, ledgerKey(state.after!!))
                    if (entry == null) {
                        check((dao.toolSweepGeneration(scope) ?: 0) >= 0)
                        state = state.copy(phase = 4)
                    } else {
                        rows++
                        ledgerString(entry.callId)
                        check(entry.unresolved == (entry.owner != null && !entry.returned))
                        entry.owner?.let { checkNotNull(dao.locate(scope, it)) { "Tool owner missing" } }
                        state = state.copy(after = ledgerString(entry.callId))
                    }
                }
                else -> error("Invalid audit phase")
            }
            currentCoroutineContext().ensureActive()
            save(scope, state)
            // Conservative aggregate bounds include point reads and progress/conversion evidence.
            Progress(state.phase == 4, rows + 24, bytesRead + MAX_STATE + 4096, revision.takeIf { state.phase == 4 })
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
