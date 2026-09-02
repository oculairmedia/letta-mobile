package com.letta.mobile.data.timeline.snapshot

import com.letta.mobile.data.timeline.PendingTimelinePersistenceDelta
import com.letta.mobile.data.timeline.SnapshotPlanningFallback
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent

/** Plans ordinary stream commits from the bounded dirty set, without encoding history. */
internal object TimelineIncrementalSnapshotPlanner {
    sealed interface Result {
        data class Planned(
            val plan: NormalizedTimelineCommitPlan,
            val changedEvents: List<StoredTimelineEvent>,
            val fullEnvelopeRequired: Boolean,
        ) : Result

        data class FullScan(val reason: SnapshotPlanningFallback) : Result
    }

    fun plan(
        timeline: Timeline,
        scope: TimelineScope,
        baseRevision: Long,
        targetRevision: Long,
        writtenAtMillis: Long,
        delta: PendingTimelinePersistenceDelta.Snapshot,
    ): Result {
        if (delta.requiresFullRescan) return Result.FullScan(requireNotNull(delta.fallbackReason))
        val confirmedByServerId = timeline.events.mapNotNull { event ->
            (event as? TimelineEvent.Confirmed)?.let { it.serverId to it }
        }.toMap()
        if (confirmedByServerId.size != timeline.events.count { it is TimelineEvent.Confirmed }) {
            return Result.FullScan(SnapshotPlanningFallback.AMBIGUOUS_CURRENT_IDENTITY)
        }

        val changed = delta.changedConfirmedServerIds.map { serverId ->
            confirmedByServerId[serverId] ?: return Result.FullScan(SnapshotPlanningFallback.CHANGED_IDENTITY_MISSING)
        }
        val changedStored = changed.map(TimelineEvent.Confirmed::toStoredTimelineEvent)

        // Until persisted order ranks land, exact planning is safe only for tail appends and
        // in-place replacements. Any delete or non-tail insertion uses the reference planner.
        if (delta.deletedConfirmedServerIds.isNotEmpty()) return Result.FullScan(SnapshotPlanningFallback.DELETE_REQUIRES_RANKED_ORDER)
        val confirmedOrder = timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
        val orderByServerId = confirmedOrder.mapIndexed { index, event -> event.serverId to index }.toMap()
        val rows = changedStored.map { event ->
            val key = NormalizedTimelineCommitPlanner.rowKey(event)
                ?: return Result.FullScan(SnapshotPlanningFallback.AMBIGUOUS_CHANGED_IDENTITY)
            NormalizedTimelineRow(key, requireNotNull(orderByServerId[event.serverId]), event)
        }
        val plan = NormalizedTimelineCommitPlan.Apply(
            NormalizedTimelineCommit(
                baseRevision = TimelineRevision(baseRevision),
                targetRevision = TimelineRevision(targetRevision),
                metadata = TimelineCommitMetadata(
                    schemaVersion = StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION,
                    scope = scope,
                    liveCursor = timeline.liveCursor,
                    backfillCursor = timeline.backfillCursor,
                    releasedOlderCount = timeline.releasedOlderCount,
                    writtenAtMillis = writtenAtMillis,
                ),
                upserts = rows,
                deletes = emptySet(),
                comparisonEvents = changed.size,
                encodedRows = changed.size,
            ),
        )
        return Result.Planned(plan, changedStored, fullEnvelopeRequired = false)
    }
}
