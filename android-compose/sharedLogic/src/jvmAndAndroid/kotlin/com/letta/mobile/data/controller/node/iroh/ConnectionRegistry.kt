package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A per-connection viewer of a conversation's live turn stream (eaczz.2 owns
 * the write-path/frame-shaping detail; this is the identity the registry keys
 * on). One [IrohNodeConnection] produces one ViewerHandle per conversation it
 * views over its lifetime.
 */
interface ViewerHandle {
    /** Stable id of the owning connection (remoteEndpointId) — for dedup/telemetry. */
    val connectionId: String

    /**
     * Best-effort write of an already-shaped wire frame to this viewer's stream.
     * MUST NOT throw into the caller (a slow/dead observer must never block the
     * initiator's turn — eaczz.6). Returns false if the write failed so the
     * registry/fanout can de-register a dead viewer.
     */
    suspend fun writeFrame(frame: String): Boolean
}

/**
 * Process-scoped registry mapping conversationId -> the set of live
 * [ViewerHandle]s currently viewing that conversation (eaczz.1). Owned by
 * [IrohNodeEndpoint] and shared across all connections so a turn on one
 * connection can fan its frames out to every connection viewing the same
 * conversation.
 *
 * Thread-safe: all mutations/reads go through [mutex]. A connection may hold
 * viewer handles under multiple conversationIds over its lifetime; [unregisterAll]
 * cleans up every entry for a connection on disconnect.
 */
class ConnectionRegistry {
    private val mutex = Mutex()
    // conversationId -> viewer handles
    private val viewersByConversation = mutableMapOf<String, MutableSet<ViewerHandle>>()
    private val registrationEpochs = mutableMapOf<Pair<String, String>, Long>()

    suspend fun register(conversationId: String, viewer: ViewerHandle) {
        mutex.withLock {
            viewersByConversation.getOrPut(conversationId) { mutableSetOf() }.add(viewer)
            val key = conversationId to viewer.connectionId
            registrationEpochs[key] = (registrationEpochs[key] ?: 0L) + 1L
        }
    }

    suspend fun unregister(conversationId: String, viewer: ViewerHandle) {
        mutex.withLock {
            viewersByConversation[conversationId]?.let { set ->
                set.remove(viewer)
                if (set.isEmpty()) viewersByConversation.remove(conversationId)
            }
        }
    }

    /** Remove every entry belonging to [connectionId] across all conversations (disconnect). */
    suspend fun unregisterAll(connectionId: String) {
        mutex.withLock {
            val emptied = mutableListOf<String>()
            viewersByConversation.forEach { (conv, set) ->
                set.removeAll { it.connectionId == connectionId }
                if (set.isEmpty()) emptied.add(conv)
            }
            emptied.forEach { viewersByConversation.remove(it) }
        }
    }

    /** Snapshot of viewers for a conversation (defensive copy — safe to iterate + write outside the lock). */
    suspend fun viewersFor(conversationId: String): Set<ViewerHandle> =
        mutex.withLock { viewersByConversation[conversationId]?.toSet() ?: emptySet() }

    suspend fun registrationEpoch(conversationId: String, connectionId: String): Long =
        mutex.withLock { registrationEpochs[conversationId to connectionId] ?: 0L }

    /** Test/telemetry: total distinct conversations currently viewed. */
    suspend fun conversationCount(): Int = mutex.withLock { viewersByConversation.size }
}
