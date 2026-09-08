package com.letta.mobile.data.local

import com.letta.mobile.data.timeline.TimelineBoundedStore
import com.letta.mobile.data.timeline.snapshot.TimelineScope

/** Explicit captured-lease entrypoints. Does not acquire, migrate or activate during construction. */
class TimelineOwnedStorageFactory(
    private val legacy: LettaDatabase,
    private val ledger: TimelineLedgerDatabase,
    private val authority: TimelineOwnershipAuthority,
) {
    /** After closing admission and draining the captured raw-config source owner, call this with
     * the exact canonical graph scope. Retry the same pair after interruption (forward recovery).
     * A missing normalized head is an empty canonical conversation (fresh or manifest-only).
     * Unsupported schema remains an error.
     */
    suspend fun beginMappedMigrationAfterDrain(
        source: TimelineOwnershipAuthority.Lease,
        target: TimelineScope,
    ): TimelineOwnershipAuthority.Lease = authority.beginMappedMigration(source, target) {
        RoomLegacyLedgerCopySource(legacy).snapshot(source.scope) { check(head().supported) }
        check(ledger.ledger().head(ledgerScopeKey(target)) == null) { "Canonical target occupied" }
        check(ledger.ledger().migration(ledgerScopeKey(target)) == null) { "Unmapped copy occupies target" }
    }

    /** Recover a crash between source intent and target publication using only durable identity. */
    suspend fun recoverMappedIntentAfterDrain(source: TimelineScope, target: TimelineScope): TimelineOwnershipAuthority.Lease {
        val state = authority.state(source)
        val mapping = checkNotNull(state.mapping)
        check(state.scope == source && mapping.source == source && mapping.target == target)
        check(state.phase == TimelineOwnershipAuthority.Phase.Migrating)
        return beginMappedMigrationAfterDrain(
            TimelineOwnershipAuthority.Lease(source, mapping.sourceEpoch - 1, TimelineOwnershipAuthority.Route.Legacy), target,
        )
    }

    /** Process restart after target publication. Prepared resumes at switch, Migrating at bounded
     * steps; Canonical uses reopenCanonical. Before target publication use recoverMappedIntentAfterDrain.
     */
    suspend fun resumeMappedMigration(source: TimelineScope, target: TimelineScope): TimelineOwnershipAuthority.Lease {
        val state = authority.state(target)
        check(state.scope == target && state.phase in listOf(
            TimelineOwnershipAuthority.Phase.Migrating, TimelineOwnershipAuthority.Phase.Prepared,
        ))
        val lease = TimelineOwnershipAuthority.Lease(target, state.epoch, TimelineOwnershipAuthority.Route.Migration)
        val mapping = checkNotNull(authority.capturedMapping(lease))
        check(mapping.source == source && mapping.target == target)
        return lease
    }

    /** Reopen does not copy, convert, validate history, or infer the source namespace. */
    suspend fun reopenCanonical(target: TimelineScope): TimelineOwnershipAuthority.Lease {
        val lease = authority.acquire(target, TimelineOwnershipAuthority.Route.Canonical)
        authority.withLease(lease) { }
        return lease
    }

    fun legacy(lease: TimelineOwnershipAuthority.Lease): RoomConfirmedTimelineStore {
        require(lease.route == TimelineOwnershipAuthority.Route.Legacy)
        return RoomConfirmedTimelineStore(legacy, ownership = authority, ownerLease = lease)
    }

    fun canonical(lease: TimelineOwnershipAuthority.Lease): TimelineBoundedStore =
        OwnershipFencedTimelineStore(RoomTimelineBoundedStore(ledger), authority, lease)

    suspend fun copyStep(lease: TimelineOwnershipAuthority.Lease): LegacyLedgerCopyResult {
        require(lease.route == TimelineOwnershipAuthority.Route.Migration)
        val mapping = authority.capturedMapping(lease)
        return authority.withLease(lease) {
            RoomLegacyLedgerCopy(RoomLegacyLedgerCopySource(legacy, mapping), ledger).step(lease.scope)
        }
    }

    suspend fun convertStep(lease: TimelineOwnershipAuthority.Lease): RoomCanonicalMigrationResult {
        require(lease.route == TimelineOwnershipAuthority.Route.Migration)
        val mapping = authority.capturedMapping(lease)
        return authority.withLease(lease) {
            RoomLegacyCanonicalMigration(RoomLegacyLedgerCopySource(legacy, mapping), ledger).step(lease.scope)
        }
    }

    /**
     * Precondition: runtime has closed admission and drained the legacy owner before obtaining this
     * migration lease, and keeps admission closed until switch/abort completes. Storage validates
     * the durable fence, not runtime queue emptiness. No caller-supplied migration receipt is trusted.
     * Lock order is authority -> legacy snapshot -> target transaction; never call within withLease.
     */
    suspend fun prepareAfterDrain(lease: TimelineOwnershipAuthority.Lease, expectedRevision: Long): TimelineOwnershipAuthority.State {
        val mapping = authority.capturedMapping(lease)
        return authority.prepare(lease) {
            RoomTimelineValidation(legacy, ledger, mapping).receipt(lease, expectedRevision)
        }
    }

    /** Revision is supplied only after completed validation under the captured migration lease.
     * It is an input to prepareAfterDrain, never an authorization to bypass its certificate checks.
     */
    data class ValidationProgress(
        val complete: Boolean,
        val metadataRows: Int,
        val bodyBytes: Int,
        val certifiedRevision: Long? = null,
    )

    suspend fun validationStep(lease: TimelineOwnershipAuthority.Lease): ValidationProgress {
        require(lease.route == TimelineOwnershipAuthority.Route.Migration)
        val mapping = authority.capturedMapping(lease)
        return authority.withLease(lease) {
            val progress = RoomTimelineValidation(legacy, ledger, mapping).step(lease)
            ValidationProgress(progress.complete, progress.metadataRows, progress.bodyBytes, progress.certifiedRevision)
        }
    }

    /** Checks the completed audit certificate and durable identity using point reads only. */
    suspend fun switchPreparedAfterDrain(lease: TimelineOwnershipAuthority.Lease): TimelineOwnershipAuthority.Lease {
        val mapping = authority.capturedMapping(lease)
        return authority.commitSwitch(lease) { prepared ->
            val actual = RoomTimelineValidation(legacy, ledger, mapping).receipt(lease, prepared.targetRevision)
            check(actual == prepared) { "Prepared source/generation/revision changed" }
        }
    }

    suspend fun validateDormant(lease: TimelineOwnershipAuthority.Lease, revision: Long): RoomCanonicalMigrationResult {
        require(lease.route == TimelineOwnershipAuthority.Route.Migration)
        val mapping = authority.capturedMapping(lease)
        return authority.withLease(lease) {
            RoomLegacyCanonicalMigration(RoomLegacyLedgerCopySource(legacy, mapping), ledger).validateForActivation(lease.scope, revision)
        }
    }
}

/** Existing coordinator DI cannot bypass ownership while runtime lease integration is incomplete. */
object DormantCanonicalTimelineStore : TimelineBoundedStore {
    override suspend fun <T> read(scope: TimelineScope, block: suspend com.letta.mobile.data.timeline.TimelineStoreReader.() -> T): T =
        error("Canonical storage requires an explicit ownership lease")
    override suspend fun <T> transaction(scope: TimelineScope, block: suspend com.letta.mobile.data.timeline.TimelineStoreTransaction.() -> T): T =
        error("Canonical storage requires an explicit ownership lease")
}
