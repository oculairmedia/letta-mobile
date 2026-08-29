package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry

internal object ObserverStreamPromotionTelemetry {
    fun emit(
        stableServerId: String,
        incomingServerId: String,
        runId: String?,
        mergedLen: Int,
        conversationId: String,
    ) {
        if (!Telemetry.isChatHotPathDebugEnabled()) return
        Telemetry.event(
            "TimelineSync",
            "streamSubscriber.forwardGrowthMerged",
            "reason" to "observerPromotionOffTail",
            "serverId" to stableServerId,
            "incomingServerId" to incomingServerId,
            "runId" to (runId ?: "<null>"),
            "mergedLen" to mergedLen,
            "conversationId" to conversationId,
            level = Telemetry.Level.DEBUG,
        )
    }
}
