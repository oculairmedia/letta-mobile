package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Handles SSE stream frame dispatch, routing incoming frames to the ingest reducer,
 * executing notifications, and fanning out to the experimental holder for parity checks.
 */
internal class TimelineStreamDispatcher(
    private val conversationId: String,
    private val writeMutex: Mutex,
    private val state: MutableStateFlow<Timeline>,
    private val events: MutableSharedFlow<TimelineSyncEvent>,
    private val pendingToolReturnsByCallId: LinkedHashMap<String, ToolReturnMessage>,
    private val conversationCursorStore: ConversationCursorStore,
    private val loopScope: CoroutineScope,
    private val ingestNotificationDispatcher: TimelineIngestNotificationDispatcher,
    private val holderFramesIn: MutableSharedFlow<LettaMessage>,
    private val getHolderEventCount: () -> Int,
) {
    suspend fun dispatch(message: LettaMessage) {
        val notification = ingestStreamEvent(
            message = message,
            writeMutex = writeMutex,
            state = state,
            events = events,
            pendingToolReturnsByCallId = pendingToolReturnsByCallId,
            conversationId = conversationId,
            conversationCursorStore = conversationCursorStore,
        )
        if (notification != null) {
            loopScope.launch { ingestNotificationDispatcher.dispatch(notification) }
        }
        val emitted = holderFramesIn.tryEmit(message)
        Telemetry.event(
            "TimelineSync", "streamSubscriber.foldedViaHolder",
            "serverId" to message.id,
            "messageType" to message.messageType,
            "emitted" to emitted,
            "holderEventCount" to getHolderEventCount(),
            "loopEventCount" to state.value.events.size,
            "matched" to (getHolderEventCount() == state.value.events.size),
        )
    }
}
