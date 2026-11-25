package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import com.letta.mobile.data.chat.runtime.chatScreenStatusOf
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.search.MessageSearchSelection
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

@androidx.compose.runtime.Immutable
data class PendingToolCall(
    val id: String,
    val name: String,
    val startedAt: Long,
)

@androidx.compose.runtime.Immutable
enum class ChatSnackbarDuration {
    Short,
    Indefinite;

    companion object {
        fun forRetryableAction(retryable: Boolean): ChatSnackbarDuration =
            if (retryable) Indefinite else Short
    }
}

@androidx.compose.runtime.Immutable
data class A2uiActionSnackbarUi(
    val id: Long,
    val message: String,
    val actionLabel: String? = null,
    val duration: ChatSnackbarDuration = ChatSnackbarDuration.Short,
    val retryAction: com.letta.mobile.data.a2ui.A2uiAction? = null,
)

@androidx.compose.runtime.Immutable
data class ContextWindowUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val maxTokens: Int = 0,
    val currentTokens: Int = 0,
    val messageCount: Int = 0,
    val systemTokens: Int = 0,
    val coreMemoryTokens: Int = 0,
    val externalMemoryTokens: Int = 0,
    val summaryMemoryTokens: Int = 0,
    val toolTokens: Int = 0,
    val messageTokens: Int = 0,
    val archivalMemoryCount: Int = 0,
    val recallMemoryCount: Int = 0,
) {
    val usagePercent: Int
        get() = if (maxTokens > 0) ((currentTokens.toFloat() / maxTokens.toFloat()) * 100).toInt().coerceIn(0, 100) else 0
}

/**
 * Chat transport indicator surfaced in the top app bar so we can verify
 * at a glance whether the current session is on the REST+SSE path or the
 * /shim/v1/mobile WebSocket path. A2UI rendering only fires on the WS
 * path (the splitter lives server-side in the WS host); rendering tests
 * against a REST-bound agent silently produce blank blocks. See
 *.
 */
sealed interface ChatTransport {
    @androidx.compose.runtime.Immutable
    data object Rest : ChatTransport

    @androidx.compose.runtime.Immutable
    data object WsIdle : ChatTransport

    @androidx.compose.runtime.Immutable
    data object WsConnecting : ChatTransport

    @androidx.compose.runtime.Immutable
    data class WsConnected(
        val a2uiEnabled: Boolean,
        val catalog: String?,
    ) : ChatTransport

    @androidx.compose.runtime.Immutable
    data class WsDisconnected(val code: Int, val reason: String) : ChatTransport
}

sealed interface ConversationState {
    @androidx.compose.runtime.Immutable
    data object Loading : ConversationState

    @androidx.compose.runtime.Immutable
    data class Ready(val conversationId: String) : ConversationState

    @androidx.compose.runtime.Immutable
    data object NoConversation : ConversationState

    @androidx.compose.runtime.Immutable
    data class Error(val message: String) : ConversationState
}

/**
 * Single source of truth for mapping the shared KMP [ChatConnectionState] into
 * the Android UI's [ConversationState].  The mapping is now derived from the
 * shared [ChatScreenStatus] descriptor so Android and Desktop compute the same
 * meaning for the same state — only the Android-specific [ConversationState]
 * type lives here.
 *
 * Used by AdminChatViewModel and the chat coordinators so Android never
 * re-defines what "loading", "offline", "config needed", or "ready" mean —
 * that logic lives in the shared [chatScreenStatusOf] function.
 */
fun com.letta.mobile.data.chat.runtime.ChatSessionState.toConversationState(): ConversationState {
    return when (val status = chatScreenStatusOf(this)) {
        is ChatScreenStatus.Loading -> ConversationState.Loading
        is ChatScreenStatus.ConfigNeeded ->
            ConversationState.Error(status.errorMessage ?: "Backend configuration required")
        is ChatScreenStatus.BackendOffline ->
            ConversationState.Error(status.errorMessage ?: "Backend offline")
        is ChatScreenStatus.NoConversations -> ConversationState.NoConversation
        is ChatScreenStatus.SendFailed ->
            // SendFailed keeps the chat readable — treat as Ready so the message
            // list remains visible and the user can edit the composer for retry.
            // The selected conversation id lives on the session state, not the descriptor.
            selectedConversationId?.let { ConversationState.Ready(it) }
                ?: ConversationState.NoConversation
        is ChatScreenStatus.Ready ->
            status.selectedConversationId?.let { ConversationState.Ready(it) }
                ?: ConversationState.NoConversation
    }
}

/**
 * Backward-compat shim used by coordinators that still pass the connection state
 * and error message as separate parameters.  Delegates to the [ChatSessionState]
 * overload so both call sites go through [chatScreenStatusOf].
 */
fun com.letta.mobile.data.chat.runtime.ChatConnectionState.toConversationState(
    selectedConversationId: String?,
    errorMessage: String?,
): ConversationState {
    val syntheticState = com.letta.mobile.data.chat.runtime.ChatSessionState(
        connectionState = this,
        selectedConversationId = selectedConversationId,
        errorMessage = errorMessage,
    )
    return syntheticState.toConversationState()
}

@androidx.compose.runtime.Immutable
data class GoalStatusUi(
    val objective: String = "",
    val status: String = "",
    val activeTimeSeconds: Long = 0,
    val tokensUsed: Long = 0,
    val tokenBudget: Long? = null,
)

@androidx.compose.runtime.Immutable
data class ChatUiState(
    val conversationState: ConversationState = ConversationState.Loading,
    val messages: ImmutableList<UiMessage> = persistentListOf(),
    val messageListChange: ChatMessageListChange = ChatMessageListChange.Full,
    val isLoadingMessages: Boolean = true,
    val isLoadingOlderMessages: Boolean = false,
    val hasMoreOlderMessages: Boolean = false,
    /** Phase for the visible conversation, or null when its run state is unavailable. */
    val runPhase: com.letta.mobile.data.presence.RunPhase? = null,
    val isStreaming: Boolean = false,
    val isAgentTyping: Boolean = false,
    val pendingTools: ImmutableList<PendingToolCall> = persistentListOf(),
    val agentName: String = "",
    // letta-mobile-c4igq.4: active conversation agent id, forwarded to the render
    // cache so foreign-agent messages are scoped out of this conversation.
    val agentId: String? = null,
    val error: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val activeApprovalRequestId: String? = null,
    /**
     * Approval requests answered while the connection was down, waiting in
     * the outbox; their cards say so and take no second answer.
     */
    val queuedApprovalRequestIds: kotlinx.collections.immutable.ImmutableSet<String> = persistentSetOf(),
    val collapsedRunIds: kotlinx.collections.immutable.ImmutableSet<String> = persistentSetOf(),
    val expandedReasoningMessageIds: kotlinx.collections.immutable.ImmutableSet<String> = persistentSetOf(),
    val contextWindow: ContextWindowUiState = ContextWindowUiState(),
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val isSearching: Boolean = false,
    val searchResults: ImmutableList<ParsedSearchMessage> = persistentListOf(),
    /** The chat search's mode and role chips. */
    val searchSelection: MessageSearchSelection = MessageSearchSelection(),
    val a2uiSurfaces: ImmutableMap<String, A2uiSurfaceState> = persistentMapOf(),
    val a2uiResolvedActionCounters: ImmutableMap<String, Int> = persistentMapOf(),
    val a2uiActionSnackbar: A2uiActionSnackbarUi? = null,
    val a2uiThinkingDelayMessage: String? = null,
    val transport: ChatTransport = ChatTransport.Rest,
    val goalStatus: GoalStatusUi? = null,
    val isGoalStatusLoading: Boolean = false,
    /**
     * An abort has been REQUESTED for the in-flight turn
     * but the authoritative terminal frame has not landed yet.
     *
     * This flag is deliberately NOT self-clearing: the run resolves to idle only
     * when [isStreaming] goes false, which happens exclusively on the terminal
     * lifecycle frame (or the transport's bounded synthetic-terminal fallback).
     * Read [isCancellingRun] rather than this raw flag — it is scoped to
     * [isStreaming] so a stale `true` can never leak "stopping…" onto a fresh
     * turn, and so cancel state resolves atomically with the terminal.
     */
    val isCancelling: Boolean = false,
    /**
     * Messages the user sent during a turn in the visible conversation,
     * waiting to run in this order. Empty when nothing is queued.
     */
    val sendQueue: com.letta.mobile.data.chat.send.ConversationSendQueue =
        com.letta.mobile.data.chat.send.ConversationSendQueue(),
    /**
     * The last run failed and nothing has been sent since, whether or not
     * the page has shown (and so cleared) [error]. The ambient glow stays "failed" while it holds.
     */
    val runFailed: Boolean = false,
) {
    /**
     * True only while a cancel is outstanding AND the
     * turn is still streaming. The composer stays blocked for the whole window;
     * a second Stop press while this is true force-clears locally.
     */
    val isCancellingRun: Boolean get() = isStreaming && isCancelling

    /**
     * A run is in flight, from the send until the turn's terminal, through
     * every tool round, approval wait and gap between steps: the agent is typing (nothing has
     * answered yet) or the run streams (the composer's Stop button is up). Every working cue (the
     * ambient glow on the page and the docked panel, the dock's busy mascot) reads this one fact.
     */
    val isRunInFlight: Boolean get() = isStreaming || isAgentTyping
}
