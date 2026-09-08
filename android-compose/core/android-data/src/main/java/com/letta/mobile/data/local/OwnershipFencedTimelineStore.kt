package com.letta.mobile.data.local

import com.letta.mobile.data.timeline.TimelineBoundedStore
import com.letta.mobile.data.timeline.TimelineStoreReader
import com.letta.mobile.data.timeline.TimelineStoreTransaction
import com.letta.mobile.data.timeline.snapshot.TimelineScope

/**
 * Lease-bound adapter for canonical engines and pending-local stores. Capture the lease at admission,
 * never reacquire it on every write: an old callback must not silently inherit a newer epoch.
 * Not DI-bound until legacy admission/drain and every legacy mutation use the same authority.
 * Migration that nests legacy and target Room transactions must hold one outer withLease instead;
 * passing this adapter into that callback would reenter the non-reentrant OS lock.
 */
class OwnershipFencedTimelineStore(
    private val delegate: TimelineBoundedStore,
    private val authority: TimelineOwnershipAuthority,
    private val lease: TimelineOwnershipAuthority.Lease,
) : TimelineBoundedStore {
    init { require(lease.route == TimelineOwnershipAuthority.Route.Canonical) }

    override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T {
        check(scope == lease.scope)
        return authority.withLease(lease) { delegate.read(scope, block) }
    }

    override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T {
        check(scope == lease.scope)
        return authority.withLease(lease) { delegate.transaction(scope, block) }
    }
}
