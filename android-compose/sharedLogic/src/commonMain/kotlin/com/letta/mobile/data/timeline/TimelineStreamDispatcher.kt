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
) {
    // h30cy: the experimental "shadow holder" (ConversationStateHolder) parallel
    // reducer was REMOVED. It ran a second reduceStreamFrame off a lossy tryEmit
    // SharedFlow, diverged from the authoritative loop (matched=false,
    // shadowHolderLag≈32) and polluted the reduce/ingest path with an untagged
    // "unknown" second ingest — the dual-reducer race behind the intermittent
    // Iroh drop/dup. Only the authoritative `state` path remains. See
    // docs/h30cy-shadow-holder-failure-mode.md.
    suspend fun dispatch(message: LettaMessage, source: String = "unknown") {
        val notification = ingestStreamEvent(
            message = message,
            writeMutex = writeMutex,
            state = state,
            events = events,
            pendingToolReturnsByCallId = pendingToolReturnsByCallId,
            conversationId = conversationId,
            conversationCursorStore = conversationCursorStore,
            source = source,
        )
        if (notification != null) {
            loopScope.launch { ingestNotificationDispatcher.dispatch(notification) }
        }
    }
}
