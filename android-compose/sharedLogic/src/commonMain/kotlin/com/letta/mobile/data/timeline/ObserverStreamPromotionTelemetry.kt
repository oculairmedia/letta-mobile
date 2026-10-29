package com.letta.mobile.data.timeline

import com.letta.mobile.util.attr
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
            "reason" attr "observerPromotionOffTail",
            "serverId" attr event.stableServerId,
            "incomingServerId" attr event.incomingServerId,
            "runId" attr (event.runId ?: "<null>"),
            "mergedLen" attr event.mergedLen,
            "conversationId" attr event.conversationId,
            level = Telemetry.Level.DEBUG,
        )
    }
}
