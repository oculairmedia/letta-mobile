package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.util.Telemetry

data class HydratedTimelineResult(
    val timeline: Timeline,
    val visibleEventCount: Int,
)

/**
 * Pure state reducer for initial history hydration.
 *
 * Network fetches and Room/disk reads stay in [TimelineSyncLoop]; this reducer
 * owns the deterministic transformation from a server snapshot + preserved
 * local state into the replacement [Timeline]. Keeping this out of the sync
 * loop makes the write-lock boundary explicit: callers fetch first, then invoke
 * this reducer while holding the timeline mutation lock.
 */
object TimelineHydrationReducer {
    fun reduce(
        conversationId: String,
        serverMessagesChronological: List<LettaMessage>,
        timelineBeforeFetch: Timeline,
        currentTimeline: Timeline,
        diskRecords: List<PendingLocalRecord>,
    ): HydratedTimelineResult {
        val rawConverted = serverMessagesChronological.mapIndexedNotNull { idx, msg ->
            msg.toTimelineEvent(position = (idx + 1).toDouble())
        }
        val converted = attachToolReturnsAndDropStandaloneReturns(
            serverMessages = serverMessagesChronological,
            rawConverted = rawConverted,
        )
        val pendingLocals = currentTimeline.events.filterIsInstance<TimelineEvent.Local>()
            .filter { it.deliveryState.isPendingOrRestorable() }
            .filter { local -> converted.none { c -> c.otid == local.otid } }
        val initialKeys = timelineBeforeFetch.events.flatMap { it.identityKeys() }.toHashSet()
        val convertedKeys = converted.flatMap { it.identityKeys() }.toHashSet()
        val concurrentConfirmed = currentTimeline.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { event -> event.identityKeys().none { it in initialKeys } }
            .filter { event -> event.identityKeys().none { it in convertedKeys } }
        val knownOtids = (converted.map { it.otid } + pendingLocals.map { it.otid }).toHashSet()
        val diskLocals = diskRecords
            .filter { it.otid !in knownOtids }
            .map { rec ->
                TimelineEvent.Local(
                    position = 0.0,
                    otid = rec.otid,
                    content = rec.content,
                    role = Role.USER,
                    sentAt = rec.sentAt,
                    deliveryState = DeliveryState.SENT,
                    attachments = rec.attachments,
                )
            }
        val maxServerPos = converted.lastOrNull()?.position ?: 0.0
        val runtimePreserved = (pendingLocals + concurrentConfirmed).sortedBy { it.position }
        val allPreserved = runtimePreserved + diskLocals
        val merged = converted + allPreserved.mapIndexed { idx, event ->
            val position = maxServerPos + (idx + 1).toDouble()
            when (event) {
                is TimelineEvent.Local -> event.copy(position = position)
                is TimelineEvent.Confirmed -> event.copy(position = position)
            }
        }
        val deduped = dedupeByOtid(
            conversationId = conversationId,
            events = merged,
        )
        return HydratedTimelineResult(
            timeline = Timeline(
                conversationId = conversationId,
                events = deduped,
                liveCursor = converted.lastOrNull()?.serverId,
            ),
            visibleEventCount = converted.size,
        )
    }

    private fun dedupeByOtid(
        conversationId: String,
        events: List<TimelineEvent>,
    ): List<TimelineEvent> {
        val seen = HashSet<String>()
        val deduped = events.filter { event -> seen.add(event.otid) }
        if (deduped.size != events.size) {
            Telemetry.event(
                "Timeline", "hydrate.duplicateOtidDropped",
                "conversationId" to conversationId,
                "eventCount" to events.size,
                "dedupedCount" to deduped.size,
                level = Telemetry.Level.WARN,
            )
        }
        return deduped
    }

    private fun attachToolReturnsAndDropStandaloneReturns(
        serverMessages: List<LettaMessage>,
        rawConverted: List<TimelineEvent.Confirmed>,
    ): List<TimelineEvent.Confirmed> {
        val decidedIds = serverMessages.filterIsInstance<ApprovalResponseMessage>()
            .mapNotNull { it.approvalRequestId }
            .toSet()
        val toolReturnsByCallId: Map<String, ToolReturnMessage> =
            serverMessages.filterIsInstance<ToolReturnMessage>()
                .mapNotNull { tr -> tr.toolCallId?.takeIf { it.isNotBlank() }?.let { it to tr } }
                .toMap()
        val returnedToolCallIds = toolReturnsByCallId.keys
        return rawConverted.mapNotNull { ev ->
            when (ev.messageType) {
                TimelineMessageType.TOOL_RETURN -> null
                TimelineMessageType.TOOL_CALL -> ev.withHydratedToolReturns(
                    decidedIds = decidedIds,
                    returnedToolCallIds = returnedToolCallIds,
                    toolReturnsByCallId = toolReturnsByCallId,
                )
                else -> ev
            }
        }
    }

    private fun DeliveryState.isPendingOrRestorable(): Boolean {
        return this == DeliveryState.SENDING || this == DeliveryState.SENT || this == DeliveryState.FAILED
    }

    private fun TimelineEvent.Confirmed.withHydratedToolReturns(
        decidedIds: Set<String>,
        returnedToolCallIds: Set<String>,
        toolReturnsByCallId: Map<String, ToolReturnMessage>,
    ): TimelineEvent.Confirmed {
        val byResponse = approvalRequestId != null && approvalRequestId in decidedIds
        val byReturn = toolCalls.any { toolCall ->
            toolCall.effectiveId.takeIf { it.isNotBlank() }?.let { it in returnedToolCallIds } == true
        }
        val matchingReturns = toolCalls.mapNotNull { tc ->
            val callId = tc.effectiveId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val toolReturn = toolReturnsByCallId[callId] ?: return@mapNotNull null
            callId to toolReturn
        }
        val matchingReturn = matchingReturns.firstOrNull()?.second
        val returnContentByCallId = toolReturnContentByCallId + matchingReturns.mapNotNull { (callId, toolReturn) ->
            toolReturn.toolReturn.funcResponse?.let { callId to it }
        }.toMap()
        val returnIsErrorByCallId = toolReturnIsErrorByCallId + matchingReturns.associate { (callId, toolReturn) ->
            callId to (toolReturn.isErr == true || toolReturn.status == "error")
        }
        return copy(
            approvalDecided = byResponse || byReturn || approvalDecided,
            toolReturnContent = matchingReturn?.toolReturn?.funcResponse ?: toolReturnContent,
            toolReturnIsError = matchingReturn?.let { it.isErr == true || it.status == "error" } ?: toolReturnIsError,
            toolReturnContentByCallId = returnContentByCallId,
            toolReturnIsErrorByCallId = returnIsErrorByCallId,
        )
    }
}

internal fun TimelineEvent.identityKeys(): Set<String> {
    val keys = mutableSetOf("otid:$otid")
    if (this is TimelineEvent.Confirmed) {
        keys += "server:$serverId:$messageType"
        semanticIdentityKeyOrNull()?.let { keys += it }
    }
    return keys
}

fun Timeline.containsIdentityFor(event: TimelineEvent): Boolean {
    val incomingKeys = event.identityKeys()
    return events.any { existing ->
        existing.identityKeys().any { it in incomingKeys }
    }
}

private fun TimelineEvent.Confirmed.semanticIdentityKeyOrNull(): String? {
    val stableRunId = runId?.takeIf { it.isNotBlank() } ?: return null
    return when (messageType) {
        TimelineMessageType.ASSISTANT,
        TimelineMessageType.REASONING,
        TimelineMessageType.TOOL_CALL,
        TimelineMessageType.ERROR -> "semantic:${messageType.name}:$stableRunId:${content.trim()}"
        TimelineMessageType.USER,
        TimelineMessageType.TOOL_RETURN,
        TimelineMessageType.SYSTEM,
        TimelineMessageType.OTHER -> null
    }
}
