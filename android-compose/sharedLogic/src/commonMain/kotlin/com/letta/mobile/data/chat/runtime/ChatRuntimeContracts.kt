package com.letta.mobile.data.chat.runtime

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage

@Immutable
enum class ChatConnectionState {
    Demo,
    Loading,
    ConfigNeeded,
    Offline,
    NoConversations,
    Live,
    Sending,
    StreamDisconnected,
    SendFailed,
}

@Immutable
data class ChatConversationSummary(
    val id: String,
    val title: String,
    val agentName: String,
    val updatedAtLabel: String,
    val lastMessagePreview: String,
    val unreadCount: Int = 0,
    val agentId: String? = null,
    val archived: Boolean = false,
)

fun ChatConversationSummary.displayTitle(maxLength: Int = 56): String {
    val cleanTitle = title.trim()
    if (cleanTitle.isNotBlank() && !cleanTitle.startsWith("Conversation ", ignoreCase = true)) {
        return cleanTitle
    }
    val previewTitle = lastMessagePreview
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.removePrefix("#")
        ?.trim()
        ?.takeUnless { it.equals("Loaded from backend", ignoreCase = true) }
        .orEmpty()
    return when {
        previewTitle.isBlank() -> cleanTitle.ifBlank { "New conversation" }
        previewTitle.length <= maxLength -> previewTitle
        else -> previewTitle.take((maxLength - 1).coerceAtLeast(1)).trimEnd() + "…"
    }
}

@Immutable
data class ChatConversationGroup(
    val key: String,
    val agentName: String,
    val conversations: List<ChatConversationSummary>,
) {
    val unreadCount: Int
        get() = conversations.sumOf { it.unreadCount }
}

@Immutable
data class ChatComposerState(
    val text: String = "",
    val pendingImageAttachments: List<MessageContentPart.Image> = emptyList(),
    val error: ChatComposerError? = null,
) {
    val hasPayload: Boolean
        get() = text.isNotBlank() || pendingImageAttachments.isNotEmpty()
}

fun groupConversationsByAgentName(
    conversations: List<ChatConversationSummary>,
): List<ChatConversationGroup> =
    conversations
        .groupBy { it.agentGroupKey() }
        .map { (key, groupedConversations) ->
            ChatConversationGroup(
                key = key,
                agentName = groupedConversations.first().agentDisplayName(),
                conversations = groupedConversations,
            )
        }
        .sortedBy { it.agentName.lowercase() }

fun Conversation.toChatConversationSummary(
    agentNamesById: Map<String, String> = emptyMap(),
): ChatConversationSummary {
    val agentIdValue = agentId.value
    val apiAgentName = agentName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val agentDisplayName = agentNamesById[agentIdValue]
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: apiAgentName
        ?: agentIdValue
    val updatedLabel = lastMessageAt ?: updatedAt ?: createdAt ?: "Remote"
    return ChatConversationSummary(
        id = id.value,
        title = summary?.takeIf { it.isNotBlank() } ?: "Conversation ${id.value.takeLast(6)}",
        agentName = agentDisplayName,
        updatedAtLabel = updatedLabel,
        lastMessagePreview = "Loaded from backend",
        agentId = agentIdValue,
        archived = archived == true,
    )
}

fun Iterable<Conversation>.toChatConversationSummaries(
    agentNamesById: Map<String, String> = emptyMap(),
): List<ChatConversationSummary> =
    map { it.toChatConversationSummary(agentNamesById) }

fun Conversation.isDefaultShimConversation(): Boolean =
    id.value.startsWith(DEFAULT_SHIM_CONVERSATION_PREFIX)

private fun ChatConversationSummary.agentDisplayName(): String =
    agentName.trim().ifBlank { UNKNOWN_AGENT_LABEL }

private fun ChatConversationSummary.agentGroupKey(): String =
    agentDisplayName().lowercase()

private const val UNKNOWN_AGENT_LABEL = "Unknown agent"
private const val DEFAULT_SHIM_CONVERSATION_PREFIX = "conv-default-"

@Immutable
enum class ChatComposerError {
    MaxAttachmentCountExceeded,
    MaxTotalBase64BytesExceeded,
    AttachmentLoadFailed,
}

@Immutable
data class ChatSessionState(
    val conversations: List<ChatConversationSummary> = emptyList(),
    val selectedConversationId: String? = null,
    val messagesByConversationId: Map<String, List<UiMessage>> = emptyMap(),
    val composer: ChatComposerState = ChatComposerState(),
    val connectionState: ChatConnectionState = ChatConnectionState.Loading,
    val isRemoteBacked: Boolean = true,
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val selectionGeneration: Long = 0L,
) {
    val selectedConversation: ChatConversationSummary?
        get() = conversations.firstOrNull { it.id == selectedConversationId }

    val selectedMessages: List<UiMessage>
        get() = selectedConversationId?.let { messagesByConversationId[it] }.orEmpty()
}

sealed interface ChatAction {
    data object RetryConnection : ChatAction
    data class SelectConversation(val conversationId: String) : ChatAction
    data class UpdateComposerText(val text: String) : ChatAction
    data class AttachImage(val image: MessageContentPart.Image) : ChatAction
    data class RemoveImageAttachment(val index: Int) : ChatAction
    data object Send : ChatAction
}
