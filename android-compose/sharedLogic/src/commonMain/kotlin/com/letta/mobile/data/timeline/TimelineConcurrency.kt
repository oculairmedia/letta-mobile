package com.letta.mobile.data.timeline

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TimelineAtomicCounter(initialValue: Int = 0) {
    private val value = atomic(initialValue)

    fun incrementAndGet(): Int = value.incrementAndGet()

    fun decrementAndGet(): Int = value.decrementAndGet()
}

class TimelineSeenRunTracker {
    private val mutex = Mutex()
    private val seenRunIds = mutableSetOf<String>()

    suspend fun markSeen(runId: String): Boolean =
        mutex.withLock { seenRunIds.add(runId) }
}
