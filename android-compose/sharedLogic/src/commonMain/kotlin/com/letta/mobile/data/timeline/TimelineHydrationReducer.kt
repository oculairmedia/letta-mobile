package com.letta.mobile.data.timeline

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.SyntheticSkillEnvelopeDetector
import com.letta.mobile.util.Telemetry

@Immutable
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
        val existingConfirmed = (timelineBeforeFetch.events + currentTimeline.events)
            .filterIsInstance<TimelineEvent.Confirmed>()
        val serverEvents = serverMessagesChronological.toHydratedServerEvents(existingConfirmed)
        val preserved = preservedEvents(
            timelineBeforeFetch = timelineBeforeFetch,
            currentTimeline = currentTimeline,
            converted = serverEvents,
            diskRecords = diskRecords,
        )
        val baseEvents = (preserved.olderConfirmed + serverEvents).withPositions()
        val merged = baseEvents.appendWithPositions(preserved.runtimeAndDisk)
        val deduped = dedupeByOtid(conversationId, merged)
        return HydratedTimelineResult(
            timeline = Timeline(
                conversationId = conversationId,
                events = deduped.toTimelinePersistentList(),
                liveCursor = serverEvents.lastOrNull()?.serverId ?: timelineBeforeFetch.liveCursor ?: currentTimeline.liveCursor,
                backfillCursor = preserved.olderConfirmed.firstOrNull()?.serverId ?: serverEvents.firstOrNull()?.serverId ?: timelineBeforeFetch.backfillCursor ?: currentTimeline.backfillCursor,
                releasedOlderCount = maxOf(timelineBeforeFetch.releasedOlderCount, currentTimeline.releasedOlderCount),
            ),
            visibleEventCount = serverEvents.size + preserved.olderConfirmed.size,
        )
    }

    private fun List<LettaMessage>.toHydratedServerEvents(
        existing: List<TimelineEvent.Confirmed>,
    ): List<TimelineEvent.Confirmed> {
        val displayable = filterNot(SyntheticSkillEnvelopeDetector::isSyntheticSkillEnvelope)
        val converted = displayable.mapIndexedNotNull { index, message ->
            message.toTimelineEvent(position = (index + 1).toDouble())
        }
        return attachToolReturnsAndDropStandaloneReturns(displayable, converted).mergeWith(existing)
    }

    private fun preservedEvents(
        timelineBeforeFetch: Timeline,
        currentTimeline: Timeline,
        converted: List<TimelineEvent.Confirmed>,
        diskRecords: List<PendingLocalRecord>,
    ): PreservedEvents {
        val convertedKeys = converted.flatMap { it.identityKeys() }.toHashSet()
        val initialKeys = timelineBeforeFetch.events.flatMap { it.identityKeys() }.toHashSet()
        val oldestServerDate = converted.firstOrNull()?.date
        val (olderConfirmed, newerConfirmed) = if (oldestServerDate != null) {
            timelineBeforeFetch.events.filterIsInstance<TimelineEvent.Confirmed>()
                .filter { it.identityKeys().none(convertedKeys::contains) }
                .partition { compareTimelineInstants(it.date, oldestServerDate) < 0 }
        } else {
            val unmatched = timelineBeforeFetch.events.filterIsInstance<TimelineEvent.Confirmed>()
                .filter { it.identityKeys().none(convertedKeys::contains) }
            Pair(unmatched, emptyList())
        }
        val pendingLocals = currentTimeline.events.filterIsInstance<TimelineEvent.Local>()
            .filter { it.deliveryState.isPendingOrRestorable() }
            .filter { local -> converted.none { it.otid == local.otid } }
            // letta-mobile-x13xi.13.1.1: cold hydration re-introduced a SENDING
            // Local as a duplicate bubble whenever the server echoed the user
            // message back with a different otid. otid-only dedup at line 85
            // cannot catch this (different otids), and identityKeys() only adds
            // serverId/semantic keys for Confirmed events — never Local. Drop
            // the Local whenever the server snapshot contains a USER message
            // with matching content within the recency window; the Confirmed
            // row will project the same bubble and SENDING would otherwise
            // stay stuck forever (no later stream event references the local
            // otid, so markSent never fires).
            .filter { local -> converted.none { server -> local.matchesConfirmedUser(server) } }
        val concurrentConfirmed = currentTimeline.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.identityKeys().none(initialKeys::contains) }
            .filter { it.identityKeys().none(convertedKeys::contains) }
        val knownOtids = (converted + pendingLocals + olderConfirmed + newerConfirmed).mapTo(HashSet()) { it.otid }
        val diskLocals = diskRecords.filter { it.otid !in knownOtids }.map { it.toSentEvent() }
        return PreservedEvents(olderConfirmed, newerConfirmed + (pendingLocals + concurrentConfirmed).sortedBy { it.position } + diskLocals)
    }

    private fun List<TimelineEvent.Confirmed>.mergeWith(
        existing: List<TimelineEvent.Confirmed>,
    ): List<TimelineEvent.Confirmed> {
        val byServerId = existing.associateBy { it.serverId }
        val byOtid = existing.associateBy { it.otid }
        return map { event ->
            (byServerId[event.serverId] ?: byOtid[event.otid])?.let { mergeRicherEventFacts(event, it) } ?: event
        }
    }

    private fun List<TimelineEvent.Confirmed>.withPositions(): List<TimelineEvent.Confirmed> =
        mapIndexed { index, event -> event.copy(position = (index + 1).toDouble()) }

    private fun List<TimelineEvent.Confirmed>.appendWithPositions(
        preserved: List<TimelineEvent>,
    ): List<TimelineEvent> {
        val startPosition = lastOrNull()?.position ?: 0.0
        return this + preserved.mapIndexed { index, event ->
            when (event) {
                is TimelineEvent.Local -> event.copy(position = startPosition + index + 1)
                is TimelineEvent.Confirmed -> event.copy(position = startPosition + index + 1)
            }
        }
    }

    private fun PendingLocalRecord.toSentEvent(): TimelineEvent.Local = TimelineEvent.Local(
        position = 0.0,
        otid = otid,
        content = content,
        role = Role.USER,
        sentAt = sentAt,
        deliveryState = DeliveryState.SENT,
        attachments = attachments.toTimelinePersistentList(),
    )

    private data class PreservedEvents(
        val olderConfirmed: List<TimelineEvent.Confirmed>,
        val runtimeAndDisk: List<TimelineEvent>,
    )

    internal fun mergeRicherEventFacts(
        serverEvent: TimelineEvent.Confirmed,
        localEvent: TimelineEvent.Confirmed,
    ): TimelineEvent.Confirmed {
        val mergedApprovalDecided = serverEvent.approvalDecided || localEvent.approvalDecided
        val mergedApprovalDecision = serverEvent.approvalDecision ?: localEvent.approvalDecision

        val mergedToolReturnContentByCallId = (serverEvent.toolReturnContentByCallId + localEvent.toolReturnContentByCallId.filter { (callId, _) ->
            callId !in localEvent.toolReturnTruncationByCallId || callId in serverEvent.toolReturnTruncationByCallId
        }).toTimelinePersistentMap()

        val mergedTruncations = (serverEvent.toolReturnTruncationByCallId.filterKeys {
            it !in localEvent.toolReturnContentByCallId || it in localEvent.toolReturnTruncationByCallId
        }).toTimelinePersistentMap()

        val mergedToolReturnContent = localEvent.toolReturnContent.takeIf { !it.isNullOrBlank() } ?: serverEvent.toolReturnContent
        val mergedAttachments = if (serverEvent.attachments.isEmpty()) localEvent.attachments else serverEvent.attachments

        return serverEvent.copy(
            approvalDecided = mergedApprovalDecided,
            approvalDecision = mergedApprovalDecision,
            toolReturnContent = mergedToolReturnContent,
            toolReturnContentByCallId = mergedToolReturnContentByCallId,
            toolReturnTruncationByCallId = mergedTruncations,
            attachments = mergedAttachments,
        )
    }

    private fun dedupeByOtid(
        conversationId: String,
        events: List<TimelineEvent>,
    ): List<TimelineEvent> {
        val seenOtids = HashSet<String>()
        // letta-mobile: the server snapshot itself can carry the SAME logical
        // assistant/reasoning/tool_call message twice — same run_id + same
        // content but a different server message id and a non-colliding otid
        // (e.g. when a run is replayed on cold start after a rebuild). otid
        // dedup alone keeps both rows, producing a doubled bubble in the UI.
        // Also collapse by semantic identity key (type:run_id:content), the
        // same key the stream reducer uses to dedupe a hydrate-then-stream
        // re-delivery. Empty keys (no run_id / user / tool_return) fall back to
        // otid-only behaviour so distinct messages are never merged.
        val seenSemantic = HashSet<String>()
        val deduped = events.filter { event ->
            val otidNovel = seenOtids.add(event.otid)
            val semanticKey = (event as? TimelineEvent.Confirmed)
                ?.semanticIdentityKeyOrNull()
            if (semanticKey == null) {
                otidNovel
            } else {
                val semanticNovel = seenSemantic.add(semanticKey)
                otidNovel && semanticNovel
            }
        }
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
        val evidence = approvalTimelineEvidence(serverMessages)
        return rawConverted.mapNotNull { ev ->
            when (ev.messageType) {
                TimelineMessageType.TOOL_RETURN -> null
                TimelineMessageType.TOOL_CALL -> ev.withHydratedToolReturns(
                    evidence = evidence,
                )
                else -> ev
            }
        }
    }

    private fun DeliveryState.isPendingOrRestorable(): Boolean {
        return this == DeliveryState.SENDING || this == DeliveryState.SENT || this == DeliveryState.FAILED
    }

    /**
     * letta-mobile-x13xi.13.1.1: true when [server] is a Confirmed USER row
     * that semantically represents the same user message as this pending
     * Local. Matches regardless of the server's otid, because hydration
     * snapshots are written by the server under its own otid even when the
     * server-side record originated from a Local message whose otid was
     * preserved or rewritten in transit. Recency is bounded by
     * [CONTENT_FALLBACK_RECENCY_MS] so that old SENDING/SENT Locals cannot
     * be rewritten by a same-content server message replayed long after
     * the original send.
     */
    private fun TimelineEvent.Local.matchesConfirmedUser(
        server: TimelineEvent.Confirmed,
    ): Boolean {
        if (role != Role.USER) return false
        if (server.messageType != TimelineMessageType.USER) return false
        if (server.content.trim() != content.trim()) return false
        val ageMillis = timelineInstantDurationMillis(sentAt, server.date)
        return ageMillis in 0..CONTENT_FALLBACK_RECENCY_MS
    }

    private fun TimelineEvent.Confirmed.withHydratedToolReturns(
        evidence: ApprovalTimelineEvidence,
    ): TimelineEvent.Confirmed {
        val byResponse = hasAnyApprovalResponse(evidence)
        val matchingReturns = matchingToolReturns(evidence)
        val byReturn = if (approvalRequestId == null) matchingReturns.isNotEmpty()
            else allApprovalCallsReturned(matchingReturns)
        val matchingReturn = matchingReturns.firstOrNull()?.second
        // letta-mobile-fe51r: shared fold keeps projected previews from
        // clobbering full bodies and tracks truncation markers per call id.
        val fold = foldToolReturnBodies(toolReturnContentByCallId, toolReturnTruncationByCallId, matchingReturns)
        val returnIsErrorByCallId = toolReturnIsErrorByCallId + matchingReturns.associate { (callId, toolReturn) ->
            callId to (toolReturn.isErr == true || toolReturn.status == "error")
        }
        val firstCallId = matchingReturns.firstOrNull()?.first
        return copy(
            approvalDecided = byResponse || byReturn || approvalDecided,
            // letta-mobile-c49of: thread an explicit decision from hydration
            // evidence so a REJECTED request projects Rejected after reload.
            approvalDecision = approvalOutcomeFromEvidence(evidence) ?: approvalDecision,
            toolReturnContent = firstCallId?.let { fold.contentByCallId[it] } ?: toolReturnContent,
            toolReturnIsError = matchingReturn?.let { it.isErr == true || it.status == "error" } ?: toolReturnIsError,
            toolReturnContentByCallId = fold.contentByCallId.toTimelinePersistentMap(),
            toolReturnIsErrorByCallId = returnIsErrorByCallId.toTimelinePersistentMap(),
            toolReturnTruncationByCallId = fold.truncationByCallId.toTimelinePersistentMap(),
            attachments = (attachments + matchingReturns.flatMap { (_, toolReturn) -> toolReturn.attachments }).distinct().toTimelinePersistentList(),
        )
    }
}

internal fun TimelineEvent.identityKeys(): Set<String> {
    val keys = mutableSetOf("otid:$otid")
    if (this is TimelineEvent.Confirmed) {
        val stableRunId = runId?.takeIf { it.isNotBlank() }
        keys += if (stableRunId == null) {
            "server:$serverId:$messageType"
        } else {
            "server:$serverId:$messageType:run:$stableRunId"
        }
        semanticIdentityKeyOrNull()?.let { keys += it }
    }
    return keys
}

private fun TimelineEvent.Confirmed.semanticIdentityKeyOrNull(): String? {
    val stableRunId = runId?.takeIf { it.isNotBlank() } ?: return null
    return when (messageType) {
        TimelineMessageType.ASSISTANT,
        TimelineMessageType.REASONING,
        TimelineMessageType.TOOL_CALL,
        TimelineMessageType.ERROR -> "semantic:${messageType.name}:$stableRunId:${content.trim()}"
        // Hydrated history can expose one logical invocation twice: once as a
        // tool_call_message and once as an approval_request_message. Their
        // server ids and rendered content can differ, but the call id is the
        // canonical invocation identity used by the matching tool return.
        TimelineMessageType.TOOL_CALL -> toolCalls
            .map { it.effectiveId }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.sorted()
            ?.joinToString(",")
            ?.let { "semantic:${messageType.name}:$stableRunId:callIds:$it" }
            ?: "semantic:${messageType.name}:$stableRunId:${content.trim()}"
        TimelineMessageType.USER,
        TimelineMessageType.TOOL_RETURN,
        TimelineMessageType.SYSTEM,
        TimelineMessageType.OTHER -> null
    }
}
