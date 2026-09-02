package com.letta.mobile.data.timeline

/**
 * Why a persistence write fell back to full-scan planning.
 *
 * letta-mobile-94bt8.1: the clean-main capture showed all nine durable writes taking the full
 * O(N) path and NONE reporting why. `FullScan(reason)` already carried a string, but nothing
 * ever emitted it, so `delta_empty`, an undeclared reducer delta, a cursor advance, a checkpoint
 * and a genuine ambiguity were indistinguishable in a log. This makes the reason typed and
 * mandatory to report.
 *
 * Bounded and non-identifying by construction: a fixed enum can never leak event payloads or
 * server ids into telemetry.
 */
enum class SnapshotPlanningFallback {
    /** No durable baseline yet, so there is nothing to diff against. */
    BASELINE_MISSING,

    /** The reducer declared no persisted change at all. */
    DELTA_EMPTY,

    /**
     * The timeline CHANGED but the reducer declared no delta.
     *
     * This is the defect behind 94bt8.1: `TimelineMutationDelta.None` is the default on
     * [TimelineReduction], so every reducer that never set it -- local append, delivery-state
     * transitions, post-send reconcile, snapshot enrichment, tool-return repair, dangling
     * settlement, cleanup -- silently produced an empty delta. The write then fell back with
     * `delta_empty`, which read as "nothing to do" rather than "nobody told persistence what
     * changed". Fails loud now instead of masquerading as the empty case.
     */
    UNDECLARED_DELTA,

    /** Hydration replaces the timeline wholesale. */
    HYDRATE,

    /** Broad reconciliation; row identity is not derivable cheaply. */
    RECONCILE,

    /** Recent-messages merge; same reasoning as [RECONCILE]. */
    RECENT_MESSAGES,

    /** Cursor or released-count metadata moved, so row order may not be stable. */
    STREAM_METADATA_CHANGED,

    /** Duplicate or missing server ids: identity is not a usable key for this timeline. */
    AMBIGUOUS_SERVER_IDENTITY,

    /** More rows changed in one reduction than the exact path is willing to carry. */
    STREAM_DELTA_TOO_WIDE,

    /** More rows accumulated across pending reductions than the exact path will carry. */
    PENDING_DELTA_TOO_WIDE,

    /** Deletes need persisted order ranks, which do not exist yet. */
    DELETE_REQUIRES_RANKED_ORDER,

    /** The current timeline has duplicate confirmed identities. */
    AMBIGUOUS_CURRENT_IDENTITY,

    /** A dirty identity is no longer present in the committed timeline. */
    CHANGED_IDENTITY_MISSING,

    /** A changed row has no usable row key. */
    AMBIGUOUS_CHANGED_IDENTITY,

    /** A periodic legacy checkpoint is due; the full envelope is required by design. */
    CHECKPOINT_DUE,
}
