package com.letta.mobile.data.timeline

/**
 * letta-mobile-827s9.4: why a snapshot persist was scheduled.
 *
 * Replaces an `immediate: Boolean` that gated only stream frames while 12 of 13 call sites
 * passed `immediate = true`, so hydration, reconcile, cursor repair and local-mutation
 * callbacks all bypassed streaming deferral. Typed so the gate is decided by the ORIGIN of the
 * request rather than by how urgent its caller happened to feel, and so telemetry can name the
 * source when a write appears where one was not expected.
 */
enum class SnapshotPersistReason {
    /** An applied stream delta. The highest-volume source by far. */
    STREAM_FRAME,

    /** Server history hydration committed. */
    HYDRATION,

    /** Recent-messages reconcile applied a snapshot. */
    RECONCILE,

    /** A local mutation, cursor repair, cleanup or maintenance repair changed the timeline. */
    LOCAL_MUTATION,

    /** A turn settled. A genuine turn boundary. */
    SETTLEMENT,

    /** The turn ended. A genuine turn boundary. */
    TURN_END,

    /** The per-turn safety deadline elapsed with deferred work outstanding. */
    SAFETY_FLUSH,
    ;

    /**
     * Whether this reason may write DURING an active turn. Only real boundaries and the
     * explicit safety deadline qualify; everything else coalesces behind them.
     */
    val isTurnBoundary: Boolean
        get() = this == SETTLEMENT || this == TURN_END || this == SAFETY_FLUSH

    /** Only the highest-volume source is debounced; boundaries write promptly. */
    val isDebounced: Boolean
        get() = this == STREAM_FRAME
}
