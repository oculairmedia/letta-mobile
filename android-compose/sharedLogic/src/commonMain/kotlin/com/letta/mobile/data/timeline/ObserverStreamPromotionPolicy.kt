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
        val context = incoming.promotionContext() ?: return null
        return timeline.activeReverseTail().findPromotionTarget(context)
    }

    private data class PromotionContext(
        val incoming: TimelineEvent.Confirmed,
        val runId: String,
        val text: String,
    )

    private fun TimelineEvent.Confirmed.promotionContext(): PromotionContext? {
        val realRunId = realAssistantRunId() ?: return null
        val incomingText = content.trim().takeIf(String::isNotEmpty) ?: return null
        return PromotionContext(this, realRunId, incomingText)
    }

    private fun List<TimelineEvent>.findPromotionTarget(context: PromotionContext): TimelineEvent.Confirmed? {
        var sawIncomingRunBridge = false
        for (event in this) {
            val existing = event as? TimelineEvent.Confirmed ?: continue
            sawIncomingRunBridge = sawIncomingRunBridge || existing.hasRunId(context.runId)
            if (existing.isPromotionTarget(context, sawIncomingRunBridge)) return existing
        }
        return null
    }

    private fun TimelineEvent.Confirmed.hasRunId(expected: String): Boolean =
        runId?.takeIf(String::isNotBlank) == expected

    private fun TimelineEvent.Confirmed.isPromotionTarget(
        context: PromotionContext,
        sawIncomingRunBridge: Boolean,
    ): Boolean = isEligibleObserverAssistant(context.incoming, sawIncomingRunBridge) &&
        content.trim().isStrictPrefixOf(context.text)

    private fun String.isStrictPrefixOf(incomingText: String): Boolean =
        isNotEmpty() && incomingText.length > length && incomingText.startsWith(this)

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
