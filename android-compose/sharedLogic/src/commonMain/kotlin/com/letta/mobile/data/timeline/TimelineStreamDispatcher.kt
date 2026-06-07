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
    // letta-mobile-yflpp: throttle the shadow-holder parity telemetry. This is
    // a Phase-2 parity probe for the experimental ConversationStateHolder, NOT
    // the authoritative UI path. The holder folds frames asynchronously off a
    // SharedFlow, so during a streaming burst its event count legitimately lags
    // the authoritative loop and `matched=false` is expected churn — not a
    // re-fold or a defect. Emitting it per-frame made the streaming hot path
    // look like a ~20/sec "reprojection storm" in logcat and added per-dispatch
    // work. Sample it instead so parity stays observable without the spam.
    private var dispatchCount = 0

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
        dispatchCount++
        val holderEventCount = getHolderEventCount()
        val loopEventCount = state.value.events.size
        val matched = holderEventCount == loopEventCount
        // Always surface a mismatch (so a real divergence isn't hidden), but
        // sample the matched=true steady state to avoid per-frame spam.
        if (!matched || dispatchCount % PARITY_TELEMETRY_SAMPLE == 1) {
            Telemetry.event(
                "TimelineSync", "streamSubscriber.foldedViaHolder",
                "serverId" to message.id,
                "messageType" to message.messageType,
                "emitted" to emitted,
                "holderEventCount" to holderEventCount,
                "loopEventCount" to loopEventCount,
                "matched" to matched,
                // Shadow-holder lag during a burst is expected; flag it so this
                // telemetry isn't misread as an authoritative-path defect.
                "shadowHolderLag" to (loopEventCount - holderEventCount),
            )
        }
    }

    private companion object {
        const val PARITY_TELEMETRY_SAMPLE = 32
    }
}
