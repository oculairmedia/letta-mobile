package com.letta.mobile.data.timeline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runtime admission only. Durable storage epochs remain the final commit fence. */
class TimelineLegacyAdmission {
    private class Entry {
        var closed = false
        var active = 0
        val drained = CompletableDeferred<Unit>()
    }
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> admitted(conversationId: String, operation: suspend () -> T): T {
        val entry = mutex.withLock {
            entries.getOrPut(conversationId) { Entry() }.also {
                check(!it.closed) { "Legacy timeline admission closed" }
                it.active++
            }
        }
        try {
            return operation()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    entry.active--
                    if (entry.active == 0) {
                        if (entry.closed) entry.drained.complete(Unit)
                        else entries.remove(conversationId)
                    }
                }
            }
        }
    }

    /** Closing is irreversible for this repository generation, including cancellation. */
    suspend fun close(conversationId: String) {
        val drained = mutex.withLock {
            val entry = entries.getOrPut(conversationId) { Entry() }
            entry.closed = true
            if (entry.active == 0) entry.drained.complete(Unit)
            entry.drained
        }
        drained.await()
    }
}
