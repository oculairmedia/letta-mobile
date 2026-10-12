package com.letta.mobile.data.document

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One peer's ephemeral presence on a shared document: who, when last seen, and whether still there. */
interface PeerPresence {
    val peerId: String
    val lastActiveEpochMs: Long
    val isActive: Boolean
}

/**
 * Transport contract for peer presence on shared documents. Kept apart from the [OpLog]: presence
 * is never persisted and never enters a snapshot.
 */
interface PresenceTransport<Id, P : PeerPresence> {
    suspend fun updatePresence(documentId: Id, presence: P)

    fun observePresence(documentId: Id): Flow<List<P>>
}

/**
 * In-memory [PresenceTransport] with TTL expiry: a peer that reports `isActive = false`, or has not
 * reported for longer than [ttlMs], is removed. [stamp] returns the presence with its last-active
 * time set, since the transport (not the peer) is the clock that expiry trusts.
 */
open class InMemoryPresenceTransport<Id, P : PeerPresence>(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val stamp: (presence: P, nowEpochMs: Long) -> P,
) : PresenceTransport<Id, P> {
    private val mutex = Mutex()
    private val presencesByDocument = mutableMapOf<Id, MutableMap<String, P>>()

    // Reached from the non-suspending observe path as well as the suspending writer, so it cannot
    // live behind the mutex. See [getOrCreate].
    private val flowsByDocument = MutableStateFlow<Map<Id, MutableStateFlow<List<P>>>>(emptyMap())

    private fun flowFor(documentId: Id): MutableStateFlow<List<P>> =
        flowsByDocument.getOrCreate(documentId) { MutableStateFlow(emptyList()) }

    override suspend fun updatePresence(documentId: Id, presence: P) = mutex.withLock {
        val peers = presencesByDocument.getOrPut(documentId) { mutableMapOf() }
        val now = clock()
        if (presence.isActive) {
            peers[presence.peerId] = stamp(presence, now)
        } else {
            peers.remove(presence.peerId)
        }
        peers.entries.removeAll { now - it.value.lastActiveEpochMs > ttlMs }
        flowFor(documentId).value = peers.values.toList()
    }

    override fun observePresence(documentId: Id): Flow<List<P>> = flowFor(documentId).asStateFlow()

    companion object {
        /** Peers silent for longer than this are gone. */
        const val DEFAULT_TTL_MS: Long = 10_000L
    }
}
