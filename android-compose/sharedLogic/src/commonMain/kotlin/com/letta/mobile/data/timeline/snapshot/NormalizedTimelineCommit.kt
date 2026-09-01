package com.letta.mobile.data.timeline.snapshot

import kotlin.jvm.JvmInline
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class TimelineRevision(val value: Long)

@Serializable
data class TimelineEventRowKey(
    val identityPrimary: Long,
    val identitySecondary: Long,
)

@Serializable
data class NormalizedTimelineRow(
    val key: TimelineEventRowKey,
    val order: Int,
    val event: StoredTimelineEvent,
)

@Serializable
data class TimelineCommitMetadata(
    val schemaVersion: Int,
    val scope: TimelineScope,
    val liveCursor: String?,
    val backfillCursor: String?,
    val releasedOlderCount: Int,
    val writtenAtMillis: Long,
)

@Serializable
data class NormalizedTimelineCommit(
    val baseRevision: TimelineRevision,
    val targetRevision: TimelineRevision,
    val metadata: TimelineCommitMetadata,
    val upserts: List<NormalizedTimelineRow>,
    val deletes: Set<TimelineEventRowKey>,
    val comparisonEvents: Int,
    val encodedRows: Int = upserts.size,
    val fullEnvelopeEncodes: Int = 0,
)

sealed interface NormalizedTimelineCommitPlan {
    data class Apply(val commit: NormalizedTimelineCommit) : NormalizedTimelineCommitPlan
    data class NoOp(
        val scope: TimelineScope,
        val baseRevision: TimelineRevision,
        val targetRevision: TimelineRevision,
        val writtenAtMillis: Long,
    ) : NormalizedTimelineCommitPlan
    data class Invalid(val reason: NormalizedTimelineCommitFailure) : NormalizedTimelineCommitPlan
}

enum class NormalizedTimelineCommitFailure {
    INVALID_REVISION,
    SCOPE_MISMATCH,
    AMBIGUOUS_EVENT_IDENTITY,
}

sealed interface NormalizedTimelineWriteResult {
    data class Committed(val revision: TimelineRevision) : NormalizedTimelineWriteResult
    data class Stale(val highWaterRevision: TimelineRevision) : NormalizedTimelineWriteResult
    data class NoOp(val revision: TimelineRevision) : NormalizedTimelineWriteResult
    data class Invalid(val reason: NormalizedTimelineCommitFailure) : NormalizedTimelineWriteResult
}

/** Stable storage values. Unknown future values fail closed instead of ordinal-decoding. */
fun NormalizedTimelineCommitFailure.toStorageValue(): String = when (this) {
    NormalizedTimelineCommitFailure.INVALID_REVISION -> "invalid_revision"
    NormalizedTimelineCommitFailure.SCOPE_MISMATCH -> "scope_mismatch"
    NormalizedTimelineCommitFailure.AMBIGUOUS_EVENT_IDENTITY -> "ambiguous_event_identity"
}

fun normalizedTimelineCommitFailureFromStorage(value: String): NormalizedTimelineCommitFailure? = when (value) {
    "invalid_revision" -> NormalizedTimelineCommitFailure.INVALID_REVISION
    "scope_mismatch" -> NormalizedTimelineCommitFailure.SCOPE_MISMATCH
    "ambiguous_event_identity" -> NormalizedTimelineCommitFailure.AMBIGUOUS_EVENT_IDENTITY
    else -> null
}

object NormalizedTimelineCommitPlanner {
    fun plan(
        previous: StoredTimelineEnvelope?,
        current: StoredTimelineEnvelope,
    ): NormalizedTimelineCommitPlan {
        val baseRevision = previous?.revision ?: 0L
        if (current.revision <= baseRevision) {
            return NormalizedTimelineCommitPlan.Invalid(NormalizedTimelineCommitFailure.INVALID_REVISION)
        }
        if (previous != null && previous.scope != current.scope) {
            return NormalizedTimelineCommitPlan.Invalid(NormalizedTimelineCommitFailure.SCOPE_MISMATCH)
        }
        val previousRows = index(previous?.events.orEmpty())
            ?: return NormalizedTimelineCommitPlan.Invalid(NormalizedTimelineCommitFailure.AMBIGUOUS_EVENT_IDENTITY)
        val currentRows = index(current.events)
            ?: return NormalizedTimelineCommitPlan.Invalid(NormalizedTimelineCommitFailure.AMBIGUOUS_EVENT_IDENTITY)
        val metadataChanged = previous == null || persistedMetadata(previous) != persistedMetadata(current)
        val upserts = currentRows.values.filter { row -> previousRows[row.key] != row }
        val deletes = previousRows.keys - currentRows.keys
        if (!metadataChanged && upserts.isEmpty() && deletes.isEmpty()) {
            return NormalizedTimelineCommitPlan.NoOp(
                scope = previous.scope,
                baseRevision = TimelineRevision(previous.revision),
                targetRevision = TimelineRevision(current.revision),
                writtenAtMillis = current.writtenAtMillis,
            )
        }
        return NormalizedTimelineCommitPlan.Apply(
            NormalizedTimelineCommit(
                baseRevision = TimelineRevision(previous?.revision ?: 0L),
                targetRevision = TimelineRevision(current.revision),
                metadata = metadata(current),
                upserts = upserts,
                deletes = deletes,
                comparisonEvents = previousRows.size + currentRows.size,
            ),
        )
    }

    private fun index(events: List<StoredTimelineEvent>): Map<TimelineEventRowKey, NormalizedTimelineRow>? {
        val rows = LinkedHashMap<TimelineEventRowKey, NormalizedTimelineRow>()
        val summary = TimelineSnapshotMutationCharacterizer.summarize(
            StoredTimelineEnvelope(scope = INTERNAL_SCOPE, revision = 1L, events = events),
        )
        events.forEachIndexed { order, event ->
            val eventSummary = summary.events[order]
            val key = TimelineEventRowKey(eventSummary.identityPrimary, eventSummary.identitySecondary)
            if (key.identityPrimary == 0L && key.identitySecondary == 0L) return null
            if (rows.put(key, NormalizedTimelineRow(key, order, event)) != null) return null
        }
        return rows
    }

    private fun metadata(envelope: StoredTimelineEnvelope) = TimelineCommitMetadata(
        schemaVersion = envelope.schemaVersion,
        scope = envelope.scope,
        liveCursor = envelope.liveCursor,
        backfillCursor = envelope.backfillCursor,
        releasedOlderCount = envelope.releasedOlderCount,
        writtenAtMillis = envelope.writtenAtMillis,
    )

    private fun persistedMetadata(envelope: StoredTimelineEnvelope) = metadata(envelope).copy(writtenAtMillis = 0L)

    private val INTERNAL_SCOPE = TimelineScope("normalized-planner", "normalized-planner")
}

class InMemoryNormalizedTimelineStore {
    private data class State(
        val revision: TimelineRevision,
        val metadata: TimelineCommitMetadata,
        val rows: Map<TimelineEventRowKey, NormalizedTimelineRow>,
    )

    private val lock = SynchronizedObject()
    private val states = LinkedHashMap<String, State>()

    fun apply(plan: NormalizedTimelineCommitPlan): NormalizedTimelineWriteResult = when (plan) {
        is NormalizedTimelineCommitPlan.Invalid -> NormalizedTimelineWriteResult.Invalid(plan.reason)
        is NormalizedTimelineCommitPlan.NoOp -> synchronized(lock) {
            val key = plan.scope.storageKey
            val existing = states[key]
            val highWater = existing?.revision ?: TimelineRevision(0L)
            if (existing == null || highWater != plan.baseRevision) {
                NormalizedTimelineWriteResult.Stale(highWater)
            } else {
                states[key] = existing.copy(
                    revision = plan.targetRevision,
                    metadata = existing.metadata.copy(writtenAtMillis = plan.writtenAtMillis),
                )
                NormalizedTimelineWriteResult.NoOp(plan.targetRevision)
            }
        }
        is NormalizedTimelineCommitPlan.Apply -> synchronized(lock) {
            val commit = plan.commit
            val key = commit.metadata.scope.storageKey
            val existing = states[key]
            val highWater = existing?.revision ?: TimelineRevision(0L)
            if (highWater != commit.baseRevision) {
                NormalizedTimelineWriteResult.Stale(highWater)
            } else {
                val nextRows = LinkedHashMap(existing?.rows.orEmpty())
                commit.deletes.forEach(nextRows::remove)
                commit.upserts.forEach { nextRows[it.key] = it }
                states[key] = State(commit.targetRevision, commit.metadata, nextRows)
                NormalizedTimelineWriteResult.Committed(commit.targetRevision)
            }
        }
    }

    fun read(scope: TimelineScope): StoredTimelineEnvelope? = synchronized(lock) {
        states[scope.storageKey]?.let { state ->
            StoredTimelineEnvelope(
                schemaVersion = state.metadata.schemaVersion,
                scope = state.metadata.scope,
                revision = state.revision.value,
                liveCursor = state.metadata.liveCursor,
                backfillCursor = state.metadata.backfillCursor,
                releasedOlderCount = state.metadata.releasedOlderCount,
                events = state.rows.values.sortedBy { it.order }.map { it.event },
                writtenAtMillis = state.metadata.writtenAtMillis,
            )
        }
    }
}
