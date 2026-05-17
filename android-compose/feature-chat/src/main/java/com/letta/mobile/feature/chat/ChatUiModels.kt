package com.letta.mobile.feature.chat

import com.letta.mobile.bot.repository.ClientModeDirectoryEntry
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.ProjectBugReport
import com.letta.mobile.data.model.UiMessage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
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
    // ImmutableMap so Compose treats this whole state as stable — raw
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
internal data class ClientModeLocationUiState(
    val isLoading: Boolean = false,
    val currentPath: String? = null,
    val defaultPath: String? = null,
    val lastRequestedPath: String? = null,
    val error: String? = null,
)

@androidx.compose.runtime.Immutable
internal data class ClientModeFilesystemPickerUiState(
    val isVisible: Boolean = false,
    val isLoading: Boolean = false,
    val path: String? = null,
    val parent: String? = null,
    val entries: ImmutableList<ClientModeDirectoryEntry> = persistentListOf(),
    val truncated: Boolean = false,
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

@androidx.compose.runtime.Immutable
internal data class ChatUiState(
    val conversationState: ConversationState = ConversationState.Loading,
    val messages: ImmutableList<UiMessage> = persistentListOf(),
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
    val isClientModeEnabled: Boolean = false,
    val clientModeLocation: ClientModeLocationUiState = ClientModeLocationUiState(),
    val clientModeFilesystemPicker: ClientModeFilesystemPickerUiState = ClientModeFilesystemPickerUiState(),
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val isSearching: Boolean = false,
    val searchResults: ImmutableList<ParsedSearchMessage> = persistentListOf(),
    val a2uiDebugFrames: ImmutableList<A2uiDebugFrameUi> = persistentListOf(),
    /**
     * Surfaced when the LettaBot harness substituted a fresh conversation ID for
     * the one we requested (i.e. our requested conv was unrecoverable on the
     * gateway/SDK side, gateway opened a new conversation and reported it back
     * via session_init). The original Letta-server timeline rows for the prior
     * conversation remain visible; new client-mode turns persist under the new
     * conversation. Dismissable. See `letta-mobile-c87t`.
     */
    val clientModeConversationSwap: ClientModeConversationSwap? = null,
)

/**
 * Banner state for the gateway's conversation-substitution recovery path.
 * Emitted by `AdminChatViewModel` when `session_init.conversation_id` differs
 * from the conversation we asked the gateway to resume.
 */
internal data class ClientModeConversationSwap(
    val requestedConversationId: String,
    val newConversationId: String,
)
