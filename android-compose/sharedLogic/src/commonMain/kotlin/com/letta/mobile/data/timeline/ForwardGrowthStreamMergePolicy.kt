package com.letta.mobile.data.timeline

internal data class ForwardGrowthMergeResult(
    val timeline: Timeline,
    val stableServerId: String,
)

/**
 * letta-mobile-lf4hh: widest gap between two streamed fragments of ONE assistant
 * reply. Fragments of a single reply are emitted within the same generation loop
 * (milliseconds apart); anything older at the timeline tail is a settled message.
 */
private const val SAME_FRAGMENT_WINDOW_MS = 2_000L

/**
 * True when [existing] and [incoming] are close enough in time to be fragments of
 * the same in-flight reply. Order-insensitive: a row can carry a server date
 * marginally AHEAD of the live fragment's client date.
 */
private fun isWithinSameFragmentWindow(existing: TimelineInstant, incoming: TimelineInstant): Boolean {
    val delta = timelineInstantDurationMillis(existing, incoming)
    val magnitude = if (delta < 0) -delta else delta
    return magnitude <= SAME_FRAGMENT_WINDOW_MS
}

private fun isCompatibleForwardGrowthRun(
    liveAssistant: TimelineEvent.Confirmed,
    confirmed: TimelineEvent.Confirmed,
): Boolean {
    val existingRun = liveAssistant.runId?.takeIf { it.isNotBlank() }
    val incomingRun = confirmed.runId?.takeIf { it.isNotBlank() }
    return when {
        existingRun == null -> incomingRun == null &&
            isWithinSameFragmentWindow(liveAssistant.date, confirmed.date)
        incomingRun == null -> false
        else -> existingRun == incomingRun || existingRun.isIrohSyntheticRunId()
    }
}

private fun isForwardGrowthFragment(
    liveAssistant: TimelineEvent.Confirmed,
    confirmed: TimelineEvent.Confirmed,
): Boolean {
    if (confirmed.messageType != TimelineMessageType.ASSISTANT || liveAssistant.serverId == confirmed.serverId) {
        return false
    }
    if (!isCompatibleForwardGrowthRun(liveAssistant, confirmed)) {
        return false
    }
    val existing = liveAssistant.content.trim()
    val incoming = confirmed.content.trim()
    return existing.isNotEmpty() && incoming.length > existing.length && incoming.startsWith(existing)
}

/**
 * Merges cumulative streamed assistant fragments on strict forward growth.
 */
internal fun applyForwardGrowthMerge(
    timeline: Timeline,
    confirmed: TimelineEvent.Confirmed,
    conversationId: String,
): ForwardGrowthMergeResult? {
    val liveAssistant = (timeline.events.lastOrNull() as? TimelineEvent.Confirmed)
        ?.takeIf { it.messageType == TimelineMessageType.ASSISTANT } ?: return null
    if (!isForwardGrowthFragment(liveAssistant, confirmed)) return null

    val merged = liveAssistant.copy(
        content = confirmed.content,
        runId = promoteRunId(liveAssistant.runId, confirmed.runId),
        seqId = latestSeqId(liveAssistant.seqId, confirmed.seqId),
    )
    val nextTimeline = timeline.replaceByServerId(merged).copy(liveCursor = liveAssistant.serverId)
    hotPathTelemetry(
        "streamSubscriber.forwardGrowthMerged",
        "serverId" to liveAssistant.serverId,
        "incomingServerId" to confirmed.serverId,
        "runId" to (confirmed.runId ?: "<null>"),
        "mergedLen" to confirmed.content.length,
        "conversationId" to conversationId,
    )
    return ForwardGrowthMergeResult(
        timeline = nextTimeline,
        stableServerId = liveAssistant.serverId,
    )
}
