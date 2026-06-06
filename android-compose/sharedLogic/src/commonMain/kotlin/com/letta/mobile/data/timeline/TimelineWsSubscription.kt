package com.letta.mobile.data.timeline

/**
 * Tracks the state of the WebSocket (external transport) subscription.
 *
 * Ensures that if an external transport is active (e.g., admin-shim WS delivering messages),
 * the SSE stream subscriber suppresses dual ingestion.
 */
class TimelineWsSubscription(
    @Suppress("unused") private val conversationId: String,
) {
    private val externalTransportActive = TimelineAtomicFlag()

    fun isActive(): Boolean = externalTransportActive.get()

    fun markActive() {
        externalTransportActive.set(true)
    }

    fun clear() {
        externalTransportActive.set(false)
    }
}
