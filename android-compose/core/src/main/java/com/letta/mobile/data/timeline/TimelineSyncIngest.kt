package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Ingest a single LettaMessage received via the resume-stream SSE
 * subscription. Dedupes by server id and otid (so stream events that
 * duplicate ones we already have via reconcile are ignored). Appends
 * novel messages as Confirmed events.
 */
internal suspend fun ingestStreamEvent(
    message: LettaMessage,
    writeMutex: Mutex,
    state: MutableStateFlow<Timeline>,
    events: MutableSharedFlow<TimelineSyncEvent>,
    pendingToolReturnsByCallId: LinkedHashMap<String, ToolReturnMessage>,
    conversationId: String,
    ingestNotificationDispatcher: TimelineIngestNotificationDispatcher,
) {
    // letta-mobile-rnyg: collect events to emit inside the writeMutex so we
    // can publish them AFTER releasing the lock. MutableSharedFlow.emit can
    // suspend (default BufferOverflow.SUSPEND), and any subscriber re-entering
    // a code path that needs writeMutex would deadlock if we emit under it.
    val pendingEvents = mutableListOf<TimelineSyncEvent>()
    val notification = writeMutex.withLock<PendingIngestNotification?> {
        // letta-mobile-mge5.15: ApprovalResponseMessage doesn't produce its own
        // bubble — instead we find the corresponding ApprovalRequestMessage
        // event (by approvalRequestId) and mark it decided so the UI hides
        // the approve/reject buttons. This covers all paths: auto-approved
        // by server, approved via phone UI, approved from another client.
        if (message is ApprovalResponseMessage) {
            val reqId = message.approvalRequestId ?: return@withLock null
            val match = state.value.events.firstOrNull {
                it is TimelineEvent.Confirmed && it.approvalRequestId == reqId
            } as? TimelineEvent.Confirmed ?: return@withLock null
            if (match.approvalDecided) {
                // letta-mobile-mge5.6: observed a redundant approval
                // response — already decided. Counted as a dedupe drop.
                Telemetry.event(
                    "TimelineSync", "streamSubscriber.eventDeduped",
                    "reason" to "approvalAlreadyDecided",
                    "approvalRequestId" to reqId,
                    "conversationId" to conversationId,
                )
                return@withLock null
            }
            val updated = match.copy(approvalDecided = true)
            state.value = state.value.replaceByServerId(updated)
            pendingEvents += TimelineSyncEvent.StreamEventIngested(match.serverId, message.messageType)
            return@withLock null
        }
        // Observe a tool_return_message → the tool ran → approval (if any)
        // must have been granted. Flip any matching ApprovalRequest event to
        // decided. letta-mobile-mge5.17: this is a causal fallback for when
        // the approval_response frame is dropped by SSE or has a wonky
        // discriminator. If the tool actually produced output, the request
        // was clearly approved, regardless of whether we saw the response.
        if (message is ToolReturnMessage) {
            // Attach the tool return body to the matching TOOL_CALL event
            // (letta-mobile-mge5.19). Command + output live in one bubble
            // with collapsible output in the UI. We never append a standalone
            // TOOL_RETURN event to the timeline — the return is ALWAYS a
            // continuation of a prior call.
            val tcid = message.toolCallId
            Telemetry.event(
                "TimelineSync", "toolReturn.observed",
                "toolCallId" to (tcid ?: "<null>"),
                "hasBody" to (message.toolReturn.funcResponse?.isNotEmpty() == true),
                "timelineSize" to state.value.events.size,
            )
            if (tcid != null) {
                val match = state.value.events.firstOrNull { ev ->
                    ev is TimelineEvent.Confirmed &&
                        ev.toolCalls.any { it.effectiveId == tcid }
                } as? TimelineEvent.Confirmed
                if (match != null) {
                    val body = message.toolReturn.funcResponse ?: ""
                    val isError = message.isErr == true || message.status == "error"
                    val contentByCallId = if (body.isEmpty()) {
                        match.toolReturnContentByCallId
                    } else {
                        match.toolReturnContentByCallId + (tcid to body)
                    }
                    val updated = match.copy(
                        approvalDecided = true,  // tool ran → approved
                        toolReturnContent = body.ifEmpty { match.toolReturnContent },
                        toolReturnIsError = isError,
                        toolReturnContentByCallId = contentByCallId,
                        toolReturnIsErrorByCallId = match.toolReturnIsErrorByCallId + (tcid to isError),
                    )
                    state.value = state.value.replaceByServerId(updated)
                    pendingEvents += TimelineSyncEvent.StreamEventIngested(match.serverId, message.messageType)
                    Telemetry.event(
                        "TimelineSync", "toolReturn.attached",
                        "serverId" to match.serverId,
                        "bodyLen" to body.length,
                    )
                    // letta-mobile-rnyg: tool_return_message was previously
                    // dropped without ever building a PendingIngestNotification,
                    // so background users never saw "tool finished". Surface
                    // the attached return like the fall-through assistant/tool
                    // path below.
                    val mt = message.messageType
                    if (mt == "tool_return_message" && body.isNotBlank()) {
                        return@withLock PendingIngestNotification(
                            serverId = match.serverId,
                            messageType = mt,
                            contentPreview = body.take(140),
                        )
                    }
                } else {
                    pendingToolReturnsByCallId[tcid] = message
                    // The call bubble may not exist yet if the return arrived
                    // before the request was ingested. Buffer it so live UI
                    // does not have to wait for REST reconcile/hydrate.
                    Telemetry.event(
                        "TimelineSync", "toolReturn.noMatch",
                        "toolCallId" to tcid,
                        "timelineSize" to state.value.events.size,
                    )
                }
            }
            return@withLock null
        }
        val confirmed = message.toTimelineEvent(position = state.value.nextLocalPosition())
        if (confirmed == null) {
            // letta-mobile-5s1n probe: message types we don't render
            // (PingMessage, StopReason, etc.) should be the only callers
            // here. If assistant_message ever shows up, it means
            // toTimelineEvent has a bug.
            Telemetry.event(
                "TimelineSync", "streamSubscriber.toTimelineEventNull",
                "messageType" to message.messageType,
                "messageId" to message.id,
                "conversationId" to conversationId,
            )
            return@withLock null
        }
        // Letta streams one assistant_message per step with an incrementing
        // `seq_id`, and each frame's `content` carries only the NEWLY-EMITTED
        // tokens since the last frame (not the cumulative text). The correct
        // way to render progress is to CONCATENATE deltas sharing the same
        // serverId. Use a heuristic so we don't double-concat if the server
        // ever switches to cumulative-content shape:
        //   - if incoming.content starts with existing.content -> treat as
        //     cumulative (server decided to send the full buffer), replace.
        //   - if existing.content starts with incoming.content -> older/stale
        //     frame, skip.
        //   - otherwise -> append incoming.content to existing.content.
        // letta-mobile-mge5: initial replaceByServerId-only fix produced
        // "All we got from your last message is look" (one delta's content)
        // instead of the full assistant text — reported 2026-04-18.
        val existing = state.value.findByServerId(confirmed.serverId, confirmed.messageType)
        if (existing != null) {
            if (existing.hasAlreadyIngestedStreamFrame(confirmed)) {
                Telemetry.event(
                    "TimelineSync", "streamSubscriber.duplicateSeqSkipped",
                    "serverId" to confirmed.serverId,
                    "messageType" to (message.messageType ?: "?"),
                    "existingSeqId" to existing.seqId,
                    "incomingSeqId" to confirmed.seqId,
                    "conversationId" to conversationId,
                )
                return@withLock null
            }
            val oldText = existing.content
            val newText = confirmed.content
            // letta-mobile (lettabot-uww.11 fix): the WS gateway emits
            // assistant text as PURE DELTAS. Verified by the gateway's
            // ws-gateway.e2e.test.ts § "assistant text reassembly"
            // suite (37 byte-perfect reassembly cases including
            // adversarial chunking, mermaid blocks, prefix-collision
            // sequences, and unicode/emoji boundaries).
            //
            // The previous wucn-snapshot-recovery cascade had two
            // defects that silently corrupted user-visible text:
            //   - `oldText.startsWith(newText)` dropped any delta
            //     whose head matched a prefix of the accumulator
            //     (frequent for repeated tokens / coalescer
            //     boundaries). This produced silent character loss
            //     such as the 2026-04-26 mermaid field repro
            //     `A[LLM snapshots]` → `A[LLMapshots|`.
            //   - the >=32-char "near-snapshot" branch replaced the
            //     accumulator wholesale, destroying everything
            //     before the incoming delta.
            //
            // The contract is delta-append. Trust it. If the gateway
            // ever changes shape, the e2e tests will fail loudly
            // before reaching the device.
            val mergedText = if (existing.source == MessageSource.CLIENT_MODE_HARNESS) {
                // Client Mode has two producers for the same logical assistant
                // turn: the WS-local harness stream and the Letta SSE/reconcile
                // stream. Once a local harness bubble has been fuzzy-collapsed
                // to a Confirmed event, a subsequent server frame with the same
                // server id may carry the already-rendered full/cumulative text
                // rather than a novel delta. Do not append that content again.
                when {
                    newText.isEmpty() -> oldText
                    newText == oldText -> oldText
                    newText.startsWith(oldText) -> newText
                    oldText.startsWith(newText) -> oldText
                    oldText.endsWith(newText) -> oldText
                    else -> oldText + newText
                }
            } else if (newText.isEmpty()) oldText else oldText + newText
            // Merge toolCalls: a later delta frame may have null/blank
            // arguments but a still-valid name/id; keep whichever list has
            // more data. Specifically, prefer the list that has more calls
            // with non-blank arguments — that's the "complete" one.
            // letta-mobile-mge5.23: fixes the "I can see the tool called
            // but can't see arguments or output" symptom where a final
            // empty-args delta was overwriting a populated earlier frame.
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
                    // Preserve these fields from the existing event — a delta
                    // that arrives AFTER a tool_return attachment would
                    // otherwise overwrite the attached output.
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
            state.value = state.value.replaceByServerId(merged)
            state.value = state.value.copy(liveCursor = confirmed.serverId)
            pendingEvents += TimelineSyncEvent.StreamEventIngested(confirmed.serverId, message.messageType)
            // letta-mobile-5s1n probe: assistant deltas after the first frame
            // hit this branch silently. Track them so we can prove (or
            // disprove) that the merged content is actually accumulating —
            // and that the existing event we're merging into came from
            // SSE/REST (i.e. is a Confirmed) rather than a Local that the
            // observer's `toUiMessageOrNull` projection might be dropping.
            Telemetry.event(
                "TimelineSync", "streamSubscriber.merged",
                "serverId" to confirmed.serverId,
                "messageType" to (message.messageType ?: "?"),
                "existingType" to existing.messageType.name,
                "oldLen" to oldText.length,
                "newLen" to newText.length,
                "mergedLen" to mergedText.length,
                "oldToolCalls" to oldCalls.size,
                "newToolCalls" to newCalls.size,
                "mergedToolCalls" to mergedCalls.size,
                "conversationId" to conversationId,
            )
            // No notification-publish for in-place updates — we already
            // published when the event first appeared.
            return@withLock null
        }
        // otid-based dedupe: catches our own echoes before reconcile runs.
        if (state.value.findByOtid(confirmed.otid) != null) {
            // letta-mobile-mge5.6: track the dedupe drop so Grafana can show
            // how many redundant frames the subscriber is absorbing.
            Telemetry.event(
                "TimelineSync", "streamSubscriber.eventDeduped",
                "reason" to "otidSeen",
                "otid" to (confirmed.otid ?: "<null>"),
                "conversationId" to conversationId,
            )
            return@withLock null
        }
        // letta-mobile-c87t: Client Mode fuzzy collapse on the live stream path.
        // Mirrors the reconcile-path collapse above — if there's a recent
        // CLIENT_MODE_HARNESS-source Local with matching content, swap rather
        // than appending a duplicate. See [Timeline.collapseClientModeFuzzyMatch].
        val fuzzy = state.value.collapseClientModeFuzzyMatch(confirmed)
        if (fuzzy.collapsed != null) {
            state.value = fuzzy.timeline
            state.value = state.value.copy(liveCursor = confirmed.serverId)
            pendingEvents += TimelineSyncEvent.StreamEventIngested(confirmed.serverId, message.messageType)
            Telemetry.event(
                "TimelineSync", "streamSubscriber.fuzzyCollapsed",
                "conversationId" to conversationId,
                "localOtid" to fuzzy.collapsed.localOtid,
                "serverId" to fuzzy.collapsed.serverId,
                "deltaMs" to fuzzy.collapsed.deltaMs,
                "contentPrefix" to fuzzy.collapsed.contentPrefix,
                "source" to fuzzy.collapsed.source.name,
            )
            return@withLock null
        }
        state.value = state.value.append(applyPendingToolReturns(confirmed, pendingToolReturnsByCallId))
        state.value = state.value.copy(liveCursor = confirmed.serverId)
        pendingEvents += TimelineSyncEvent.StreamEventIngested(confirmed.serverId, message.messageType)
        Telemetry.event(
            "TimelineSync", "streamSubscriber.ingested",
            "serverId" to confirmed.serverId,
            "messageType" to (message.messageType ?: "?"),
            "conversationId" to conversationId,
        )
        // Notify the :app module so it can post a system notification when
        // the user isn't currently viewing this conversation. We only care
        // about inbound assistant/tool_return messages — skip streams of our
        // own echo and agent-internal plumbing. Resolve and invoke the
        // listener after releasing writeMutex so notification lookup/network
        // work cannot block timeline mutations or rendering.
        val mt = message.messageType
        if (mt == "assistant_message" || mt == "tool_return_message") {
            PendingIngestNotification(
                serverId = confirmed.serverId,
                messageType = mt,
                contentPreview = confirmed.content.take(140).ifBlank { null },
            )
        } else {
            null
        }
    }
    // Lock released — safe to suspend on emit/dispatch.
    pendingEvents.forEach { events.emit(it) }
    ingestNotificationDispatcher.dispatch(notification)
}

/**
 * Attach any tool_return frames that arrived before their tool_call frame.
 * Must be called under [writeMutex].
 */
internal fun applyPendingToolReturns(
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
