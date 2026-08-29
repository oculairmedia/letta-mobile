package com.letta.mobile.data.timeline

internal sealed interface ObserverStreamPromotionDecision {
    data object NoPromotion : ObserverStreamPromotionDecision

    data class Promote(
        val stableServerId: String,
        val merged: TimelineEvent.Confirmed,
    ) : ObserverStreamPromotionDecision
}

/** Selects and promotes a bounded, provenance-linked observer assistant placeholder. */
internal object ObserverStreamPromotionPolicy {
    internal const val OBSERVER_RUN_ID_PREFIX = "iroh-observer-run-"
    private const val TAIL_EVENT_LIMIT = 30

    fun decide(
        timeline: Timeline,
        incoming: TimelineEvent.Confirmed,
    ): ObserverStreamPromotionDecision {
        val target = findTarget(timeline, incoming) ?: return ObserverStreamPromotionDecision.NoPromotion
        return ObserverStreamPromotionDecision.Promote(
            stableServerId = target.serverId,
            merged = target.copy(
                content = if (incoming.content.length >= target.content.length) incoming.content else target.content,
                runId = incoming.runId,
                seqId = latestSeqId(target.seqId, incoming.seqId),
            ),
        )
    }

    private fun findTarget(
        timeline: Timeline,
        incoming: TimelineEvent.Confirmed,
    ): TimelineEvent.Confirmed? {
        val incomingRunId = incoming.realAssistantRunId() ?: return null
        val incomingText = incoming.content.trim().takeIf { it.isNotEmpty() } ?: return null
        var sawIncomingRunBridge = false

        for (event in timeline.activeReverseTail()) {
            val existing = event as? TimelineEvent.Confirmed ?: continue
            if (existing.runId?.takeIf { it.isNotBlank() } == incomingRunId) sawIncomingRunBridge = true
            if (!existing.isEligibleObserverAssistant(incoming, sawIncomingRunBridge)) continue

            val existingText = existing.content.trim()
            if (existingText.isNotEmpty() &&
                incomingText.length > existingText.length &&
                incomingText.startsWith(existingText)
            ) {
                return existing
            }
        }
        return null
    }

    private fun TimelineEvent.Confirmed.realAssistantRunId(): String? {
        if (messageType != TimelineMessageType.ASSISTANT) return null
        return runId?.takeIf { it.isNotBlank() && !it.isIrohSyntheticRunId() }
    }

    private fun TimelineEvent.Confirmed.isEligibleObserverAssistant(
        incoming: TimelineEvent.Confirmed,
        sawIncomingRunBridge: Boolean,
    ): Boolean = sawIncomingRunBridge &&
        messageType == TimelineMessageType.ASSISTANT &&
        serverId != incoming.serverId &&
        runId?.startsWith(OBSERVER_RUN_ID_PREFIX) == true

    private fun Timeline.activeReverseTail(): List<TimelineEvent> {
        val reverseTail = events.takeLast(TAIL_EVENT_LIMIT).asReversed()
        val boundaryOffset = reverseTail.indexOfFirst { event ->
            event is TimelineEvent.Confirmed &&
                (event.messageType == TimelineMessageType.USER || event.messageType == TimelineMessageType.SYSTEM)
        }
        return if (boundaryOffset >= 0) reverseTail.take(boundaryOffset) else reverseTail
    }
}
