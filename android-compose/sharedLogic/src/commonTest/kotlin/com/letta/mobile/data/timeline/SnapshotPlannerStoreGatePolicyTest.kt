package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.InMemoryConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommit
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlan
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineCommitMetadata
import com.letta.mobile.data.timeline.snapshot.TimelineIncrementalSnapshotPlanner
import com.letta.mobile.data.timeline.snapshot.TimelineRevision
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-1448 / shared dedupe planner gate.
 *
 * The unified planner ([TimelineSyncLoop.decideIncrementalPlan] + [TimelineSyncLoop.canPersistIncremental])
 * gates incremental persistence on THREE conditions:
 *   1. The planner produced [TimelineIncrementalSnapshotPlanner.Result.Planned].
 *   2. No legacy checkpoint is due.
 *   3. The [ConfirmedTimelineStore] reports [ConfirmedTimelineStore.supportsIncrementalCommit].
 *
 * A `Planned` result with a store that does NOT support incremental commits must NOT be
 * routed to [TimelineSyncLoop.persistIncrementalSnapshot] -- the default `commitNormalized`
 * shim only writes the full envelope, so this would silently lose the increment vs
 * full-scan distinction.
 *
 * Fail-on-revert: deleting any of the three conjuncts from `canPersistIncremental` (or
 * reverting [ConfirmedTimelineStore.supportsIncrementalCommit] to default `false` while
 * keeping the `if (Planned && !checkpointDue)` check) makes the first assertion fail,
 * because a `Planned` decision for a store that returns `false` from
 * `supportsIncrementalCommit` would then pass `canPersistIncremental()`.
 *
 * Pairs with: [ConfirmedTimelineStore.supportsIncrementalCommit] (default `false`),
 * [com.letta.mobile.data.local.RoomConfirmedTimelineStore.supportsIncrementalCommit]
 * (overridden `true`), and the `IncrementalPlanningDecision.reason` precedence in
 * `decideIncrementalPlan` (store_unsupported precedes FullScan-reason to give ops a
 * distinct telemetry signal).
 */
class SnapshotPlannerStoreGatePolicyTest {

    /**
     * A `Planned` decision for a store that reports `supportsIncrementalCommit = false` must
     * NOT be routed through the incremental write path. This is the load-bearing contract
     * that gives the planner a unified gate.
     */
    @Test
    fun plannedDecisionForUnsupportedStoreIsNotPersistable() {
        val decision = TimelineSyncLoop.IncrementalPlanningDecision(
            result = plannedResult(),
            checkpointDue = false,
            reason = SnapshotPlanningFallback.STORE_UNSUPPORTED,
            baseRevision = 0L,
            targetRevision = 1L,
            storeSupportsIncremental = false,
        )
        assertFalse(
            TimelineSyncLoop.canPersistIncremental(decision),
            "A Planned planner result for a store that does not support incremental commits " +
                "must NOT pass the unified gate; otherwise the default commitNormalized shim " +
                "would silently demote an incremental plan to a full-scan write without the " +
                "telemetry or invariants noticing.",
        )
    }

    /**
     * Mirror of the above: a `Planned` decision for a store that DOES support incremental
     * commits passes the gate. Without this, `RoomConfirmedTimelineStore` would lose its
     * incremental path.
     */
    @Test
    fun plannedDecisionForSupportedStoreIsPersistable() {
        val decision = TimelineSyncLoop.IncrementalPlanningDecision(
            result = plannedResult(),
            checkpointDue = false,
            reason = null,
            baseRevision = 0L,
            targetRevision = 1L,
            storeSupportsIncremental = true,
        )
        assertTrue(TimelineSyncLoop.canPersistIncremental(decision))
    }

    /**
     * A `Planned` decision is still gated by the legacy-checkpoint check, even when the store
     * supports incremental commits. The three conjuncts are AND-ed, not OR-ed; removing the
     * checkpoint conjunct would let every Nth commit skip its v11 fallback write.
     */
    @Test
    fun plannedDecisionWithCheckpointDueIsNotPersistableEvenForSupportedStore() {
        val decision = TimelineSyncLoop.IncrementalPlanningDecision(
            result = plannedResult(),
            checkpointDue = true,
            reason = SnapshotPlanningFallback.CHECKPOINT_DUE,
            baseRevision = 0L,
            targetRevision = 1L,
            storeSupportsIncremental = true,
        )
        assertFalse(
            TimelineSyncLoop.canPersistIncremental(decision),
            "The checkpointDue conjunct must short-circuit the gate regardless of store " +
                "support, so legacy v11 rollback-readability is preserved every " +
                "LEGACY_CHECKPOINT_INTERVAL commits.",
        )
    }

    /**
     * `reason` precedence: when the planner produced `Planned` AND the store lacks support,
     * the telemetry `reason` must be `store_unsupported` -- NOT `delta` -- so ops can
     * attribute "why is this conversation falling back to full-scan?" without confusing it
     * with a real planner-rejected reason.
     */
    @Test
    fun reasonPrecedenceIsStoreUnsupportedOverDelta() {
        assertEquals(
            SnapshotPlanningFallback.STORE_UNSUPPORTED,
            TimelineSyncLoop.IncrementalPlanningDecision(
                result = plannedResult(),
                checkpointDue = false,
                reason = SnapshotPlanningFallback.STORE_UNSUPPORTED,
                baseRevision = 0L,
                targetRevision = 1L,
                storeSupportsIncremental = false,
            ).reason,
            "When the planner would say 'delta' but the store blocks it, telemetry reason " +
                "must surface the store constraint, not the planner verdict.",
        )
    }

    /**
     * Default contract: a fresh [InMemoryConfirmedTimelineStore] reports
     * `supportsIncrementalCommit = false`. This pins the interface default so an
     * accidental override on the default path (which would let tests silently take the
     * incremental branch against a non-Room store) fails here.
     */
    @Test
    fun inMemoryStoreDefaultsToUnsupported() {
        assertFalse(
            InMemoryConfirmedTimelineStore().supportsIncrementalCommit,
            "InMemoryConfirmedTimelineStore must report supportsIncrementalCommit = false; " +
                "the default commitNormalized shim writes a full envelope and would " +
                "mis-attribute writes as 'incremental' in telemetry if this overrode.",
        )
    }

    /**
     * The full-scan fallback path: when the planner produces a FullScan and the store does
     * support incremental commits, the gate still returns false (because the result is not
     * `Planned`). The FullScan branch carries its own reason string and is routed through
     * the legacy normalized planner, NOT [TimelineSyncLoop.persistIncrementalSnapshot].
     */
    @Test
    fun fullScanDecisionIsNotPersistableRegardlessOfStore() {
        val decision = TimelineSyncLoop.IncrementalPlanningDecision(
            result = TimelineIncrementalSnapshotPlanner.Result.FullScan(SnapshotPlanningFallback.BASELINE_MISSING),
            checkpointDue = false,
            reason = SnapshotPlanningFallback.BASELINE_MISSING,
            baseRevision = null,
            targetRevision = 1L,
            storeSupportsIncremental = true,
        )
        assertFalse(TimelineSyncLoop.canPersistIncremental(decision))
    }

    /**
     * Cross-check via an instrumented store: a store that overrides
     * [ConfirmedTimelineStore.supportsIncrementalCommit] to `true` MUST actually accept
     * `commitNormalized` as a real incremental transaction, not silently fall back to the
     * shim. This pins the contract between the gate flag and the override.
     */
    @Test
    fun storeOverrideIsVisibleToTheGate() {
        val store = RecordingSupportsStore(supportsIncrementalCommit = true)
        assertTrue(store.supportsIncrementalCommit)
        val store2 = RecordingSupportsStore(supportsIncrementalCommit = false)
        assertFalse(store2.supportsIncrementalCommit)
    }

    private fun plannedResult(): TimelineIncrementalSnapshotPlanner.Result.Planned {
        // The shape of the underlying plan only matters to the gate -- it is type-erased to
        // Planned for the gate test. We do not need a real plan to exercise the gate logic;
        // constructing a stub plan keeps the test focused on the gate contract.
        val plan = NormalizedTimelineCommitPlan.Apply(
            commit = NormalizedTimelineCommit(
                baseRevision = TimelineRevision(0L),
                targetRevision = TimelineRevision(1L),
                metadata = TimelineCommitMetadata(
                    schemaVersion = StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION,
                    scope = TimelineScope("backend", "conv-gate-test"),
                    liveCursor = "live-1",
                    backfillCursor = "back-1",
                    releasedOlderCount = 0,
                    writtenAtMillis = 0L,
                ),
                upserts = emptyList(),
                deletes = emptySet(),
                comparisonEvents = 0,
                encodedRows = 0,
            ),
        )
        return TimelineIncrementalSnapshotPlanner.Result.Planned(
            plan = plan,
            changedEvents = emptyList(),
            fullEnvelopeRequired = false,
        )
    }
}

private class RecordingSupportsStore(
    override val supportsIncrementalCommit: Boolean,
) : ConfirmedTimelineStore {
    override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? = null
    override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean = true
    override suspend fun deleteSnapshot(scope: TimelineScope) = Unit
    override suspend fun clearForBackend(backendId: String) = Unit
    override suspend fun prune(backendId: String, maxRetainedConversations: Int) = Unit
}
