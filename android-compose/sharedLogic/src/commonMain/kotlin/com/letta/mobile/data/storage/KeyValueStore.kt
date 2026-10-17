package com.letta.mobile.data.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable text values by key, for what is too large or too many for a [SecureSettingsStore]:
 * canvas boards and their op logs. Asynchronous because the web keeps it in IndexedDB.
 *
 * Every call may throw when the storage underneath fails (quota, a private window, a blocked
 * site); the shared stores built on it turn that into a reported storage fault, never a crash.
 */
interface KeyValueStore {
    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String)

    suspend fun remove(key: String)

    /** Every stored key that starts with [prefix]. */
    suspend fun keys(prefix: String): List<String>
}

/** A [KeyValueStore] in memory, for tests and hosts without storage. */
class InMemoryKeyValueStore : KeyValueStore {
    private val mutex = Mutex()
    private val values = mutableMapOf<String, String>()

    override suspend fun get(key: String): String? = mutex.withLock { values[key] }

    override suspend fun put(key: String, value: String) = mutex.withLock { values[key] = value }

    override suspend fun remove(key: String) {
        mutex.withLock { values.remove(key) }
    }

    override suspend fun keys(prefix: String): List<String> = mutex.withLock { values.keys.filter { it.startsWith(prefix) } }
}
