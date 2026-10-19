package com.letta.mobile.data.controller

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * A map whose reads are lock-free and always see one whole snapshot. Writes
 * replace the immutable backing map atomically, so a reader on another thread (the turn engine's
 * non-suspending permission-mode provider, called from parallel per-key leases) never observes a
 * half-applied mutation the way it could with a plain `mutableMapOf` under a rehash.
 */
internal class CopyOnWriteMap<K, V> {
    private val snapshot = atomic(emptyMap<K, V>())

    operator fun get(key: K): V? = snapshot.value[key]

    operator fun contains(key: K): Boolean = key in snapshot.value

    operator fun set(key: K, value: V) {
        snapshot.update { it + (key to value) }
    }

    fun remove(key: K) {
        snapshot.update { it - key }
    }

    fun clear() {
        snapshot.value = emptyMap()
    }
}
