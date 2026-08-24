package com.letta.mobile.data.timeline.snapshot

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

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
     */
    suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope?

    /**
     * Write [envelope] atomically.
     * Returns `true` if written, `false` if rejected due to a stale revision.
     */
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
        if (maxRetainedConversations <= 0) return
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
