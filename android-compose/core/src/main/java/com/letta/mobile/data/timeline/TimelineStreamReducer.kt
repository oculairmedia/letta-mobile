package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.util.Telemetry

internal data class TimelineReducerInput(
    val prev: Timeline,
    val frame: LettaMessage,
    val pendingToolReturnsByCallId: Map<String, ToolReturnMessage>,
)

internal data class TimelineReducerOutput(
    val next: Timeline,
    val updatedPendingToolReturnsByCallId: Map<String, ToolReturnMessage>,
    val emittedEvents: List<TimelineSyncEvent>,
    val notification: PendingIngestNotification?,
)

internal fun reduceStreamFrame(input: TimelineReducerInput): TimelineReducerOutput {
    var timeline = input.prev
    val pendingToolReturnsByCallId = LinkedHashMap(input.pendingToolReturnsByCallId)
    val pendingEvents = mutableListOf<TimelineSyncEvent>()
    val conversationId = input.prev.conversationId
    val message = input.frame

    fun output(notification: PendingIngestNotification? = null): TimelineReducerOutput =
        TimelineReducerOutput(
            next = timeline,
            updatedPendingToolReturnsByCallId = pendingToolReturnsByCallId.toMap(linkedMapOf()),
            emittedEvents = pendingEvents.toList(),
            notification = notification,
        )

    // Telemetry side-effects are retained for observability; this reducer is
    // pure for state transformation purposes and safe to call from a hot fold.
    // A future bead can move telemetry into explicit output events if needed.
    if (message is ApprovalResponseMessage) {
        val reqId = message.approvalRequestId ?: return output()
        val match = timeline.events.firstOrNull {
            it is TimelineEvent.Confirmed && it.approvalRequestId == reqId
        } as? TimelineEvent.Confirmed ?: return output()
        if (match.approvalDecided) {
            Telemetry.event(
                "TimelineSync", "streamSubscriber.eventDeduped",
                "reason" to "approvalAlreadyDecided",
                "approvalRequestId" to reqId,
                "conversationId" to conversationId,
            )
            return output()
        }
        val updated = match.copy(approvalDecided = true)
        timeline = timeline.replaceByServerId(updated)
        pendingEvents += TimelineSyncEvent.StreamEventIngested(match.serverId, message.messageType)
        return output()
    }

    if (message is ToolReturnMessage) {
        val tcid = message.toolCallId
        Telemetry.event(
            "TimelineSync", "toolReturn.observed",
            "toolCallId" to (tcid ?: "<null>"),
            "hasBody" to (message.toolReturn.funcResponse?.isNotEmpty() == true),
            "timelineSize" to timeline.events.size,
        )
        if (tcid != null) {
            val match = timeline.events.firstOrNull { ev ->
                ev is TimelineEvent.Confirmed &&
                    ev.toolCalls.any { it.effectiveId == tcid }
            } as? TimelineEvent.Confirmed
            if (match != null) {
                val body = message.toolReturn.funcResponse ?: ""
                val isError = message.isErr == true || message.status == "error"
                val contentByCallId = match.toolReturnContentByCallId + (tcid to body)
                val updated = match.copy(
                    approvalDecided = true,
                    toolReturnContent = body.ifBlank { match.toolReturnContent ?: body },
                    toolReturnIsError = isError,
                    toolReturnContentByCallId = contentByCallId,
                    toolReturnIsErrorByCallId = match.toolReturnIsErrorByCallId + (tcid to isError),
                )
                timeline = timeline.replaceByServerId(updated)
                pendingEvents += TimelineSyncEvent.StreamEventIngested(match.serverId, message.messageType)
                Telemetry.event(
                    "TimelineSync", "toolReturn.attached",
                    "serverId" to match.serverId,
                    "bodyLen" to body.length,
                )
                val mt = message.messageType
                if (mt == "tool_return_message" && body.isNotBlank()) {
                    return output(
                        PendingIngestNotification(
                            serverId = match.serverId,
                            messageType = mt,
                            contentPreview = body.take(140),
                        )
                    )
                }
            } else {
                pendingToolReturnsByCallId[tcid] = message
                Telemetry.event(
                    "TimelineSync", "toolReturn.noMatch",
                    "toolCallId" to tcid,
                    "timelineSize" to timeline.events.size,
                )
            }
        }
        return output()
    }

    val confirmed = message.toTimelineEvent(position = timeline.nextLocalPosition())
    if (confirmed == null) {
        Telemetry.event(
            "TimelineSync", "streamSubscriber.toTimelineEventNull",
            "messageType" to message.messageType,
            "messageId" to message.id,
            "conversationId" to conversationId,
        )
        return output()
    }

    val existing = timeline.findByServerId(confirmed.serverId, confirmed.messageType)
    if (existing != null) {
        if (existing.hasAlreadyIngestedStreamFrame(confirmed)) {
            Telemetry.event(
                "TimelineSync", "streamSubscriber.duplicateSeqSkipped",
                "serverId" to confirmed.serverId,
                "messageType" to message.messageType,
                "existingSeqId" to existing.seqId,
                "incomingSeqId" to confirmed.seqId,
                "conversationId" to conversationId,
            )
            return output()
        }
        val oldText = existing.content
        val newText = confirmed.content
        val canUseSnapshotMerge = existing.seqId != null && confirmed.seqId != null
        val textMerge = mergeStreamText(
            existing = oldText,
            incoming = newText,
            canUseSnapshotMerge = canUseSnapshotMerge,
        )
        val mergedText = textMerge.text
        val oldCalls = existing.toolCalls
        val newCalls = confirmed.toolCalls
        val oldScore = oldCalls.count { !it.arguments.isNullOrBlank() }
        val newScore = newCalls.count { !it.arguments.isNullOrBlank() }
        val mergedCalls = if (newCalls.isEmpty() && oldCalls.isNotEmpty()) oldCalls
            else if (oldCalls.isEmpty()) newCalls
            else if (newScore >= oldScore) newCalls
            else oldCalls
        val merged = applyPendingToolReturns(
            confirmed.copy(
                content = mergedText,
                toolCalls = mergedCalls,
                approvalDecided = existing.approvalDecided || confirmed.approvalDecided,
                toolReturnContent = confirmed.toolReturnContent ?: existing.toolReturnContent,
                toolReturnIsError = confirmed.toolReturnIsError || existing.toolReturnIsError,
                toolReturnContentByCallId = existing.toolReturnContentByCallId + confirmed.toolReturnContentByCallId,
                toolReturnIsErrorByCallId = existing.toolReturnIsErrorByCallId + confirmed.toolReturnIsErrorByCallId,
                approvalRequestId = confirmed.approvalRequestId ?: existing.approvalRequestId,
                source = existing.source,
                seqId = latestSeqId(existing.seqId, confirmed.seqId),
            ),
            pendingToolReturnsByCallId,
        )
        timeline = timeline.replaceByServerId(merged)
        timeline = timeline.copy(liveCursor = confirmed.serverId)
        pendingEvents += TimelineSyncEvent.StreamEventIngested(confirmed.serverId, message.messageType)
        Telemetry.event(
            "TimelineSync", "streamSubscriber.merged",
            "serverId" to confirmed.serverId,
            "messageType" to message.messageType,
            "existingType" to existing.messageType.name,
            "oldLen" to oldText.length,
            "newLen" to newText.length,
            "mergedLen" to mergedText.length,
            "mergeBranch" to textMerge.branch.name,
            "mergeGarbleRisk" to textMerge.garbleRisk,
            "oldToolCalls" to oldCalls.size,
            "newToolCalls" to newCalls.size,
            "mergedToolCalls" to mergedCalls.size,
            "conversationId" to conversationId,
        )
        textMerge.defensiveTelemetryName()?.let { eventName ->
            Telemetry.event(
                "TimelineSync", eventName,
                "serverId" to confirmed.serverId,
                "messageType" to message.messageType,
                "oldLen" to oldText.length,
                "newLen" to newText.length,
                "mergedLen" to mergedText.length,
                "conversationId" to conversationId,
            )
        }
        return output()
    }

    if (timeline.findByOtid(confirmed.otid) != null) {
        Telemetry.event(
            "TimelineSync", "streamSubscriber.eventDeduped",
            "reason" to "otidSeen",
            "otid" to confirmed.otid,
            "conversationId" to conversationId,
        )
        return output()
    }

    timeline = timeline.append(applyPendingToolReturns(confirmed, pendingToolReturnsByCallId))
    timeline = timeline.copy(liveCursor = confirmed.serverId)
    pendingEvents += TimelineSyncEvent.StreamEventIngested(confirmed.serverId, message.messageType)
    Telemetry.event(
        "TimelineSync", "streamSubscriber.ingested",
        "serverId" to confirmed.serverId,
        "messageType" to message.messageType,
        "conversationId" to conversationId,
    )
    val mt = message.messageType
    return output(
        if (mt == "assistant_message" || mt == "tool_return_message") {
            PendingIngestNotification(
                serverId = confirmed.serverId,
                messageType = mt,
                contentPreview = confirmed.content.take(140).ifBlank { null },
            )
        } else {
            null
        }
    )
}

private fun StreamTextMergeResult.defensiveTelemetryName(): String? = when (branch) {
    StreamTextMergeBranch.CUMULATIVE -> "streamSubscriber.cumulativeSnapshotReplaced"
    StreamTextMergeBranch.STALE -> "streamSubscriber.staleFrameDropped"
    StreamTextMergeBranch.SUFFIX_DUPLICATE -> "streamSubscriber.endsWithDropped"
    StreamTextMergeBranch.EMPTY_INCOMING,
    StreamTextMergeBranch.EQUAL,
    StreamTextMergeBranch.APPEND -> null
}

/**
 * Attach any tool_return frames that arrived before their tool_call frame.
 */
private fun applyPendingToolReturns(
    ev: TimelineEvent.Confirmed,
    pendingToolReturnsByCallId: LinkedHashMap<String, ToolReturnMessage>,
): TimelineEvent.Confirmed {
    if (ev.messageType != TimelineMessageType.TOOL_CALL || ev.toolCalls.isEmpty()) return ev
    val matchingReturns = ev.toolCalls.mapNotNull { toolCall ->
        val callId = toolCall.effectiveId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val toolReturn = pendingToolReturnsByCallId.remove(callId) ?: return@mapNotNull null
        callId to toolReturn
    }
    if (matchingReturns.isEmpty()) return ev
    val firstReturn = matchingReturns.first().second
    val returnContentByCallId = ev.toolReturnContentByCallId + matchingReturns.mapNotNull { (callId, toolReturn) ->
        toolReturn.toolReturn.funcResponse?.let { callId to it }
    }.toMap()
    val returnIsErrorByCallId = ev.toolReturnIsErrorByCallId + matchingReturns.associate { (callId, toolReturn) ->
        callId to (toolReturn.isErr == true || toolReturn.status == "error")
    }
    Telemetry.event(
        "TimelineSync", "toolReturn.attachedPending",
        "serverId" to ev.serverId,
        "count" to matchingReturns.size,
    )
    return ev.copy(
        approvalDecided = true,
        toolReturnContent = firstReturn.toolReturn.funcResponse ?: ev.toolReturnContent,
        toolReturnIsError = firstReturn.isErr == true || firstReturn.status == "error" || ev.toolReturnIsError,
        toolReturnContentByCallId = returnContentByCallId,
        toolReturnIsErrorByCallId = returnIsErrorByCallId,
    )
}
