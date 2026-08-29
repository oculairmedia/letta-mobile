package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry

internal object ObserverStreamPromotionTelemetry {
    data class Event(
        val stableServerId: String,
        val incomingServerId: String,
        val runId: String?,
        val mergedLen: Int,
        val conversationId: String,
    )

    fun emit(event: Event) {
        if (!Telemetry.isChatHotPathDebugEnabled()) return
        Telemetry.event(
            "TimelineSync",
            "streamSubscriber.forwardGrowthMerged",
            "reason" to "observerPromotionOffTail",
            "serverId" to event.stableServerId,
            "incomingServerId" to event.incomingServerId,
            "runId" to (event.runId ?: "<null>"),
            "mergedLen" to event.mergedLen,
            "conversationId" to event.conversationId,
            level = Telemetry.Level.DEBUG,
        )
    }
}
