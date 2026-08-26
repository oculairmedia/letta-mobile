package com.letta.mobile.data.session

import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimelineFrameCollectorOverflowReconciler @Inject constructor(
    private val timelineWriter: Lazy<TimelineExternalTransportWriter>,
) : FrameCollectorOverflowReconciler {
    override suspend fun reconcile(
        conversationId: String,
        connectionGeneration: Long,
    ): RecentMessagesReconcileOutcome = timelineWriter.get().reconcileRecentMessages(
        agentId = null,
        conversationId = conversationId,
        reason = "frame_collector_overflow",
        forceRefresh = true,
        connectionGeneration = connectionGeneration,
    )
}
