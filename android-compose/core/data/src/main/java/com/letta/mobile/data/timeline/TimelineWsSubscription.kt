package com.letta.mobile.data.timeline

/**
 * Tracks the state of the WebSocket (external transport) subscription.
 *
 * Ensures that if an external transport is active (e.g., admin-shim WS delivering messages),
 * the SSE stream subscriber suppresses dual ingestion.
 */
internal class TimelineWsSubscription(
    private val conversationId: String,
) {
    @Volatile
    private var externalTransportActive = false

    fun isActive(): Boolean = externalTransportActive

    fun markActive() {
        externalTransportActive = true
    }

    fun clear() {
        externalTransportActive = false
    }
}
