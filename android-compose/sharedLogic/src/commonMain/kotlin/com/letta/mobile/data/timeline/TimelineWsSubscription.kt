package com.letta.mobile.data.timeline

/**
 * Tracks the state of the WebSocket (external transport) subscription.
 *
 * Ensures that if an external transport is active (e.g., admin-shim WS delivering messages),
 * the SSE stream subscriber suppresses dual ingestion.
 *
 * letta-mobile-h30cy: the active-flag is now keyed on the conversationId in a
 * process-wide [TimelineExternalTransportRegistry], NOT per loop instance. Over
 * Iroh two loop instances could exist for one conversation (scoped/unscoped
 * aliasing gap), so a per-instance flag left the dual-ingest guard checking the
 * wrong instance -> both paths ingested every frame (2x reducer input ->
 * duplicate row + random character drops). Sharing by conversationId makes the
 * guard correct regardless of instance count.
 */
class TimelineWsSubscription(
    private val conversationId: String,
) {
    fun isActive(): Boolean = TimelineExternalTransportRegistry.isActive(conversationId)

    fun markActive() {
        TimelineExternalTransportRegistry.markActive(conversationId)
    }

    fun clear() {
        TimelineExternalTransportRegistry.clear(conversationId)
    }
}
