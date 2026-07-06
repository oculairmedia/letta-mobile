package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap

data class TimelineReducerInput(
    val prev: Timeline,
    val frame: LettaMessage,
    val pendingToolReturnsByCallId: PersistentMap<String, ToolReturnMessage>,
)

data class TimelineReducerOutput(
    val next: Timeline,
    val updatedPendingToolReturnsByCallId: PersistentMap<String, ToolReturnMessage>,
    val emittedEvents: PersistentList<TimelineSyncEvent>,
    val notification: PendingIngestNotification?,
)

fun reduceStreamFrame(input: TimelineReducerInput): TimelineReducerOutput {
    var timeline = input.prev
    val pendingToolReturnsByCallId = LinkedHashMap(input.pendingToolReturnsByCallId)
    val pendingEvents = mutableListOf<TimelineSyncEvent>()
    val conversationId = input.prev.conversationId
    val message = input.frame

    // FrameFlowDiag: content-length at the reducer INGEST gate. Compare against
    // gate1.emit (IrohChannelTransport) to locate where fragment characters are
    // dropped between transport emit and reducer ingest.
    if (com.letta.mobile.data.transport.iroh.IrohFrameFlowDiagnostics.enabled()) {
        when (message) {
            is AssistantMessage -> com.letta.mobile.data.transport.iroh.IrohFrameFlowDiagnostics.record(
                "gate.reduceIngest", message.otid ?: message.id, "assistant_message", message.content,
            )
            is ReasoningMessage -> com.letta.mobile.data.transport.iroh.IrohFrameFlowDiagnostics.record(
                "gate.reduceIngest", message.otid ?: message.id, "reasoning_message", message.reasoning,
            )
            else -> Unit
        }
    }

    fun output(notification: PendingIngestNotification? = null): TimelineReducerOutput =
        TimelineReducerOutput(
            next = timeline,
            updatedPendingToolReturnsByCallId = pendingToolReturnsByCallId.toTimelinePersistentMap(),
            emittedEvents = pendingEvents.toTimelinePersistentList(),
            notification = notification,
        )

    // Chat stream reducer diagnostics are opt-in because this reducer is called
    // once per streamed frame. Keep the transformation pure-ish and bounded by
    // default; enable Telemetry.chatHotPathDebugEnabled while investigating.
    if (message is ApprovalResponseMessage) {
        val reqId = message.approvalRequestId ?: return output()
        val match = timeline.events.firstOrNull {
            it is TimelineEvent.Confirmed && it.approvalRequestId == reqId
        } as? TimelineEvent.Confirmed ?: return output()
        if (match.approvalDecided) {
            hotPathTelemetry(
                "streamSubscriber.eventDeduped",
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
        hotPathTelemetry(
            "toolReturn.observed",
            "toolCallId" to (tcid ?: "<null>"),
            "hasBody" to (message.toolReturn.funcResponse?.isNotEmpty() == true),
            "timelineSize" to timeline.events.size,
        )
        if (!tcid.isNullOrBlank()) {
            val match = timeline.events.firstOrNull { ev ->
                ev is TimelineEvent.Confirmed &&
                    ev.toolCalls.any { it.effectiveId.takeIf { id -> id.isNotBlank() } == tcid }
            } as? TimelineEvent.Confirmed
            if (match != null) {
                val isError = message.isErr == true || message.status == "error"
                // letta-mobile-fe51r: shared fold keeps projected previews
                // from clobbering full bodies and tracks truncation markers.
                val fold = foldToolReturnBodies(
                    match.toolReturnContentByCallId,
                    match.toolReturnTruncationByCallId,
                    listOf(tcid to message),
                )
                // Preserve legacy behavior for returns without a func_response
                // (fold skips them): still mark the call complete with "".
                val contentByCallId =
                    if (tcid in fold.contentByCallId) fold.contentByCallId
                    else fold.contentByCallId + (tcid to "")
                val body = contentByCallId.getValue(tcid)
                val updated = match.copy(
                    approvalDecided = true,
                    toolReturnContent = body.ifBlank { match.toolReturnContent ?: body },
                    toolReturnIsError = isError,
                    toolReturnContentByCallId = contentByCallId.toTimelinePersistentMap(),
                    toolReturnIsErrorByCallId = (match.toolReturnIsErrorByCallId + (tcid to isError)).toTimelinePersistentMap(),
                    toolReturnTruncationByCallId = fold.truncationByCallId.toTimelinePersistentMap(),
                    attachments = (match.attachments + message.attachments).distinct().toTimelinePersistentList(),
                )
                timeline = timeline.replaceByServerId(updated)
                pendingEvents += TimelineSyncEvent.StreamEventIngested(match.serverId, message.messageType)
                hotPathTelemetry(
                    "toolReturn.attached",
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
                hotPathTelemetry(
                    "toolReturn.noMatch",
                    "toolCallId" to tcid,
                    "timelineSize" to timeline.events.size,
                )
            }
        }
        return output()
    }

    val confirmed = message.toTimelineEvent(position = timeline.nextLocalPosition())
    if (confirmed == null) {
        hotPathTelemetry(
            "streamSubscriber.toTimelineEventNull",
            "messageType" to message.messageType,
            "messageId" to message.id,
            "conversationId" to conversationId,
        )
        return output()
    }

    val existing = timeline.findByServerId(confirmed.serverId, confirmed.messageType)
        ?.takeIf { it.canMergeStreamFrame(confirmed) }
    if (existing != null) {
        val syntheticLiveToRealFinal = existing.hasIrohSyntheticRunId() && !confirmed.hasIrohSyntheticRunId()
        if (!syntheticLiveToRealFinal && existing.hasAlreadyIngestedStreamFrame(confirmed)) {
            hotPathTelemetry(
                "streamSubscriber.duplicateSeqSkipped",
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
        // T5 canonical ids: the transport promotes a synthetic `iroh-run-*` row
        // to the real server run id mid-stream (letta-mobile P3). A promoted
        // frame that ALSO carries a strictly-higher seq id than the row we hold
        // is a genuine forward stream delta — NOT the reconcile snapshot the
        // synthetic->real path was originally written for. Treating it as a
        // snapshot keep-longer would drop appended tokens, so route it through
        // the normal forward-delta merge instead. The message.list reconcile
        // final carries no seq id, so it stays on the snapshot path unchanged.
        val promotedForwardDelta = syntheticLiveToRealFinal &&
            existing.seqId != null && confirmed.seqId != null && confirmed.seqId > existing.seqId
        val snapshotReplacement = syntheticLiveToRealFinal && !promotedForwardDelta
        val canUseSnapshotMerge = snapshotReplacement || (existing.seqId != null && confirmed.seqId != null)
        // letta-mobile-k9y5d: a frame is a forward (newer) delta only when its
        // seq id is strictly greater than the text we already hold. A frame with
        // a lower-or-equal seq id is a replayed / out-of-order re-delivery and
        // must never append or drop a prefix of the complete text. When seq ids
        // are absent we keep the historical append behaviour (treat as forward),
        // except for the Iroh synthetic-live -> real-final replacement path: the
        // reconciled final is a snapshot, not another text delta.
        val incomingIsForwardDelta = !snapshotReplacement &&
            (existing.seqId == null || confirmed.seqId == null || confirmed.seqId > existing.seqId)
        val textMerge = if (snapshotReplacement) {
            StreamTextMergeResult(
                text = newText.ifBlank { oldText },
                branch = StreamTextMergeBranch.SNAPSHOT_CONFLICT,
                garbleRisk = false,
            )
        } else {
            mergeStreamText(
                existing = oldText,
                incoming = newText,
                canUseSnapshotMerge = canUseSnapshotMerge,
                incomingIsForwardDelta = incomingIsForwardDelta,
            )
        }
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
                toolReturnContentByCallId = (existing.toolReturnContentByCallId + confirmed.toolReturnContentByCallId).toTimelinePersistentMap(),
                toolReturnIsErrorByCallId = (existing.toolReturnIsErrorByCallId + confirmed.toolReturnIsErrorByCallId).toTimelinePersistentMap(),
                toolReturnTruncationByCallId = (existing.toolReturnTruncationByCallId + confirmed.toolReturnTruncationByCallId).toTimelinePersistentMap(),
                approvalRequestId = confirmed.approvalRequestId ?: existing.approvalRequestId,
                attachments = (existing.attachments + confirmed.attachments).distinct().toTimelinePersistentList(),
                source = existing.source,
                seqId = latestSeqId(existing.seqId, confirmed.seqId),
            ),
            pendingToolReturnsByCallId,
        )
        timeline = timeline.replaceByServerId(merged)
        timeline = timeline.copy(liveCursor = confirmed.serverId)
        pendingEvents += TimelineSyncEvent.StreamEventIngested(confirmed.serverId, message.messageType)
        hotPathTelemetry(
            "streamSubscriber.merged",
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
            hotPathTelemetry(
                eventName,
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

    val otidMatch = timeline.findByOtid(confirmed.otid) as? TimelineEvent.Confirmed
    if (otidMatch != null) {
        // App Server assistant frames for one logical reply share a stable otid,
        // while backend server ids may rotate per chunk. The chunk body can be a
        // cumulative snapshot ("Got" -> "Got it") or an incremental token stream
        // ("I" -> "'m" -> " Lester"), but seq ids still tell us whether an
        // incoming frame is forward progress. Merge forward same-otid assistant
        // text into the existing row instead of dropping it as an otid duplicate;
        // distinct assistant messages carry distinct otids, so tool-mediated
        // multi-assistant runs stay separate.
        val bothAssistant = otidMatch.messageType == TimelineMessageType.ASSISTANT &&
            confirmed.messageType == TimelineMessageType.ASSISTANT
        val merge = if (bothAssistant) {
            // Same-otid assistant frames with seq ids support both cumulative
            // snapshots and incremental tokens. Snapshot-merge remains enabled so
            // cumulative growth replaces the prior body before any append logic,
            // while incomingIsForwardDelta + incrementalForwardAppend ensures only
            // monotonic forward tokens bypass the STALE/SUFFIX drop branches.
            mergeStreamText(
                existing = otidMatch.content,
                incoming = confirmed.content,
                canUseSnapshotMerge = true,
                incomingIsForwardDelta = otidMatch.seqId == null || confirmed.seqId == null ||
                    confirmed.seqId > otidMatch.seqId,
                // h30cy: for monotonic forward incremental tokens, a
                // prefix/suffix byte coincidence is new text, not a stale resend;
                // true cumulative snapshots still route to CUMULATIVE above.
                incrementalForwardAppend = true,
            )
        } else {
            null
        }
        if (merge != null && merge.text != otidMatch.content) {
            val merged = otidMatch.copy(
                content = merge.text,
                seqId = latestSeqId(otidMatch.seqId, confirmed.seqId),
                // Codex review P2: per-chunk backend ids route through this otid
                // path, so the serverId-merge run-id promotion above never runs.
                // Adopt the incoming frame's real run id when it supersedes a
                // synthetic iroh-run-* id, so run-scoped grouping/collapse keys
                // on the real run id.
                runId = promoteRunId(otidMatch.runId, confirmed.runId),
            )
            timeline = timeline.replaceByServerId(merged)
            // Keep the row's stable serverId as identity, but advance liveCursor
            // to the just-ingested chunk id (codex review P2): reconcile uses
            // liveCursor as the `after` cursor, so leaving it on the first chunk
            // could make a long reply's reconcile miss later messages.
            timeline = timeline.copy(liveCursor = confirmed.serverId)
            pendingEvents += TimelineSyncEvent.StreamEventIngested(otidMatch.serverId, message.messageType)
            hotPathTelemetry(
                "streamSubscriber.otidCumulativeMerged",
                "otid" to confirmed.otid,
                "serverId" to otidMatch.serverId,
                "incomingServerId" to confirmed.serverId,
                "oldLen" to otidMatch.content.length,
                "newLen" to confirmed.content.length,
                "mergedLen" to merge.text.length,
                "mergeBranch" to merge.branch.name,
                "conversationId" to conversationId,
            )
            return output()
        }
        hotPathTelemetry(
            "streamSubscriber.eventDeduped",
            "reason" to "otidSeen",
            "otid" to confirmed.otid,
            "conversationId" to conversationId,
        )
        return output()
    }
    if (timeline.containsIdentityFor(confirmed)) {
        hotPathTelemetry(
            "streamSubscriber.eventDeduped",
            "reason" to "semanticIdentitySeen",
            "serverId" to confirmed.serverId,
            "messageType" to message.messageType,
            "conversationId" to conversationId,
        )
        return output()
    }

    val prefixOrphanTarget = timeline.findSameRunAssistantPrefixOrBlankTarget(confirmed)
    if (prefixOrphanTarget != null) {
        hotPathTelemetry(
            "streamSubscriber.assistantPrefixOrphanSkipped",
            "incomingServerId" to confirmed.serverId,
            "existingServerId" to prefixOrphanTarget.serverId,
            "runId" to (confirmed.runId ?: "<null>"),
            "incomingLen" to confirmed.content.length,
            "existingLen" to prefixOrphanTarget.content.length,
            "conversationId" to conversationId,
        )
        return output()
    }

    // letta-mobile-x1xnl: one-row-per-otid invariant. The App Server streams
    // assistant deltas with a NEW backend letta-msg-* id per chunk but a STABLE
    // otid. If — through a fanout/timing race — an increment reaches the append
    // path while a row for this otid already exists (the earlier findByOtid
    // merge above having been bypassed on a prior racing frame), appending would
    // create a SECOND assistant row for the same message that the user sees as a
    // stranded duplicate. Never let that happen: if a confirmed assistant row
    // already carries this otid, merge into it (keyed by its stable serverId)
    // instead of appending a new row. Distinct assistant messages carry distinct
    // otids, so tool-mediated multi-assistant runs stay separate.
    val otidRow = confirmed.otid
        .takeIf { it.isNotBlank() && confirmed.messageType == TimelineMessageType.ASSISTANT }
        ?.let { otid ->
            timeline.events.firstOrNull { ev ->
                ev is TimelineEvent.Confirmed &&
                    ev.messageType == TimelineMessageType.ASSISTANT &&
                    ev.otid == otid
            } as? TimelineEvent.Confirmed
        }
    if (otidRow != null) {
        val merge = mergeStreamText(
            existing = otidRow.content,
            incoming = confirmed.content,
            canUseSnapshotMerge = otidRow.seqId != null && confirmed.seqId != null,
            incomingIsForwardDelta = otidRow.seqId == null || confirmed.seqId == null ||
                confirmed.seqId > otidRow.seqId,
            // h30cy: same-otid monotonic forward tokens must append even when
            // their bytes coincide with existing prefix/suffix text.
            incrementalForwardAppend = true,
        )
        val merged = otidRow.copy(
            content = merge.text,
            seqId = latestSeqId(otidRow.seqId, confirmed.seqId),
        )
        timeline = timeline.replaceByServerId(merged)
        timeline = timeline.copy(liveCursor = otidRow.serverId)
        pendingEvents += TimelineSyncEvent.StreamEventIngested(otidRow.serverId, message.messageType)
        hotPathTelemetry(
            "streamSubscriber.otidAppendMerged",
            "otid" to confirmed.otid,
            "serverId" to otidRow.serverId,
            "incomingServerId" to confirmed.serverId,
            "mergedLen" to merge.text.length,
            "mergeBranch" to merge.branch.name,
            "conversationId" to conversationId,
        )
        return output()
    }

    // letta-mobile-h30cy: real-wire forward-GROWTH merge. The App Server streams
    // assistant deltas with a NEW sequential letta-msg id per fragment AND NO otid
    // (captured live via app-server-iroh-probe: one reply = N fragments, ids
    // letta-msg-1312..1334, otid=null, content grows monotonically). serverId
    // rotates so findByServerId misses; otid is null so the otidRow merge misses;
    // the same-run prefix target only catches REPLAY prefixes (existing longer).
    // So each cumulative fragment appends → N rows = the on-device duplicate.
    //
    // Merge ONLY on STRICT forward growth: the immediately-preceding (last) event
    // is a same-run assistant row AND the incoming content EXTENDS it
    // (incoming.startsWith(existing) && incoming strictly longer). This excludes:
    //   • post-tool continuations — those START a new message ("Y" after a longer
    //     prior assistant), where incoming is SHORTER, so existing.startsWith(incoming)
    //     not incoming.startsWith(existing) → not a forward growth → not merged.
    //   • distinct same-run assistant messages — no forward-prefix relationship.
    val liveAssistant = (timeline.events.lastOrNull() as? TimelineEvent.Confirmed)
        ?.takeIf { it.messageType == TimelineMessageType.ASSISTANT }
    val isForwardGrowthFragment = liveAssistant != null &&
        confirmed.messageType == TimelineMessageType.ASSISTANT &&
        liveAssistant.serverId != confirmed.serverId &&
        confirmed.runId?.takeIf { it.isNotBlank() }?.let { inRun ->
            liveAssistant.runId?.takeIf { it.isNotBlank() }
                ?.isCompatibleAssistantPrefixRunId(inRun) == true
        } == true &&
        run {
            val existing = liveAssistant.content.trim()
            val incoming = confirmed.content.trim()
            existing.isNotEmpty() && incoming.length > existing.length && incoming.startsWith(existing)
        }
    if (isForwardGrowthFragment && liveAssistant != null) {
        val merged = liveAssistant.copy(
            content = confirmed.content,
            runId = promoteRunId(liveAssistant.runId, confirmed.runId),
            seqId = latestSeqId(liveAssistant.seqId, confirmed.seqId),
        )
        timeline = timeline.replaceByServerId(merged)
        timeline = timeline.copy(liveCursor = liveAssistant.serverId)
        pendingEvents += TimelineSyncEvent.StreamEventIngested(liveAssistant.serverId, message.messageType)
        hotPathTelemetry(
            "streamSubscriber.forwardGrowthMerged",
            "serverId" to liveAssistant.serverId,
            "incomingServerId" to confirmed.serverId,
            "runId" to (confirmed.runId ?: "<null>"),
            "mergedLen" to confirmed.content.length,
            "conversationId" to conversationId,
        )
        return output()
    }

    timeline = timeline.append(applyPendingToolReturns(confirmed, pendingToolReturnsByCallId))
    timeline = timeline.copy(liveCursor = confirmed.serverId)
    pendingEvents += TimelineSyncEvent.StreamEventIngested(confirmed.serverId, message.messageType)
    hotPathTelemetry(
        "streamSubscriber.ingested",
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
    // letta-mobile-k9y5d: surface snapshot collisions resolved by keeping the
    // longer text so the replay-garble path stays observable in telemetry.
    StreamTextMergeBranch.SNAPSHOT_CONFLICT -> "streamSubscriber.snapshotConflictKeptLonger"
    StreamTextMergeBranch.EMPTY_INCOMING,
    StreamTextMergeBranch.EQUAL,
    StreamTextMergeBranch.APPEND -> null
}

private fun hotPathTelemetry(
    name: String,
    vararg attrs: Pair<String, Any?>,
) {
    if (!Telemetry.isChatHotPathDebugEnabled()) return
    Telemetry.event(
        "TimelineSync",
        name,
        *attrs,
        level = Telemetry.Level.DEBUG,
    )
}

private fun TimelineEvent.Confirmed.canMergeStreamFrame(
    incoming: TimelineEvent.Confirmed,
): Boolean {
    val existingRunId = runId?.takeIf { it.isNotBlank() }
    val incomingRunId = incoming.runId?.takeIf { it.isNotBlank() }
    if (existingRunId != null && incomingRunId != null) {
        return existingRunId == incomingRunId || (existingRunId.isIrohSyntheticRunId() && !incomingRunId.isIrohSyntheticRunId())
    }
    return true
}

private fun TimelineEvent.Confirmed.hasIrohSyntheticRunId(): Boolean =
    runId?.takeIf { it.isNotBlank() }?.isIrohSyntheticRunId() == true

private fun String.isIrohSyntheticRunId(): Boolean = startsWith("iroh-run-")

/**
 * When an otid-matched cumulative chunk arrives carrying the real server run
 * id after the row was created under a synthetic `iroh-run-*` id, adopt the
 * real id so run-scoped grouping/collapse keys on it. Otherwise keep the
 * existing id (never regress a real id back to a synthetic one, and never
 * clobber with a blank incoming id).
 */
private fun promoteRunId(existing: String?, incoming: String?): String? {
    val existingRunId = existing?.takeIf { it.isNotBlank() }
    val incomingRunId = incoming?.takeIf { it.isNotBlank() } ?: return existingRunId
    if (existingRunId == null) return incomingRunId
    return if (existingRunId.isIrohSyntheticRunId() && !incomingRunId.isIrohSyntheticRunId()) {
        incomingRunId
    } else {
        existingRunId
    }
}

private fun String?.isCompatibleAssistantPrefixRunId(other: String): Boolean {
    val thisRunId = this?.takeIf { it.isNotBlank() } ?: return false
    if (thisRunId == other) return true
    val thisIsIrohFamily = thisRunId.isIrohSyntheticRunId() || thisRunId.startsWith("local-run-")
    val otherIsIrohFamily = other.isIrohSyntheticRunId() || other.startsWith("local-run-")
    return thisIsIrohFamily && otherIsIrohFamily
}

private fun Timeline.findSameRunAssistantPrefixOrBlankTarget(
    incoming: TimelineEvent.Confirmed,
): TimelineEvent.Confirmed? {
    if (incoming.messageType != TimelineMessageType.ASSISTANT) return null
    val incomingRunId = incoming.runId?.takeIf { it.isNotBlank() } ?: return null
    val incomingText = incoming.content.trim()
    return events
        .asSequence()
        .filterIsInstance<TimelineEvent.Confirmed>()
        .firstOrNull { existing ->
            if (existing.messageType != TimelineMessageType.ASSISTANT) return@firstOrNull false
            if (existing.serverId == incoming.serverId) return@firstOrNull false
            val existingRunId = existing.runId?.takeIf { it.isNotBlank() }
            if (existingRunId != null && !existingRunId.isCompatibleAssistantPrefixRunId(incomingRunId)) return@firstOrNull false

            val existingText = existing.content.trim()
            val isReplayPrefix = existingText.isNotBlank() &&
                (incomingText.isBlank() ||
                    (existingText.length > incomingText.length && existingText.startsWith(incomingText)))
            if (!isReplayPrefix) return@firstOrNull false

            // letta-mobile-ujz3x: preserve the one-character first token of a
            // genuine post-tool assistant continuation ("Y" -> "Yes ...") even
            // though it may be a prefix of an earlier same-run assistant. The
            // post-install replay bug, however, replays multi-character prefixes
            // like "Got" with seqId=1; those should still be dropped.
            if (existingRunId != null && incoming.seqId == 1 && incomingText.length <= 1) return@firstOrNull false
            true
        }
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
    // letta-mobile-fe51r: shared fold keeps projected previews from
    // clobbering full bodies and tracks truncation markers per call id.
    val fold = foldToolReturnBodies(ev.toolReturnContentByCallId, ev.toolReturnTruncationByCallId, matchingReturns)
    val returnIsErrorByCallId = ev.toolReturnIsErrorByCallId + matchingReturns.associate { (callId, toolReturn) ->
        callId to (toolReturn.isErr == true || toolReturn.status == "error")
    }
    Telemetry.event(
        "TimelineSync", "toolReturn.attachedPending",
        "serverId" to ev.serverId,
        "count" to matchingReturns.size,
    )
    val firstCallId = matchingReturns.first().first
    return ev.copy(
        approvalDecided = true,
        toolReturnContent = fold.contentByCallId[firstCallId] ?: ev.toolReturnContent,
        toolReturnIsError = firstReturn.isErr == true || firstReturn.status == "error" || ev.toolReturnIsError,
        toolReturnContentByCallId = fold.contentByCallId.toTimelinePersistentMap(),
        toolReturnIsErrorByCallId = returnIsErrorByCallId.toTimelinePersistentMap(),
        toolReturnTruncationByCallId = fold.truncationByCallId.toTimelinePersistentMap(),
        attachments = (ev.attachments + matchingReturns.flatMap { (_, toolReturn) -> toolReturn.attachments }).distinct().toTimelinePersistentList(),
    )
}
