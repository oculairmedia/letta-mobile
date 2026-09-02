package com.letta.mobile.data.timeline.snapshot

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

enum class SnapshotReadFailure {
    MISSING,
    STORAGE_FAILURE,
    METADATA_INVALID,
    MANIFEST_MISSING,
    CHUNK_MISSING,
    CHUNK_INVALID,
    LENGTH_MISMATCH,
    CHECKSUM_MISMATCH,
    SCOPE_MISMATCH,
    REVISION_MISMATCH,
    SCHEMA_MISMATCH,
    CORRUPT_ENCODING,
}

sealed interface ConfirmedTimelineReadResult {
    val snapshot: StoredTimelineEnvelope?
    val highWaterRevision: Long

    data class Active(
        override val snapshot: StoredTimelineEnvelope,
        override val highWaterRevision: Long = snapshot.revision,
    ) : ConfirmedTimelineReadResult

    data class Fallback(
        override val snapshot: StoredTimelineEnvelope,
        val activeFailure: SnapshotReadFailure,
        override val highWaterRevision: Long,
    ) : ConfirmedTimelineReadResult

    data class ReconciliationRequired(
        val failure: SnapshotReadFailure,
        override val highWaterRevision: Long = 0L,
    ) : ConfirmedTimelineReadResult {
        override val snapshot: StoredTimelineEnvelope? = null
    }
}

/**
 * Common contract for persisting and reading confirmed timeline snapshots.
 *
 * All operations are scoped by [TimelineScope] to prevent cross-backend or cross-agent
 * data leakage. Implementations must guarantee atomic revision-safe writes (rejecting
 * stale writes whose revision <= existing stored revision).
 */
interface ConfirmedTimelineStore {
    /**
     * Read the persisted snapshot for [scope], or null if none exists / if corrupt.
     *
     * Android's durable implementation also exposes [readSnapshotResult] so callers can
     * distinguish an active snapshot from a last-known-good fallback. Existing callers keep
     * the nullable API until reconciliation consumes the typed result in the next milestone.
     */
    suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope?

    suspend fun readSnapshotResult(scope: TimelineScope): ConfirmedTimelineReadResult =
        readSnapshot(scope)?.let(ConfirmedTimelineReadResult::Active)
            ?: ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.MISSING)

    /**
     * Write [envelope] atomically.
     * Returns `true` if written, `false` if rejected due to a stale revision.
     */
    /**
     * The revision NORMALIZED storage durably holds, or null when it is unknown/absent.
     *
     * Needed because a legacy fallback can succeed at revision N while normalized stays at
     * N-1 -- reading the snapshot back cannot tell the two apart, since legacy serves N either
     * way. Only this distinguishes "normalized caught up" from "normalized is stranded".
     */
    suspend fun normalizedHeadRevision(scope: TimelineScope): Long? = null

    /**
     * Whether this store can APPLY a [NormalizedTimelineCommitPlan] to durable rows.
     *
     * The incremental path deliberately hands `commitNormalized` a METADATA-ONLY envelope --
     * not encoding the whole timeline is the entire point. A store whose commitNormalized is
     * the default shim below writes that envelope wholesale, which for an Apply plan means
     * replacing the stored timeline with ZERO events.
     *
     * letta-mobile-94bt8.1: DesktopConfirmedTimelineStore does not override commitNormalized,
     * so it takes that shim. Defaulting to false keeps every such store on the full-envelope
     * path, which is slower but correct; Room, which really does apply plans row-wise,
     * overrides this to true and keeps the proportional win.
     */
    val supportsIncrementalCommit: Boolean get() = false

    suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean

    /**
     * Delete the snapshot for [scope].
     */
    suspend fun deleteSnapshot(scope: TimelineScope)

    /**
     * Clear all snapshots belonging to [backendId] (e.g. on backend switch or logout).
     */
    suspend fun clearForBackend(backendId: String)

    /**
     * Prune older snapshots for [backendId] keeping at most [maxRetainedConversations].
     */
    suspend fun prune(backendId: String, maxRetainedConversations: Int)

    /**
     * Commit an incremental normalized-row [plan] describing the changed rows between the
     * last durably acknowledged envelope and [fullEnvelope] (the fully-materialized target
     * state, already built in memory by the caller at zero extra encode cost).
     *
     * Implementations that persist a normalized row store (Android Room) MUST override this
     * with a real incremental transaction that touches only changed rows and performs no
     * full-envelope [TimelineSnapshotCodec.encode] call for ordinary append/update/delete/
     * cursor-only/no-op mutations — that is the whole point of this slice.
     *
     * The default below is a **compatibility shim only**. It reconstructs the target
     * envelope (already fully available as [fullEnvelope], no extra encode) and delegates to
     * [writeSnapshot]/[readSnapshot], so no-op/in-memory/Desktop/test stores keep working
     * unmodified. It is intentionally NOT the fast path and must never be assumed to satisfy
     * the production zero-full-encode requirement — only [RoomConfirmedTimelineStore]'s
     * override does that.
     */
    suspend fun commitNormalized(
        plan: NormalizedTimelineCommitPlan,
        fullEnvelope: StoredTimelineEnvelope,
        // Advisory checkpoint hint from the caller's legacy-v11 cadence policy (see
        // TimelineSyncLoop.maybeCheckpointLegacyEnvelope). The default shim below already
        // performs a full envelope write for every commit, so it ignores this; only an
        // implementation whose ordinary commit path is otherwise incremental (Room) needs to
        // act on it, by also staging a full v11 write when true, best-effort, without turning
        // a checkpoint failure into a reported persistence failure.
        checkpointLegacyEnvelope: Boolean = false,
    ): NormalizedTimelineWriteResult = when (plan) {
        is NormalizedTimelineCommitPlan.Invalid -> NormalizedTimelineWriteResult.Invalid(plan.reason)
        is NormalizedTimelineCommitPlan.NoOp -> {
            val target = fullEnvelope.copy(revision = plan.targetRevision.value, writtenAtMillis = plan.writtenAtMillis)
            if (writeSnapshot(target)) {
                NormalizedTimelineWriteResult.NoOp(plan.targetRevision)
            } else {
                NormalizedTimelineWriteResult.Stale(TimelineRevision(readSnapshot(plan.scope)?.revision ?: 0L))
            }
        }
        is NormalizedTimelineCommitPlan.Apply -> {
            val commit = plan.commit
            // Fail loud rather than silently truncating. Reaching here with row upserts and an
            // empty envelope means a caller took the incremental path against a store that
            // cannot apply plans -- writing `target` would erase the conversation.
            if (commit.upserts.isNotEmpty() && fullEnvelope.events.isEmpty()) {
                return NormalizedTimelineWriteResult.Invalid(
                    NormalizedTimelineCommitFailure.UNSUPPORTED_PLAN,
                )
            }
            val target = fullEnvelope.copy(revision = commit.targetRevision.value)
            if (writeSnapshot(target)) {
                NormalizedTimelineWriteResult.Committed(commit.targetRevision)
            } else {
                NormalizedTimelineWriteResult.Stale(
                    TimelineRevision(readSnapshot(commit.metadata.scope)?.revision ?: 0L),
                )
            }
        }
    }
}

/**
 * No-op implementation for environments where local persistence is disabled.
 */
object NoOpConfirmedTimelineStore : ConfirmedTimelineStore {
    override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? = null
    override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean = true
    override suspend fun deleteSnapshot(scope: TimelineScope) = Unit
    override suspend fun clearForBackend(backendId: String) = Unit
    override suspend fun prune(backendId: String, maxRetainedConversations: Int) = Unit
}

/**
 * Thread-safe in-memory store for unit tests and deterministic scenarios.
 */
class InMemoryConfirmedTimelineStore : ConfirmedTimelineStore {
    private val lock = SynchronizedObject()
    private val store = LinkedHashMap<String, StoredTimelineEnvelope>()

    override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? = synchronized(lock) {
        store[scope.storageKey]
    }

    override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean = synchronized(lock) {
        val key = envelope.scope.storageKey
        val existing = store[key]
        if (existing != null && existing.revision >= envelope.revision) {
            return false
        }
        store[key] = envelope
        true
    }

    override suspend fun deleteSnapshot(scope: TimelineScope) {
        synchronized(lock) {
            store.remove(scope.storageKey)
        }
    }

    override suspend fun clearForBackend(backendId: String) {
        synchronized(lock) {
            val toRemove = store.values.filter { it.scope.backendId == backendId }.map { it.scope.storageKey }
            toRemove.forEach { store.remove(it) }
        }
    }

    override suspend fun prune(backendId: String, maxRetainedConversations: Int) {
        if (maxRetainedConversations <= 0) {
            clearForBackend(backendId)
            return
        }
        synchronized(lock) {
            val matching = store.values
                .filter { it.scope.backendId == backendId }
                .sortedByDescending { it.writtenAtMillis }

            if (matching.size > maxRetainedConversations) {
                val toDrop = matching.drop(maxRetainedConversations).map { it.scope.storageKey }
                toDrop.forEach { store.remove(it) }
            }
        }
    }

    fun size(): Int = synchronized(lock) { store.size }
}
