package com.letta.mobile.data.timeline

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TimelineAtomicCounter(initialValue: Int = 0) {
    private val value = atomic(initialValue)

    fun incrementAndGet(): Int = value.incrementAndGet()

    fun decrementAndGet(): Int = value.decrementAndGet()
}

class TimelineAtomicFlag(initialValue: Boolean = false) {
    private val value = atomic(initialValue)

    fun get(): Boolean = value.value

    fun set(newValue: Boolean) {
        value.value = newValue
    }
}

class TimelineSeenRunTracker {
    private val mutex = Mutex()
    private val seenRunIds = mutableSetOf<String>()

    suspend fun markSeen(runId: String): Boolean =
        mutex.withLock { seenRunIds.add(runId) }
}

object TimelineStreamDedupeRegistry {
    private val lock = SynchronizedObject()
    private val seenByConversation = LinkedHashMap<String, LinkedHashSet<String>>()

    fun markSeen(conversationId: String, key: String): Boolean = synchronized(lock) {
        val keys = seenByConversation.remove(conversationId) ?: LinkedHashSet()
        seenByConversation[conversationId] = keys

        val added = keys.add(key)
        if (added) {
            while (keys.size > MAX_SEEN_STREAM_MESSAGES_PER_CONVERSATION) {
                keys.remove(keys.first())
            }
            trimConversations()
        }
        !added
    }

    private fun trimConversations() {
        while (seenByConversation.size > MAX_SEEN_STREAM_MESSAGE_CONVERSATIONS) {
            val oldest = seenByConversation.keys.firstOrNull() ?: break
            seenByConversation.remove(oldest)
        }
    }

    private const val MAX_SEEN_STREAM_MESSAGES_PER_CONVERSATION = 512
    private const val MAX_SEEN_STREAM_MESSAGE_CONVERSATIONS = 256
}
