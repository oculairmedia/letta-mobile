package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlan
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryValue
import com.letta.mobile.util.attr
import com.letta.mobile.util.telemetryAttributes

/** Reducer diagnostics use the same typed boundary as other telemetry callers. */
internal typealias TelemetryAttribute = Pair<String, TelemetryValue>

internal fun emitSnapshotPlanningDecision(
    decision: TimelineSyncLoop.IncrementalPlanningDecision,
    delta: PendingTimelinePersistenceDelta.Snapshot,
    identity: Array<Pair<String, String>>,
) {
    val plan = (decision.result as? TimelineIncrementalSnapshotPlanner.Result.Planned)?.plan
    val comparisonEvents = (plan as? NormalizedTimelineCommitPlan.Apply)?.commit?.comparisonEvents ?: 0
    val encodedRows = (plan as? NormalizedTimelineCommitPlan.Apply)?.commit?.encodedRows ?: 0
    val planningMode = when {
        decision.result is TimelineIncrementalSnapshotPlanner.Result.NoWork -> "no_work"
        TimelineSyncLoop.canPersistIncremental(decision) -> "delta"
        else -> "full_scan"
    }
    val reasonStr = when {
        decision.reason != null -> decision.reason.name
        decision.result is TimelineIncrementalSnapshotPlanner.Result.NoWork -> "no_work"
        else -> "delta"
    }
    Telemetry.event(
        "TimelineSync", "snapshotPersist.planningDecision",
        attrs = identity.telemetryAttributes() + arrayOf(
            "planningMode" attr planningMode,
            "reason" attr reasonStr,
            "throughSequence" attr delta.throughSequence,
            "dirtyChanged" attr delta.changedConfirmedServerIds.size,
            "dirtyDeleted" attr delta.deletedConfirmedServerIds.size,
            "metadataChanged" attr delta.metadataChanged,
            "baselineRevision" attr (decision.baseRevision ?: 0L),
            "targetRevision" attr decision.targetRevision,
            "comparisonEvents" attr comparisonEvents,
            "encodedRows" attr encodedRows,
            "storeSupportsIncremental" attr decision.storeSupportsIncremental,
        ),
    )
}

internal fun emitFullScanPlanned(
    identity: Array<Pair<String, String>>,
    reason: String,
    delta: PendingTimelinePersistenceDelta.Snapshot,
) {
    Telemetry.event(
        "TimelineSync", "snapshotPersist.fullScanPlanned",
        attrs = identity.telemetryAttributes() + arrayOf(
            "reason" attr reason,
            "dirtyIdentities" attr delta.dirtyIdentityCount,
            "metadataChanged" attr delta.metadataChanged,
        ),
    )
}
