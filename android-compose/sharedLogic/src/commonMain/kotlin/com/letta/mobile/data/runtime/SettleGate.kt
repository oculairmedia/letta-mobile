package com.letta.mobile.data.runtime

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Lets a `message.list` wait, bounded, for the turn of its conversation that is still being settled. */
internal class SettleGate(private val waitMs: Long) {
    private val lock = SynchronizedObject()
    private val pending = HashMap<String, CompletableDeferred<Unit>>()

    fun expect(conversationId: String) {
        synchronized(lock) { pending.getOrPut(conversationId) { CompletableDeferred() } }
    }

    fun release(conversationId: String) {
        synchronized(lock) { pending.remove(conversationId) }?.complete(Unit)
    }

    /** True when nothing was pending or the pending settle finished inside the wait. */
    suspend fun awaitSettled(conversationId: String): Boolean {
        val gate = synchronized(lock) { pending[conversationId] } ?: return true
        return withTimeoutOrNull(waitMs) { gate.await() } != null
    }
}
