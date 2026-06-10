package com.letta.mobile.feature.chat.render

import androidx.compose.material3.SnackbarDuration
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import com.letta.mobile.data.chat.runtime.chatScreenStatusOf
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.ProjectBugReport
import com.letta.mobile.data.model.UiMessage
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

@androidx.compose.runtime.Immutable
internal data class ProjectChatContext(
    val identifier: String,
    val name: String,
    val lettaFolderId: String? = null,
    val filesystemPath: String? = null,
    val gitUrl: String? = null,
    val lastSyncAt: String? = null,
    val activeCodingAgents: String? = null,
)

@androidx.compose.runtime.Immutable
internal data class PendingToolCall(
    val id: String,
    val name: String,
    val startedAt: Long = System.currentTimeMillis(),
)

@androidx.compose.runtime.Immutable
internal data class A2uiDebugFrameUi(
    val id: String,
    val transport: String,
    val messageType: String,
    val surfaceId: String?,
    val conversationId: String?,
    val requestId: String?,
)

@androidx.compose.runtime.Immutable
internal data class A2uiActionSnackbarUi(
    val id: Long,
    val message: String,
    val actionLabel: String? = null,
    val duration: SnackbarDuration = SnackbarDuration.Short,
    val retryAction: com.letta.mobile.data.a2ui.A2uiAction? = null,
)

internal enum class ProjectBriefSectionKey {
    Description,
    KeyDecisions,
    TechStack,
    ActiveGoals,
    RecentChanges,
}

@androidx.compose.runtime.Immutable
internal data class ProjectBriefSection(
    val key: ProjectBriefSectionKey,
    val blockLabel: String,
    val content: String,
    val updatedAt: String? = null,
)

@androidx.compose.runtime.Immutable
internal data class ProjectBriefUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    // ImmutableMap so Compose treats this whole state as stable â€” raw
    // kotlin.collections.Map is an unstable interface type to the
    // compiler (it could be a MutableMap at runtime). See o7ob.2.6.
    val sections: kotlinx.collections.immutable.ImmutableMap<ProjectBriefSectionKey, ProjectBriefSection> =
        kotlinx.collections.immutable.persistentMapOf(),
    val error: String? = null,
)

internal enum class BugSeverity(val wireValue: String) {
    Critical("critical"),
    High("high"),
    Medium("medium"),
    Low("low"),
}

@androidx.compose.runtime.Immutable
internal data class ProjectBugReportDraft(
    val title: String = "",
    val description: String = "",
    val severity: BugSeverity = BugSeverity.Medium,
    val tags: ImmutableList<String> = persistentListOf(),
    val attachmentReferences: ImmutableList<String> = persistentListOf(),
)

@androidx.compose.runtime.Immutable
internal data class ProjectBugReportUiState(
    val isSubmitting: Boolean = false,
    val recentReports: ImmutableList<ProjectBugReport> = persistentListOf(),
    val lastSubmittedPrompt: String? = null,
    val error: String? = null,
)

internal enum class ProjectAgentStatusTone {
    Neutral,
    Good,
    Busy,
    Error,
}

@androidx.compose.runtime.Immutable
internal data class ProjectAgentActivity(
    val id: String,
    val name: String,
    val statusLabel: String,
    val statusTone: ProjectAgentStatusTone,
    val detail: String? = null,
    val model: String? = null,
    val lastActivity: String? = null,
)

@androidx.compose.runtime.Immutable
internal data class ProjectAgentsUiState(
    val isLoading: Boolean = false,
    val agents: ImmutableList<ProjectAgentActivity> = persistentListOf(),
    val error: String? = null,
)

@androidx.compose.runtime.Immutable
internal data class ContextWindowUiState(
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
 * letta-mobile-cbjh.
 */
internal sealed interface ChatTransport {
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

internal sealed interface ConversationState {
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
internal fun com.letta.mobile.data.chat.runtime.ChatSessionState.toConversationState(): ConversationState {
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
internal fun com.letta.mobile.data.chat.runtime.ChatConnectionState.toConversationState(
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
internal data class ChatUiState(
    val conversationState: ConversationState = ConversationState.Loading,
    val messages: ImmutableList<UiMessage> = persistentListOf(),
    val messageListChange: ChatMessageListChange = ChatMessageListChange.Full,
    val isLoadingMessages: Boolean = true,
    val isLoadingOlderMessages: Boolean = false,
    val hasMoreOlderMessages: Boolean = false,
    val isStreaming: Boolean = false,
    val isAgentTyping: Boolean = false,
    val pendingTools: ImmutableList<PendingToolCall> = persistentListOf(),
    val agentName: String = "",
    val error: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val activeApprovalRequestId: String? = null,
    val collapsedRunIds: kotlinx.collections.immutable.ImmutableSet<String> = persistentSetOf(),
    val expandedReasoningMessageIds: kotlinx.collections.immutable.ImmutableSet<String> = persistentSetOf(),
    val projectBrief: ProjectBriefUiState = ProjectBriefUiState(),
    val bugReports: ProjectBugReportUiState = ProjectBugReportUiState(),
    val projectAgents: ProjectAgentsUiState = ProjectAgentsUiState(),
    val contextWindow: ContextWindowUiState = ContextWindowUiState(),
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val isSearching: Boolean = false,
    val searchResults: ImmutableList<ParsedSearchMessage> = persistentListOf(),
    val a2uiDebugFrames: ImmutableList<A2uiDebugFrameUi> = persistentListOf(),
    val a2uiSurfaces: ImmutableMap<String, A2uiSurfaceState> = persistentMapOf(),
    val a2uiResolvedActionCounters: ImmutableMap<String, Int> = persistentMapOf(),
    val a2uiActionSnackbar: A2uiActionSnackbarUi? = null,
    val a2uiThinkingDelayMessage: String? = null,
    val transport: ChatTransport = ChatTransport.Rest,
    val a2uiFrameCount: Int = 0,
)
