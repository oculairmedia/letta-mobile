package com.letta.mobile.data.timeline

import kotlinx.atomicfu.atomic
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
