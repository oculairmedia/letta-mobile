package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    conversationCursorStore: ConversationCursorStore = NoOpConversationCursorStore,
    source: String = "unknown",
): PendingIngestNotification? {
    // letta-mobile-rnyg: collect events to emit inside the writeMutex so we
    // can publish them AFTER releasing the lock. MutableSharedFlow.emit can
    // suspend (default BufferOverflow.SUSPEND), and any subscriber re-entering
    // a code path that needs writeMutex would deadlock if we emit under it.
    val output = writeMutex.withLock {
        val out = reduceStreamFrame(
            TimelineReducerInput(
                prev = state.value,
                frame = message,
                pendingToolReturnsByCallId = pendingToolReturnsByCallId.toTimelinePersistentMap(),
                source = source,
            )
        )
        message.seqId?.takeIf { it >= 0 }?.let { seq ->
            conversationCursorStore.recordFrame(conversationId, seq.toLong())
        }
        state.value = out.next.forceVisibleEmissionIfNeeded(message, state.value)
        if (Telemetry.isTimelineSyncGateDebugEnabled()) {
            Telemetry.event(
                "TimelineSyncIngest", "gate5.stateUpdated",
                "conversationId" to conversationId,
                "events.size" to state.value.events.size,
                "messageType" to message.messageType,
                level = Telemetry.Level.DEBUG,
            )
        }
        // Keep the loop-owned LinkedHashMap in reducer-return order so buffered
        // tool returns preserve insertion order across frames.
        pendingToolReturnsByCallId.clear()
        pendingToolReturnsByCallId.putAll(out.updatedPendingToolReturnsByCallId)
        out
    }
    // Lock released — safe to suspend on emit/dispatch.
    output.emittedEvents.forEach { events.emit(it) }
    dumpTimelineState("streamIngest.${message.messageType}", conversationId, state.value)
    return output.notification
}

private fun Timeline.forceVisibleEmissionIfNeeded(message: LettaMessage, previousEmitted: Timeline): Timeline {
    if (message is ToolReturnMessage) return forceVisibleEmission()
    if (message is AssistantMessage && message.id != previousEmitted.events.lastConfirmedAssistantIdForOtid(message.otid)) {
        return forceVisibleEmission()
    }
    return this
}

private fun List<TimelineEvent>.lastConfirmedAssistantIdForOtid(otid: String?): String? =
    (lastOrNull() as? TimelineEvent.Confirmed)
        ?.takeIf { event ->
            event.messageType == TimelineMessageType.ASSISTANT &&
                (otid.isNullOrBlank() || event.otid == otid)
        }
        ?.serverId
