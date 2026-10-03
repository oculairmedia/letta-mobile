package com.letta.mobile.data.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One conversation's row identities: loaded from the [store] on first use, appended through it. */
internal class ConversationIdentities(
    val conversationId: String,
    private val store: TurnIdentityStore,
) {
    private val mutex = Mutex()
    private val mapped = LinkedHashMap<StoredRowRef, RowIdentity>()
    private var loaded = false
    private var backfilled = false

    suspend fun snapshot(): Map<StoredRowRef, RowIdentity> = mutex.withLock { ensureLoaded().toMap() }

    suspend fun persist(entries: Map<StoredRowRef, RowIdentity>) {
        mutex.withLock {
            ensureLoaded()
            store.append(conversationId, entries)
            mapped.putAll(entries)
        }
    }

    suspend fun isBackfilled(): Boolean = mutex.withLock { backfilled }

    suspend fun markBackfilled() = mutex.withLock { backfilled = true }

    private suspend fun ensureLoaded(): MutableMap<StoredRowRef, RowIdentity> {
        if (!loaded) {
            mapped.putAll(store.load(conversationId))
            loaded = true
        }
        return mapped
    }
}
