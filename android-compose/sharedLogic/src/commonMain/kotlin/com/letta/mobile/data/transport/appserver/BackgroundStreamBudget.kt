package com.letta.mobile.data.transport.appserver

/**
 * letta-mobile data-efficiency audit, Phase 4 (H4): single source of truth
 * for how many stream subscribers the app proactively warms at startup, and
 * which conversations those slots are reserved for.
 *
 * Allocation policy:
 *   1. The currently visible conversation consumes slot 0 (when known) so it
 *      is never displaced by recency ordering.
 *   2. The remaining slots are filled by recency order (most-recent first),
 *      deduped against the current id.
 *   3. Result is capped at [MAX_WARM_STREAMS].
 *
 * Anything older than the allocated window incurs a one-shot getOrCreate
 * hydrate on first open (~500ms — imperceptible if the user just tapped)
 * and starts its own subscriber from there. Foreground UI getOrCreate
 * remains on-demand and is not evicted by this budget.
 *
 * OkHttp dispatcher is tuned in [com.letta.mobile.data.api.LettaApiClient]
 * with `maxRequestsPerHost = 16`, leaving headroom for these background
 * streams plus foreground sends/fetches. Do not raise [MAX_WARM_STREAMS]
 * without telemetry showing foreground requests are not starved.
 */
object BackgroundStreamBudget {
    /** Total warmup budget — must match `ChatPushService.MAX_BACKGROUND_PERSISTENT_STREAMS`. */
    const val MAX_WARM_STREAMS: Int = 3

    /** Telemetry label for the currently visible conversation slot. */
    const val PRIORITY_CURRENT: Int = 0

    /** Telemetry label for recency-ordered slots. */
    const val PRIORITY_RECENT: Int = 1

    /**
     * Build the warmup set: current first (if known), then recent ids in order,
     * deduped, capped at [MAX_WARM_STREAMS].
     */
    fun allocate(
        currentConversationId: String?,
        recentConversationIds: List<String>,
    ): List<String> = buildList {
        currentConversationId?.let { add(it) }
        recentConversationIds.forEach { id ->
            if (id !in this) add(id)
        }
    }.take(MAX_WARM_STREAMS)
}