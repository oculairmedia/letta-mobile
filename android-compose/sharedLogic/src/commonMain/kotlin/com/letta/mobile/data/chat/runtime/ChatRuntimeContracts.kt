package com.letta.mobile.data.chat.runtime

import androidx.compose.runtime.Immutable
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
)

@Immutable
data class ChatComposerState(
    val text: String = "",
    val pendingImageAttachments: List<MessageContentPart.Image> = emptyList(),
    val error: ChatComposerError? = null,
) {
    val hasPayload: Boolean
        get() = text.isNotBlank() || pendingImageAttachments.isNotEmpty()
}

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
