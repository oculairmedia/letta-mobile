package com.letta.mobile.data.timeline

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Bounded mutation accumulator shared by processor publication and snapshot persistence. */
class PendingTimelinePersistenceDelta : SynchronizedObject() {
    private val changed = linkedSetOf<String>()
    private val deleted = linkedSetOf<String>()
    private var metadataChanged = false
    private var fallbackReason: SnapshotPlanningFallback? = null
    private var latestSequence = 0L

    fun merge(sequence: Long, delta: TimelineMutationDelta) = synchronized(this) {
        latestSequence = maxOf(latestSequence, sequence)
        when (delta) {
            TimelineMutationDelta.None -> Unit
            is TimelineMutationDelta.RequiresFullRescan -> fallbackReason = fallbackReason ?: delta.reason
            is TimelineMutationDelta.Exact -> {
                if (fallbackReason != null) return@synchronized
                delta.changedConfirmedServerIds.forEach { serverId ->
                    deleted.remove(serverId)
                    changed += serverId
                }
                delta.deletedConfirmedServerIds.forEach { serverId ->
                    changed.remove(serverId)
                    deleted += serverId
                }
                metadataChanged = metadataChanged || delta.metadataChanged
                if (changed.size + deleted.size > MAX_PENDING_IDENTITIES) {
                    changed.clear()
                    deleted.clear()
                    fallbackReason = SnapshotPlanningFallback.PENDING_DELTA_TOO_WIDE
                }
            }
        }
    }

    fun snapshot(): Snapshot = synchronized(this) {
        Snapshot(
            throughSequence = latestSequence,
            changedConfirmedServerIds = changed.toSet(),
            deletedConfirmedServerIds = deleted.toSet(),
            metadataChanged = metadataChanged,
            fallbackReason = fallbackReason,
        )
    }

    fun acknowledge(throughSequence: Long) = synchronized(this) {
        if (latestSequence > throughSequence) return@synchronized
        changed.clear()
        deleted.clear()
        metadataChanged = false
        fallbackReason = null
    }

    data class Snapshot(
        val throughSequence: Long,
        val changedConfirmedServerIds: Set<String>,
        val deletedConfirmedServerIds: Set<String>,
        val metadataChanged: Boolean,
        val fallbackReason: SnapshotPlanningFallback?,
    ) {
        val requiresFullRescan: Boolean get() = fallbackReason != null
        val dirtyIdentityCount: Int get() = changedConfirmedServerIds.size + deletedConfirmedServerIds.size
        val isEmpty: Boolean get() = !requiresFullRescan && dirtyIdentityCount == 0 && !metadataChanged
    }

    private companion object {
        const val MAX_PENDING_IDENTITIES = 256
    }
}
