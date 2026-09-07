package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope

/** Host-neutral session. Transport calls happen outside the engine storage mutex. */
class CanonicalTimelineSession(
    store: TimelineBoundedStore,
    private val transport: TimelineTransport,
    private val scope: TimelineScope,
    enabled: Boolean = false,
    budget: TimelinePageBudget = TimelinePageBudget(64, 2L * 1024 * 1024),
) {
    val engine = CanonicalTimelineEngine(
        store, TimelineExactCanonicalWriter(scope, minOf(budget.maxDecodedBodyBytes, Int.MAX_VALUE.toLong()).toInt()),
        budget, enabled,
    )
    val publication = engine.publication
    val live = engine.live

    suspend fun open(target: TimelineMessageId? = null): TimelineEngineOpen = engine.open(scope, target)

    suspend fun loadOlder(selection: TimelineEngineSelection): TimelineEnginePageOutcome {
        val request = engine.beginPage(selection)
        return when (val response = transport.listConversationMessagePage(request.remote)) {
            is TimelineRemotePageResult.Page -> engine.applyPage(request, response)
            is TimelineRemotePageResult.NoProgress -> engine.rejectNoProgress(request, response)
        }
    }

    suspend fun beginLive(selection: TimelineEngineSelection): TimelineLiveFence = engine.beginLive(selection)

    suspend fun ingest(fence: TimelineLiveFence, frame: TimelineStreamFrame): Boolean = engine.ingest(fence, frame)

    suspend fun acknowledgeSettlement(fence: TimelineLiveFence, presented: Map<TimelineMessageId, Long>): Boolean =
        engine.acknowledgeSettlement(fence, presented)

    suspend fun close(selection: TimelineEngineSelection) = engine.release(selection)
}
