package com.letta.mobile.data.timeline.api

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Dispatch leases shared by a runtime factory across its captured generations. */
class PendingDispatchOwners {
    private val lock = SynchronizedObject()
    private val owners = mutableMapOf<Triple<String, String, String>, () -> Boolean>()

    internal suspend fun <T> protecting(key: Triple<String, String, String>, block: suspend () -> T): T {
        val submitting = atomic(true)
        synchronized(lock) {
            val previous = owners[key]?.takeIf { it() }
            owners[key] = { submitting.value || previous?.invoke() == true }
        }
        try {
            return block()
        } finally {
            submitting.value = false
        }
    }

    internal fun retain(backend: String, conversation: String, otid: String, active: () -> Boolean) {
        synchronized(lock) {
            owners.entries.removeAll { !it.value.invoke() }
            owners[Triple(backend, conversation, otid)] = active
        }
    }

    internal fun isActive(backend: String, conversation: String, otid: String): Boolean = synchronized(lock) {
        val key = Triple(backend, conversation, otid)
        val active = owners[key]?.invoke() == true
        if (!active) owners.remove(key)
        active
    }
}
