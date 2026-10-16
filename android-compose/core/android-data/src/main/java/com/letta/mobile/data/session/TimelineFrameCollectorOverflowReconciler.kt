package com.letta.mobile.data.session

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimelineFrameCollectorOverflowReconciler @Inject constructor(
    private val writers: CanonicalConversationWriters,
) : FrameCollectorOverflowReconciler {
    override suspend fun reconcile(
        conversationId: String,
        connectionGeneration: Long,
    ): RecentMessagesReconcileOutcome = writers.writerFor(ConversationId(conversationId))?.reconcileRecentMessages(
        agentId = null,
        conversationId = conversationId,
        reason = "frame_collector_overflow",
        forceRefresh = true,
        connectionGeneration = connectionGeneration,
    ) ?: RecentMessagesReconcileOutcome.Skipped("unbound")
}
