package com.letta.mobile.feature.chat.coordination

import android.util.Log
import androidx.compose.material3.SnackbarDuration
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.a2ui.A2uiFrameEvent
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.feature.chat.state.ChatBannerController
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.letta.mobile.ui.chat.render.A2uiActionSnackbarUi
import com.letta.mobile.ui.chat.render.A2uiDebugFrameUi
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.feature.chat.a2ui.toToolApprovalSubmission

internal class AdminChatA2uiCoordinator(
    private val scope: CoroutineScope,
    private val wsChatBridge: WsChatBridge,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val chatBannerController: ChatBannerController,
    private val activeConversationId: () -> String?,
    private val chatApprovalController: ChatApprovalController,
) {
    companion object {
        private const val MAX_A2UI_DEBUG_FRAMES = 12
        private const val A2UI_THINKING_TIMEOUT_MS = 60_000L
        private const val A2UI_THINKING_DELAY_MESSAGE = "Still working — long tool calls can take a few minutes"
        private const val TAG = "AdminChatA2uiCoordinator"
    }

    private val a2uiSurfaceManager = A2uiSurfaceManager()
    private val pendingA2uiActions = mutableMapOf<String, PendingA2uiAction>()
    private var a2uiConversationId: String? = null
    private var a2uiHistorySignature: Int? = null
    private var a2uiHistoryAppliedCount: Int = 0
    private var a2uiHistoryAppliedPrefix: List<A2uiMessage>? = null
    private var a2uiLiveEventSeen = false
    private var a2uiThinkingTimeoutJob: Job? = null
    private var a2uiThinkingStartMessageCount: Int? = null
    private var nextA2uiDebugFrameId = 0L
    private var nextA2uiSnackbarId = 0L

    init {
        observeA2uiSurfaceState()
        observeA2uiEvents()
        observeA2uiActionOutcomes()
    }

    fun release() {
        a2uiThinkingTimeoutJob?.cancel()
    }

    private fun observeA2uiSurfaceState() {
        scope.launch {
            a2uiSurfaceManager.surfaces.collect { surfaces ->
                publishA2uiSurfaces(surfaces)
            }
        }
    }

    private fun publishA2uiSurfaces(surfaces: Map<String, A2uiSurfaceState>) {
        val next = surfaces.toPersistentMap()
        uiState.update { current ->
            if (current.a2uiSurfaces == next) {
                current
            } else {
                current.copy(a2uiSurfaces = next)
            }
        }
    }

    private fun observeA2uiEvents() {
        scope.launch {
            wsChatBridge.a2uiEvents.collect { event ->
                event.conversationId?.let(::ensureA2uiConversation)
                a2uiLiveEventSeen = true
                a2uiSurfaceManager.apply(event)
                val surfaces = a2uiSurfaceManager.surfaces.value.toPersistentMap()
                val frames = event.toDebugFrames()
                uiState.update { current ->
                    current.copy(
                        a2uiSurfaces = surfaces,
                        a2uiDebugFrames = if (frames.isEmpty()) {
                            current.a2uiDebugFrames
                        } else {
                            (current.a2uiDebugFrames + frames)
                                .takeLast(MAX_A2UI_DEBUG_FRAMES)
                                .toImmutableList()
                        },
                        a2uiFrameCount = current.a2uiFrameCount + event.messages.size,
                    )
                }
            }
        }
    }

    fun ensureA2uiConversation(conversationId: String) {
        if (a2uiConversationId == conversationId) return
        a2uiConversationId = conversationId
        a2uiHistorySignature = null
        a2uiHistoryAppliedCount = 0
        a2uiHistoryAppliedPrefix = null
        a2uiLiveEventSeen = false
        a2uiSurfaceManager.clear()
        uiState.update { it.copy(a2uiSurfaces = persistentMapOf()) }
    }

    fun syncA2uiHistorySnapshot(
        conversationId: String,
        messages: List<A2uiMessage>,
    ): Map<String, A2uiSurfaceState> {
        ensureA2uiConversation(conversationId)
        if (!a2uiLiveEventSeen) {
            val signature = messages.hashCode()
            if (signature != a2uiHistorySignature) {
                val appliedPrefix = a2uiHistoryAppliedPrefix
                val isAppend = messages.size >= a2uiHistoryAppliedCount &&
                    appliedPrefix != null &&
                    messages.subList(0, a2uiHistoryAppliedCount) == appliedPrefix

                if (isAppend) {
                    val trailing = messages.subList(a2uiHistoryAppliedCount, messages.size)
                    if (trailing.isNotEmpty()) {
                        a2uiSurfaceManager.applyMessages(trailing)
                    }
                } else {
                    a2uiSurfaceManager.replaceWith(messages)
                }

                a2uiHistorySignature = signature
                a2uiHistoryAppliedCount = messages.size
                a2uiHistoryAppliedPrefix = messages.toList()
            }
        }
        return a2uiSurfaceManager.surfaces.value
    }

    private fun observeA2uiActionOutcomes() {
        scope.launch {
            wsChatBridge.events.collect { event ->
                val outcome = event as? WsTimelineEvent.UserActionOutcome ?: return@collect
                handleA2uiActionOutcome(outcome)
            }
        }
    }

    private fun handleA2uiActionOutcome(outcome: WsTimelineEvent.UserActionOutcome) {
        val pending = pendingA2uiActions.remove(outcome.frameId)
        if (pending == null) {
            Log.w(TAG, "Dropping stale A2UI action outcome frameId=${outcome.frameId} outcome=${outcome.outcome}")
            return
        }
        uiState.update { current ->
            val nextCount = (current.a2uiResolvedActionCounters[pending.action.surfaceId] ?: 0) + 1
            current.copy(
                a2uiResolvedActionCounters = current.a2uiResolvedActionCounters
                    .toPersistentMap()
                    .put(pending.action.surfaceId, nextCount),
                a2uiActionSnackbar = outcome.toSnackbar(pending.action),
            )
        }
        if (outcome.expectsFollowUpTurn()) {
            startA2uiThinkingIndicator()
        }
    }

    private fun WsTimelineEvent.UserActionOutcome.expectsFollowUpTurn(): Boolean = outcome.lowercase() in setOf(
        "injected_as_input",
        "matched_approval",
    )

    private fun startA2uiThinkingIndicator() {
        a2uiThinkingTimeoutJob?.cancel()
        a2uiThinkingStartMessageCount = uiState.value.messages.size
        uiState.update {
            it.copy(
                isStreaming = true,
                isAgentTyping = true,
                a2uiThinkingDelayMessage = null,
            )
        }
        a2uiThinkingTimeoutJob = scope.launch {
            delay(A2UI_THINKING_TIMEOUT_MS)
            if (a2uiThinkingStartMessageCount != null) {
                a2uiThinkingStartMessageCount = null
                uiState.update {
                    it.copy(
                        isStreaming = true,
                        isAgentTyping = true,
                        a2uiThinkingDelayMessage = A2UI_THINKING_DELAY_MESSAGE,
                    )
                }
            }
        }
    }

    fun clearA2uiThinkingOnResponse() {
        a2uiThinkingStartMessageCount = null
        a2uiThinkingTimeoutJob?.cancel()
        a2uiThinkingTimeoutJob = null
    }

    fun markA2uiThinkingDelayMessageShown() {
        uiState.update { it.copy(a2uiThinkingDelayMessage = null) }
    }

    fun markA2uiActionSnackbarShown(id: Long) {
        uiState.update { current ->
            if (current.a2uiActionSnackbar?.id == id) {
                current.copy(a2uiActionSnackbar = null)
            } else {
                current
            }
        }
    }

    fun dismissA2uiSurface(surfaceId: String) {
        a2uiSurfaceManager.dismissSurface(surfaceId)
        publishA2uiSurfaces(a2uiSurfaceManager.surfaces.value)
    }

    fun submitA2uiAction(action: A2uiAction) {
        val resolvedAction = if (action.conversationId.isNullOrBlank()) {
            val currentConversationId = activeConversationId()
            if (currentConversationId.isNullOrBlank()) {
                chatBannerController.showComposerError("Couldn't send action. No active conversation is available.")
                return
            }
            action.copy(conversationId = currentConversationId)
        } else {
            action
        }
        if (submitA2uiToolApprovalViaRest(resolvedAction)) return

        val result = wsChatBridge.sendA2uiAction(resolvedAction)
        Log.i(
            "A2UI",
            "submitA2uiAction surfaceId=${resolvedAction.surfaceId} event=${resolvedAction.name} " +
                "conversationId=${resolvedAction.conversationId} result=$result",
        )
        when (result) {
            is A2uiActionDispatchResult.Queued -> {
                pendingA2uiActions[result.frameId] = PendingA2uiAction(action = resolvedAction)
                chatBannerController.showComposerError("Action queued until the chat connection returns")
            }
            is A2uiActionDispatchResult.Sent -> {
                pendingA2uiActions[result.frameId] = PendingA2uiAction(action = resolvedAction)
            }
            A2uiActionDispatchResult.Failed -> {
                chatBannerController.showComposerError("Couldn't send action. Check the chat connection and try again.")
            }
        }
    }

    private fun submitA2uiToolApprovalViaRest(action: A2uiAction): Boolean {
        val submission = action.toToolApprovalSubmission() ?: return false
        chatApprovalController.submitApproval(
            requestId = submission.approvalRequestId,
            toolCallIds = listOf(submission.callId),
            approve = submission.approve,
            reason = submission.reason,
            activeConversationIdOverride = action.conversationId,
        )
        return true
    }

    private fun A2uiFrameEvent.toDebugFrames(): List<A2uiDebugFrameUi> = messages.mapIndexed { index, message ->
        A2uiDebugFrameUi(
            id = listOf(
                frameId.orEmpty().ifBlank { "frame" },
                requestId.orEmpty().ifBlank { "request" },
                message.surfaceId.ifBlank { "surface" },
                message.messageType,
                index.toString(),
                (++nextA2uiDebugFrameId).toString(),
            ).joinToString(":"),
            transport = transport,
            messageType = message.messageType,
            surfaceId = message.surfaceId.takeIf { it.isNotBlank() },
            conversationId = conversationId,
            requestId = requestId,
        )
    }

    private fun WsTimelineEvent.UserActionOutcome.toSnackbar(action: A2uiAction): A2uiActionSnackbarUi {
        val normalized = outcome.lowercase()
        val decision = action.context["decision"]?.jsonPrimitive?.contentOrNull
        val message = when (normalized) {
            "matched_approval" -> when (decision) {
                "deny", "rejected", "timeout" -> "Denied"
                else -> "Approved"
            }
            "injected_as_input" -> "Sent"
            "recorded_only" -> "Saved"
            "rejected" -> reason?.takeIf { it.isNotBlank() }?.let { "Could not send: $it" } ?: "Could not send"
            "error" -> reason?.takeIf { it.isNotBlank() }?.let { "Something went wrong: $it" } ?: "Something went wrong"
            else -> "Action updated"
        }
        val retryable = normalized in setOf("rejected", "error") && idempotent
        return A2uiActionSnackbarUi(
            id = ++nextA2uiSnackbarId,
            message = message,
            actionLabel = if (retryable) "Retry" else null,
            duration = if (retryable) SnackbarDuration.Indefinite else SnackbarDuration.Short,
            retryAction = action.takeIf { retryable },
        )
    }

    private data class PendingA2uiAction(
        val action: A2uiAction,
        val createdAtMillis: Long = System.currentTimeMillis(),
    )
}
