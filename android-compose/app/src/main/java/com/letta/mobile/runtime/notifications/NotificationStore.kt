package com.letta.mobile.runtime.notifications

import kotlinx.serialization.Serializable
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A single captured notification, flattened to the fields the agent needs.
 * Intentionally minimal — no large payloads, no PendingIntents — to keep the
 * cross-app data surface (and memory) small.
 */
@Serializable
data class CapturedNotification(
    val packageName: String,
    val title: String?,
    val text: String?,
    val postTimeMillis: Long,
)

/**
 * Bounded in-memory store of recently posted notifications. Injectable as an
 * interface so [NotificationPollTool] is unit-testable with a fake store and
 * no Robolectric — the real [LettaNotificationListenerService] writes here.
 */
interface NotificationStore {
    fun record(notification: CapturedNotification)
    /** Most-recent-first, capped at [limit]. */
    fun recent(limit: Int): List<CapturedNotification>
    fun clear()
}

class InMemoryNotificationStore(
    private val maxSize: Int = DEFAULT_MAX_SIZE,
) : NotificationStore {
    private val lock = ReentrantReadWriteLock()
    private val buffer = ArrayDeque<CapturedNotification>(maxSize)

    override fun record(notification: CapturedNotification) = lock.write {
        buffer.addLast(notification)
        while (buffer.size > maxSize) {
            buffer.removeFirst()
        }
    }

    override fun recent(limit: Int): List<CapturedNotification> = lock.read {
        if (limit <= 0) return emptyList()
        // Newest first.
        buffer.asReversed().take(limit).toList()
    }

    override fun clear() = lock.write { buffer.clear() }

    companion object {
        const val DEFAULT_MAX_SIZE = 50
    }
}
