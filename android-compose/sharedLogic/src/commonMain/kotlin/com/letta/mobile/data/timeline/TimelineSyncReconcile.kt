package com.letta.mobile.data.timeline

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reconcile timeline state after sending a message. Swaps Local→Confirmed
 * for the outbound message, pulls in any server messages we don't yet have,
 * and advances liveCursor.
 */
suspend fun reconcileAfterSend(
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

        val result = applyReconcileAfterSendSnapshot(
            otid = otid,
            conversationId = conversationId,
            serverMessages = serverMessages,
            writeMutex = writeMutex,
            state = state,
        )
        confirmedLocal = result.confirmedLocal
        appendedMissing = result.appendedMissing
        confirmedServerId = result.confirmedServerId
        shouldDeletePendingLocal = result.shouldDeletePendingLocal

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

@Immutable
data class ReconcileAfterSendResult(
    val confirmedLocal: Boolean,
    val appendedMissing: Int,
    val confirmedServerId: String?,
    val shouldDeletePendingLocal: Boolean,
)

suspend fun applyReconcileAfterSendSnapshot(
    otid: String,
    conversationId: String,
    serverMessages: List<LettaMessage>,
    writeMutex: Mutex,
    state: MutableStateFlow<Timeline>,
): ReconcileAfterSendResult {
    var confirmedLocal = false
    var appendedMissing = 0
    var confirmedServerId: String? = null
    var shouldDeletePendingLocal = false

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
        val mergeResult = state.value.mergeServerMessages(serverMessages)
        state.value = mergeResult.first
        appendedMissing = mergeResult.second

        // 3. Advance liveCursor
        serverMessages.lastOrNull()?.id?.let {
            state.value = state.value.copy(liveCursor = it)
        }
    }

    return ReconcileAfterSendResult(
        confirmedLocal = confirmedLocal,
        appendedMissing = appendedMissing,
        confirmedServerId = confirmedServerId,
        shouldDeletePendingLocal = shouldDeletePendingLocal,
    )
}

fun Timeline.mergeServerMessages(
    serverMessages: List<LettaMessage>,
): Pair<Timeline, Int> {
    var timeline = this
    var merged = 0
    serverMessages.forEach { msg ->
        val pos = timeline.positionForServerMessageDate(msg)
        val confirmed = msg.toTimelineEvent(position = pos) ?: return@forEach
        if (confirmed.messageType == TimelineMessageType.TOOL_RETURN) return@forEach
        if (timeline.shouldSuppressAbandonedAssistantFragment(confirmed)) {
            Telemetry.event(
                "TimelineSync", "recentReconcile.abandonedAssistantSuppressed",
                "conversationId" to timeline.conversationId,
                "serverId" to confirmed.serverId,
                "runId" to (confirmed.runId ?: ""),
            )
            return@forEach
        }
        if (timeline.containsIdentityFor(confirmed)) return@forEach
        val existingByServerId = timeline.findByServerId(confirmed.serverId, confirmed.messageType)
        if (existingByServerId == null && timeline.recentTailContainsEquivalent(confirmed)) {
            // The HTTP backend mints its OWN message ids (ui-msg-*) that never
            // match the App Server ids (letta-msg-*) carried by the live Iroh
            // frames, and it omits run_id/original otid. No id-based identity
            // can catch that copy, so a live-delivered reply re-appends on the
            // next reconcile poll. Dedupe by (messageType + identical trimmed
            // content) within the recent tail window instead of inserting.
            Telemetry.event(
                "TimelineSync", "recentReconcile.contentDeduped",
                "conversationId" to timeline.conversationId,
                "serverId" to confirmed.serverId,
                "messageType" to confirmed.messageType.name,
            )
            return@forEach
        }
        if (existingByServerId?.canReplaceIrohSyntheticLiveRow(confirmed) == true) {
            timeline = timeline.replaceByServerId(confirmed)
            merged++
        } else if (existingByServerId == null) {
            val prefixIndex = timeline.findRecentAssistantPrefixIndex(confirmed)
            if (prefixIndex != null) {
                timeline = timeline.replaceEventAt(prefixIndex, confirmed.copy(position = timeline.events[prefixIndex].position))
                Telemetry.event(
                    "TimelineSync", "recentReconcile.assistantPrefixReplaced",
                    "conversationId" to timeline.conversationId,
                    "serverId" to confirmed.serverId,
                    "incomingLen" to confirmed.content.length,
                )
                merged++
            } else {
                timeline = timeline.insertOrdered(confirmed)
                merged++
            }
        } else if (existingByServerId.sharesRunIdentityWith(confirmed)) {
            // Same server id + message type, and at least one side lacks a
            // run id (REST /messages replies often omit run_id while the live
            // Iroh/WS row carries the real one). #780 run-scoped identity keys
            // no longer overlap in that case, so without this guard the
            // reconciled copy re-inserts and the reply renders twice. Only a
            // REAL run-id mismatch on both sides (recycled server id across
            // runs) may append.
            return@forEach
        } else {
            timeline = timeline.insertOrdered(confirmed)
            merged++
        }
    }
    return timeline to merged
}

private fun Timeline.replaceEventAt(index: Int, event: TimelineEvent.Confirmed): Timeline {
    val updated = events.toMutableList()
    updated[index] = event
    return copy(events = updated.toPersistentList(), stablePrefixVersion = stablePrefixVersion + 1)
}

private fun Timeline.findRecentAssistantPrefixIndex(incoming: TimelineEvent.Confirmed): Int? {
    if (incoming.messageType != TimelineMessageType.ASSISTANT) return null
    val incomingText = incoming.content.trim()
    if (incomingText.length < 2) return null
    val start = (events.size - RECONCILE_CONTENT_DEDUPE_TAIL).coerceAtLeast(0)
    for (index in events.size - 1 downTo start) {
        val event = events[index] as? TimelineEvent.Confirmed ?: continue
        if (event.messageType != TimelineMessageType.ASSISTANT) continue
        if (event.serverId == incoming.serverId) continue
        val existingText = event.content.trim()
        if (existingText.isBlank()) continue
        if (incomingText.length > existingText.length && incomingText.startsWith(existingText)) {
            return index
        }
    }
    return null
}


private fun TimelineEvent.Confirmed.canReplaceIrohSyntheticLiveRow(
    incoming: TimelineEvent.Confirmed,
): Boolean {
    val existingRunId = runId?.takeIf { it.isNotBlank() }
    val incomingRunId = incoming.runId?.takeIf { it.isNotBlank() }
    return messageType in setOf(TimelineMessageType.ASSISTANT, TimelineMessageType.REASONING) &&
        existingRunId?.startsWith("iroh-run-") == true &&
        incomingRunId != null &&
        !incomingRunId.startsWith("iroh-run-")
}

/**
 * Content-level dedupe for reconcile copies whose ids can never match the live
 * rows (HTTP backend mints ui-msg-* ids; live Iroh rows carry letta-msg-*).
 * Scans the recent tail for a row of the same message type with identical
 * trimmed content. Bounded to the tail window so long histories stay O(1)-ish
 * per reconcile and legitimately repeated OLD messages are not collapsed.
 */
private const val RECONCILE_CONTENT_DEDUPE_TAIL = 30

private fun Timeline.recentTailContainsEquivalent(incoming: TimelineEvent.Confirmed): Boolean {
    if (incoming.messageType != TimelineMessageType.ASSISTANT &&
        incoming.messageType != TimelineMessageType.USER &&
        incoming.messageType != TimelineMessageType.REASONING
    ) {
        return false
    }
    val incomingContent = incoming.content.trim()
    if (incomingContent.isEmpty()) return false
    val start = (events.size - RECONCILE_CONTENT_DEDUPE_TAIL).coerceAtLeast(0)
    for (i in events.size - 1 downTo start) {
        val event = events[i] as? TimelineEvent.Confirmed ?: continue
        if (event.messageType != incoming.messageType) continue
        if (event.content.trim() == incomingContent) return true
    }
    return false
}

/**
 * True when an existing row with the same server id should absorb the incoming
 * reconciled copy instead of appending a duplicate. Matching real run ids, or
 * either side missing a run id (REST reconcile responses often omit run_id),
 * count as the same logical message. Only two DIFFERENT real run ids — the
 * recycled-server-id-across-runs case from #780 — are treated as distinct.
 */
private fun TimelineEvent.Confirmed.sharesRunIdentityWith(
    incoming: TimelineEvent.Confirmed,
): Boolean {
    val existingRunId = runId?.takeIf { it.isNotBlank() }
    val incomingRunId = incoming.runId?.takeIf { it.isNotBlank() }
    if (existingRunId == null || incomingRunId == null) return true
    return existingRunId == incomingRunId
}

fun Timeline.positionForServerMessageDate(message: LettaMessage): Double {
    val messageDate = message.date?.let { parseTimelineInstantOrNull(it) }
        ?: return nextLocalPosition()
    val nextIndex = events.indexOfFirst { event ->
        val eventDate = (event as? TimelineEvent.Confirmed)?.date ?: return@indexOfFirst false
        compareTimelineInstants(eventDate, messageDate) > 0
    }
    if (nextIndex < 0) return nextLocalPosition()

    val nextPosition = events[nextIndex].position
    val previousPosition = events.getOrNull(nextIndex - 1)?.position
    return when {
        previousPosition == null -> nextPosition - 1.0
        nextPosition > previousPosition -> previousPosition + ((nextPosition - previousPosition) / 2.0)
        else -> previousPosition + 0.000001
    }
}



suspend fun reconcileForExternalRun(
    runId: String,
    reconcileRecentMessagesFromServer: suspend (String, Array<Pair<String, Any?>>, Boolean) -> Unit,
) {
    reconcileRecentMessagesFromServer(
        "externalRunReconcile",
        arrayOf("runId" to runId),
        true
    )
}

