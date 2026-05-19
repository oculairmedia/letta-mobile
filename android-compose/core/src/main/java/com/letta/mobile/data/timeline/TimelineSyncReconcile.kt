package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reconcile timeline state after sending a message. Swaps Local→Confirmed
 * for the outbound message, pulls in any server messages we don't yet have,
 * and advances liveCursor.
 */
internal suspend fun reconcileAfterSend(
    otid: String,
    conversationId: String,
    writeMutex: Mutex,
    state: MutableStateFlow<Timeline>,
    events: MutableSharedFlow<TimelineSyncEvent>,
    pendingLocalStore: PendingLocalStore,
    listMessagesWithRetry: suspend (String) -> List<LettaMessage>,
) {
    val timer = Telemetry.startTimer("TimelineSync", "reconcile")
    var confirmedLocal = false
    var appendedMissing = 0
    var confirmedServerId: String? = null
    var shouldDeletePendingLocal = false
    try {
        // letta-mobile-j44j: retry the GET on transient failures before
        // surfacing a user-visible error. The stream already landed the
        // assistant reply as Confirmed events (see streamAndReconcile),
        // so reconcile's job here is the lower-stakes work of swapping
        // the Local user bubble to Confirmed and picking up anything
        // the SSE missed. A network blip on that GET shouldn't leave
        // the bubble stuck in SENT forever. Keep this fetch outside the
        // write mutex so timeline mutation/rendering is not blocked by
        // network latency.
        val serverMessages = listMessagesWithRetry(otid).reversed()

        writeMutex.withLock {
            // 1. Swap Local→Confirmed for our outbound message
            val myMatch = serverMessages.firstOrNull { it.otid == otid }
            if (myMatch != null) {
                val existing = state.value.findByOtid(otid)
                if (existing is TimelineEvent.Local) {
                    val confirmed = myMatch.toTimelineEvent(position = existing.position)
                    if (confirmed != null) {
                        state.value = state.value.replaceLocal(otid, confirmed)
                        confirmedServerId = myMatch.id
                        confirmedLocal = true
                        // mge5.24: server echoed our send back, so any
                        // disk-persisted Local for this otid is now obsolete.
                        // (For text sends nothing was persisted; for images
                        // this branch will rarely fire today because the
                        // server drops them — but if/when it does, clean up.)
                        shouldDeletePendingLocal = true
                    }
                }
            }

            // 2. Pull in any server messages we don't yet have (missed stream events)
            serverMessages.forEach { msg ->
                val pos = state.value.nextLocalPosition()
                val confirmed = msg.toTimelineEvent(position = pos) ?: return@forEach
                if (confirmed.messageType == TimelineMessageType.TOOL_RETURN) return@forEach
                val byOtid = state.value.findByOtid(confirmed.otid)
                val byServerId = state.value.findByServerId(msg.id, confirmed.messageType)
                if (byOtid == null && byServerId == null) {
                    // letta-mobile-c87t: before falling through to the standard
                    // append, attempt a Client-Mode fuzzy collapse. If the user
                    // sent this message via Client Mode, there's a `cm-<uuid>`
                    // Local with matching content + recent timestamp + source
                    // == CLIENT_MODE_HARNESS in the timeline; we collapse the
                    // pair to a single Confirmed event at the Local's position
                    // so the UI doesn't transiently show two user bubbles.
                    //
                    // This path is scoped strictly via the Local's `source`
                    // field — never via ambient flags. See
                    // [Timeline.collapseClientModeFuzzyMatch] for the matcher.
                    val fuzzy = state.value.collapseClientModeFuzzyMatch(confirmed)
                    if (fuzzy.collapsed != null) {
                        state.value = fuzzy.timeline
                        // Meridian guardrail (2): log every fuzzy collapse at
                        // INFO level so 8cm8 verification + duplicate-bubble
                        // triage have data to work with.
                        Telemetry.event(
                            "TimelineSync", "reconcile.fuzzyCollapsed",
                            "conversationId" to conversationId,
                            "localOtid" to fuzzy.collapsed.localOtid,
                            "serverId" to fuzzy.collapsed.serverId,
                            "deltaMs" to fuzzy.collapsed.deltaMs,
                            "contentPrefix" to fuzzy.collapsed.contentPrefix,
                            "source" to fuzzy.collapsed.source.name,
                        )
                        return@forEach
                    }
                    state.value = state.value.append(confirmed)
                    appendedMissing++
                }
            }

            // 3. Advance liveCursor
            serverMessages.lastOrNull()?.id?.let {
                state.value = state.value.copy(liveCursor = it)
            }
        }

        confirmedServerId?.let { serverId ->
            events.emit(TimelineSyncEvent.LocalConfirmed(otid, serverId))
        }
        if (shouldDeletePendingLocal) {
            runCatching { pendingLocalStore.delete(otid) }
        }
        timer.stop(
            "otid" to otid,
            "serverCount" to serverMessages.size,
            "confirmedLocal" to confirmedLocal,
            "appendedMissing" to appendedMissing,
        )
    } catch (t: Throwable) {
        timer.stopError(t, "otid" to otid)
        events.emit(TimelineSyncEvent.ReconcileError(t.message ?: "unknown"))
    }
}

/**
 * Walk a server message snapshot and apply any approval_response +
 * tool_return hints to existing TOOL_CALL events in the timeline. Flips
 * approvalDecided=true and attaches toolReturnContent as appropriate.
 * Must be invoked inside writeMutex.
 *
 * letta-mobile-mge5.21: the server's SSE /stream endpoint often omits
 * tool_return frames even though they're stored in REST. So we apply
 * these hints explicitly after any REST snapshot (hydrate, reconcile).
 */
internal fun applyReturnsAndResponsesFromSnapshot(
    snapshot: List<LettaMessage>,
    state: MutableStateFlow<Timeline>,
) {
    val decidedIds = snapshot.filterIsInstance<ApprovalResponseMessage>()
        .mapNotNull { it.approvalRequestId }
        .toSet()
    val returnsByCallId: Map<String, ToolReturnMessage> =
        snapshot.filterIsInstance<ToolReturnMessage>()
            .mapNotNull { r -> r.toolCallId?.let { it to r } }
            .toMap()
    if (decidedIds.isEmpty() && returnsByCallId.isEmpty()) return
    val returnedToolCallIds = returnsByCallId.keys
    val newEvents = state.value.events.map { ev ->
        if (ev !is TimelineEvent.Confirmed || ev.messageType != TimelineMessageType.TOOL_CALL) {
            return@map ev
        }
        val matchingReturns = ev.toolCalls.mapNotNull { tc ->
            val callId = tc.effectiveId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val toolReturn = returnsByCallId[callId] ?: return@mapNotNull null
            callId to toolReturn
        }
        val matchingReturn = matchingReturns.firstOrNull()?.second
        val byResponse = ev.approvalRequestId != null && ev.approvalRequestId in decidedIds
        val byReturn = ev.toolCalls.any { it.effectiveId in returnedToolCallIds }
        if (matchingReturn == null && !byResponse && !byReturn) return@map ev
        val returnContentByCallId = ev.toolReturnContentByCallId + matchingReturns.mapNotNull { (callId, toolReturn) ->
            toolReturn.toolReturn.funcResponse?.let { callId to it }
        }.toMap()
        val returnIsErrorByCallId = ev.toolReturnIsErrorByCallId + matchingReturns.associate { (callId, toolReturn) ->
            callId to (toolReturn.isErr == true || toolReturn.status == "error")
        }
        ev.copy(
            approvalDecided = byResponse || byReturn || ev.approvalDecided,
            toolReturnContent = matchingReturn?.toolReturn?.funcResponse
                ?: ev.toolReturnContent,
            toolReturnIsError = matchingReturn?.let { it.isErr == true || it.status == "error" }
                ?: ev.toolReturnIsError,
            toolReturnContentByCallId = returnContentByCallId,
            toolReturnIsErrorByCallId = returnIsErrorByCallId,
        )
    }
    if (newEvents !== state.value.events) {
        state.value = state.value.copy(events = newEvents)
    }
}
