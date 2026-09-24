package com.letta.mobile.data.transport

import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * letta-mobile-qygvv.11: exact-frame deduplication at the transport's single
 * emit seam, BEFORE fan-out.
 *
 * Every subscriber of the frame stream (the chat coordinator, the goal and A2UI
 * coordinators, repositories) used to receive each duplicate and pay for its
 * projection before the coordinator's [TimelineEventKeys.key] dropped it. Here a
 * duplicate is recognised once, from the [TransportFrameEvent.timelineEvent]
 * projection the subscribers would share anyway, and never published.
 *
 * Frames with no timeline projection (A2UI, cron, subagent snapshots, the
 * self-todo chip frame, ...) are never deduplicated: they have no key.
 *
 * Owned by one transport instance; the window is bounded like the coordinator's.
 */
internal class IngestFrameDeduplicator(
    private val maxSeen: Int = DEFAULT_MAX_SEEN,
) {
    private val lock = SynchronizedObject()
    private val keys = ArrayDeque<String>()
    private val keySet = HashSet<String>()
    private var dropped = 0L

    /** Duplicates suppressed at ingest since construction (or the last [reset]). */
    val droppedCount: Long get() = synchronized(lock) { dropped }

    /** True when [event] repeats a frame already published inside the window. */
    fun isDuplicate(event: TransportFrameEvent): Boolean {
        val timelineEvent = event.timelineEvent ?: return false
        val key = TimelineEventKeys.ingestKey(timelineEvent) ?: return false
        val duplicate = synchronized(lock) { rememberLocked(key) }
        if (duplicate) {
            Telemetry.event(
                "IrohGate", "ingest.exactDuplicateDropped",
                "eventType" to (timelineEvent::class.simpleName ?: ""),
                "keyHash" to key.hashCode().toString(),
            )
        }
        return duplicate
    }

    fun reset() = synchronized(lock) {
        keys.clear()
        keySet.clear()
        dropped = 0L
    }

    private fun rememberLocked(key: String): Boolean {
        if (!keySet.add(key)) {
            dropped++
            return true
        }
        keys.addLast(key)
        while (keys.size > maxSeen) keySet.remove(keys.removeFirst())
        return false
    }

    companion object {
        const val DEFAULT_MAX_SEEN = 512
    }
}
