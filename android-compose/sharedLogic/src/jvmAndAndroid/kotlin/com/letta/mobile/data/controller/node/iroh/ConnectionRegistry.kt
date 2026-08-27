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
    /** Full canonical transport-authenticated endpoint id; retained in memory for dedup only. */
    val connectionId: String

    /**
     * Best-effort write of an already-shaped wire frame to this viewer's stream.
     * MUST NOT throw into the caller (a slow/dead observer must never block the
     * initiator's turn — eaczz.6). Returns false if the write failed so the
     * registry/fanout can de-register a dead viewer.
     */
    suspend fun writeFrame(frame: String): Boolean
}

/** Opaque ownership token for one canonical endpoint connection generation. */
class ViewerRegistration internal constructor(
    internal val endpointId: String,
    internal val generation: Long,
    internal val viewer: ViewerHandle,
)

/**
 * Process-scoped registry mapping conversationId -> the set of live
 * [ViewerHandle]s currently viewing that conversation (eaczz.1). Owned by
 * [IrohNodeEndpoint] and shared across all connections so a turn on one
 * connection can fan its frames out to every connection viewing the same
 * conversation.
 *
 * Thread-safe: all mutations/reads go through [mutex]. Each endpoint claim has
 * a monotonic generation, and [release] removes only subscriptions owned by that
 * exact generation so stale disconnects cannot evict a reconnect.
 */
class ConnectionRegistry {
    private val mutex = Mutex()
    private var nextGeneration = 1L
    private val activeByEndpoint = mutableMapOf<String, ViewerRegistration>()
    // conversationId -> canonical endpoint identity -> current registration
    private val viewersByConversation = mutableMapOf<String, MutableMap<String, ViewerRegistration>>()

    /**
     * Atomically claims [viewer.connectionId] for this connection generation.
     * A reconnect supersedes every subscription owned by the previous generation.
     */
    suspend fun claim(viewer: ViewerHandle): ViewerRegistration = mutex.withLock {
        claimLocked(viewer)
    }

    /**
     * Convenience registration for tests and direct callers. Reuses this exact
     * handle's active claim, or atomically creates a new generation before adding
     * the conversation subscription.
     */
    suspend fun register(conversationId: String, viewer: ViewerHandle): ViewerRegistration = mutex.withLock {
        val active = activeByEndpoint[viewer.connectionId]
        val registration = if (active?.viewer === viewer) active else claimLocked(viewer)
        viewersByConversation.getOrPut(conversationId) { mutableMapOf() }[registration.endpointId] = registration
        registration
    }

    /** Register [registration] only while it remains the endpoint's current generation. */
    suspend fun register(conversationId: String, registration: ViewerRegistration): Boolean = mutex.withLock {
        if (activeByEndpoint[registration.endpointId] !== registration) return@withLock false
        viewersByConversation.getOrPut(conversationId) { mutableMapOf() }[registration.endpointId] = registration
        true
    }

    /** Remove [viewer] only when it is the exact handle currently registered. */
    suspend fun unregister(conversationId: String, viewer: ViewerHandle) {
        mutex.withLock {
            val viewers = viewersByConversation[conversationId] ?: return@withLock
            val registration = viewers[viewer.connectionId]
            if (registration?.viewer === viewer) viewers.remove(viewer.connectionId)
            if (viewers.isEmpty()) viewersByConversation.remove(conversationId)
        }
    }

    /**
     * Releases one connection generation across every conversation. A stale
     * disconnect cannot evict a newer claim for the same endpoint.
     */
    suspend fun release(registration: ViewerRegistration) {
        mutex.withLock {
            if (activeByEndpoint[registration.endpointId] !== registration) return@withLock
            activeByEndpoint.remove(registration.endpointId)
            removeSubscriptionsLocked(registration)
        }
    }

    /** Snapshot of viewers for a conversation (defensive copy — safe to iterate + write outside the lock). */
    suspend fun viewersFor(conversationId: String): Set<ViewerHandle> = mutex.withLock {
        viewersByConversation[conversationId]?.values?.mapTo(linkedSetOf()) { it.viewer } ?: emptySet()
    }

    /** Test/telemetry: total distinct conversations currently viewed. */
    suspend fun conversationCount(): Int = mutex.withLock { viewersByConversation.size }

    private fun claimLocked(viewer: ViewerHandle): ViewerRegistration {
        require(viewer.connectionId.isNotBlank()) { "viewer endpoint identity must not be blank" }
        activeByEndpoint[viewer.connectionId]?.let(::removeSubscriptionsLocked)
        return ViewerRegistration(
            endpointId = viewer.connectionId,
            generation = nextGeneration++,
            viewer = viewer,
        ).also { activeByEndpoint[viewer.connectionId] = it }
    }

    private fun removeSubscriptionsLocked(registration: ViewerRegistration) {
        val emptied = mutableListOf<String>()
        viewersByConversation.forEach { (conversationId, viewers) ->
            if (viewers[registration.endpointId] === registration) viewers.remove(registration.endpointId)
            if (viewers.isEmpty()) emptied += conversationId
        }
        emptied.forEach(viewersByConversation::remove)
    }
}
