package com.letta.mobile.data.local

import com.letta.mobile.data.timeline.TimelineBoundedStore
import com.letta.mobile.data.timeline.snapshot.TimelineScope

/** Explicit captured-lease entrypoints. Does not acquire, migrate or activate during construction. */
class TimelineOwnedStorageFactory(
    private val legacy: LettaDatabase,
    private val ledger: TimelineLedgerDatabase,
    private val authority: TimelineOwnershipAuthority,
) {
    fun legacy(lease: TimelineOwnershipAuthority.Lease): RoomConfirmedTimelineStore {
        require(lease.route == TimelineOwnershipAuthority.Route.Legacy)
        return RoomConfirmedTimelineStore(legacy, ownership = authority, ownerLease = lease)
    }

    fun canonical(lease: TimelineOwnershipAuthority.Lease): TimelineBoundedStore =
        OwnershipFencedTimelineStore(RoomTimelineBoundedStore(ledger), authority, lease)

    suspend fun copyStep(lease: TimelineOwnershipAuthority.Lease): LegacyLedgerCopyResult {
        require(lease.route == TimelineOwnershipAuthority.Route.Migration)
        return authority.withLease(lease) {
            RoomLegacyLedgerCopy(RoomLegacyLedgerCopySource(legacy), ledger).step(lease.scope)
        }
    }

    suspend fun convertStep(lease: TimelineOwnershipAuthority.Lease): RoomCanonicalMigrationResult {
        require(lease.route == TimelineOwnershipAuthority.Route.Migration)
        return authority.withLease(lease) {
            RoomLegacyCanonicalMigration(RoomLegacyLedgerCopySource(legacy), ledger).step(lease.scope)
        }
    }

    suspend fun validateDormant(lease: TimelineOwnershipAuthority.Lease, revision: Long): RoomCanonicalMigrationResult {
        require(lease.route == TimelineOwnershipAuthority.Route.Migration)
        return authority.withLease(lease) {
            RoomLegacyCanonicalMigration(RoomLegacyLedgerCopySource(legacy), ledger).validateForActivation(lease.scope, revision)
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
