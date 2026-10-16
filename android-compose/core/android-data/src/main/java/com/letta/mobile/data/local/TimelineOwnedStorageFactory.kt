package com.letta.mobile.data.local

import com.letta.mobile.data.timeline.TimelineBoundedStore

/**
 * Lease-bound store onto the canonical ledger (letta-mobile-by4vm.15: the only Android timeline).
 * Leases come from [TimelineOwnershipAuthority.claimCanonical]; nothing is acquired during construction.
 */
class TimelineOwnedStorageFactory(
    private val ledger: TimelineLedgerDatabase,
    private val authority: TimelineOwnershipAuthority,
) {
    fun canonical(lease: TimelineOwnershipAuthority.Lease): TimelineBoundedStore =
        OwnershipFencedTimelineStore(RoomTimelineBoundedStore(ledger), authority, lease)
}
