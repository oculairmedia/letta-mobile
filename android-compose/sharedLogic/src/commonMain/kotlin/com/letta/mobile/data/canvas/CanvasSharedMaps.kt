package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Get-or-create over a map held in a [MutableStateFlow], for state a suspending writer and a
 * non-suspending reader both reach.
 *
 * The canvas transports need exactly this and cannot get it from a lock: the write paths are
 * `suspend` and can hold a [kotlinx.coroutines.sync.Mutex], but `subscribe`/`observe` are not, so
 * they reached for `synchronized` instead. Those are two different locks over one map — they do not
 * exclude each other, so the map could be mutated from two threads at once, and because both sides
 * created the missing entry a subscriber could end up holding a different flow than the publisher
 * wrote to and silently receive nothing. `synchronized` is also JVM-only, so it did not compile for
 * wasm or native at all.
 *
 * Compare-and-set is the repo's existing answer for shared maps in `commonMain` (see
 * `ConversationRunRegistry`, `CachedProjectWorkRepository`): one source of truth, no lock to pair
 * up, and every target compiles. [create] may run more than once under contention, so it must be
 * cheap and side-effect free; the winner's value is the one every caller gets back.
 */
internal fun <K, V> MutableStateFlow<Map<K, V>>.getOrCreate(key: K, create: () -> V): V {
    value[key]?.let { return it }
    val created = create()
    while (true) {
        val current = value
        current[key]?.let { return it }
        if (compareAndSet(current, current + (key to created))) return created
    }
}
