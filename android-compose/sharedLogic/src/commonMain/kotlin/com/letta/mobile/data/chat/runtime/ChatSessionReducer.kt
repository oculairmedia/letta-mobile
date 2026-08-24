package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage

object ChatSessionReducer {
    fun beginConversationLoad(
        state: ChatSessionState,
        statusMessage: String = "Loading conversations",
    ): ChatSessionState =
        state.copy(
            isLoading = true,
            isSending = false,
            isRemoteBacked = true,
            connectionState = ChatConnectionState.Loading,
            statusMessage = statusMessage,
            errorMessage = null,
        )

    fun configNeeded(
        state: ChatSessionState,
        statusMessage: String = "Backend configuration required",
        errorMessage: String = "Set a server URL in Settings.",
    ): ChatSessionState =
        state.copy(
            conversations = emptyList(),
            selectedConversationId = null,
            messagesByConversationId = emptyMap(),
            isLoading = false,
            isSending = false,
            isRemoteBacked = false,
            connectionState = ChatConnectionState.ConfigNeeded,
            statusMessage = statusMessage,
            errorMessage = errorMessage,
        )

    fun conversationLoadFailed(
        state: ChatSessionState,
        errorMessage: String,
        statusMessage: String = "Backend offline",
    ): ChatSessionState =
        state.copy(
            conversations = emptyList(),
            selectedConversationId = null,
            messagesByConversationId = emptyMap(),
            isLoading = false,
            isSending = false,
            isRemoteBacked = false,
            connectionState = ChatConnectionState.Offline,
            statusMessage = statusMessage,
            errorMessage = errorMessage,
        )

    fun conversationsLoaded(
        state: ChatSessionState,
        conversations: List<ChatConversationSummary>,
        liveStatusMessage: String = "Live",
        emptyStatusMessage: String = "No conversations",
    ): ChatSessionState {
        val selectedId = conversations.firstOrNull()?.id
        return state.copy(
            conversations = conversations,
            selectedConversationId = selectedId,
            messagesByConversationId = emptyMap(),
            composer = ChatComposerState(),
            isLoading = false,
            isSending = false,
            isRemoteBacked = true,
            snapshotAvailability = SnapshotAvailability.None,
            remoteSyncState = RemoteSyncState.Idle,
            connectionState = if (conversations.isEmpty()) {
                ChatConnectionState.NoConversations
            } else {
                ChatConnectionState.Live
            },
            statusMessage = if (conversations.isEmpty()) emptyStatusMessage else liveStatusMessage,
            errorMessage = null,
            selectionGeneration = if (selectedId == null) {
                state.selectionGeneration
            } else {
                state.selectionGeneration + 1
            },
        )
    }

    /**
     * Remove a single conversation in place without rebuilding the whole session.
     * Deleting a background conversation leaves the active selection and its
     * hydrated messages untouched (no reload, no flash). Deleting the active
     * conversation falls back to the next one and bumps the selection generation
     * so the caller can hydrate it.
     */
    fun conversationDeleted(
        state: ChatSessionState,
        conversationId: String,
        emptyStatusMessage: String = "No conversations",
    ): ChatSessionState {
        if (state.conversations.none { it.id == conversationId }) return state
        val remaining = state.conversations.filterNot { it.id == conversationId }
        val messages = state.messagesByConversationId - conversationId
        if (state.selectedConversationId != conversationId) {
            return state.copy(
                conversations = remaining,
                messagesByConversationId = messages,
                connectionState = if (remaining.isEmpty()) {
                    ChatConnectionState.NoConversations
                } else {
                    state.connectionState
                },
                statusMessage = if (remaining.isEmpty()) emptyStatusMessage else state.statusMessage,
            )
        }
        val nextSelected = remaining.firstOrNull()
        return state.copy(
            conversations = remaining,
            messagesByConversationId = messages,
            selectedConversationId = nextSelected?.id,
            composer = ChatComposerState(),
            isSending = false,
            isLoading = nextSelected != null && state.isRemoteBacked,
            snapshotAvailability = SnapshotAvailability.None,
            remoteSyncState = RemoteSyncState.Idle,
            connectionState = when {
                nextSelected == null -> ChatConnectionState.NoConversations
                state.isRemoteBacked -> ChatConnectionState.Loading
                else -> state.connectionState
            },
            statusMessage = when {
                nextSelected == null -> emptyStatusMessage
                state.isRemoteBacked -> "Loading messages"
                else -> state.statusMessage
            },
            errorMessage = null,
            selectionGeneration = if (nextSelected != null) {
                state.selectionGeneration + 1
            } else {
                state.selectionGeneration
            },
        )
    }

    fun retryConnection(
        current: ChatSessionState,
        initial: ChatSessionState,
    ): ChatSessionState =
        initial.copy(selectionGeneration = current.selectionGeneration + 1)

    fun updateComposerText(
        state: ChatSessionState,
        text: String,
    ): ChatSessionState =
        state.copy(composer = ChatComposerPolicy.updateText(state.composer, text))

    fun attachImage(
        state: ChatSessionState,
        image: MessageContentPart.Image,
        limits: AttachmentLimits = AttachmentLimits.Default,
    ): ChatSessionState =
        state.copy(composer = ChatComposerPolicy.attachImage(state.composer, image, limits))

    fun removeImageAttachment(
        state: ChatSessionState,
        index: Int,
    ): ChatSessionState =
        state.copy(composer = ChatComposerPolicy.removeImageAttachment(state.composer, index))

    fun selectConversation(
        state: ChatSessionState,
        conversationId: String,
        remoteBacked: Boolean = state.isRemoteBacked,
        hasSnapshot: Boolean = false,
    ): ChatSessionState {
        if (!state.canSelect(conversationId)) return state
        val transition = SelectionTransition.create(state, remoteBacked, hasSnapshot)
        return state.copy(
            selectedConversationId = conversationId,
            conversations = state.conversations.clearUnreadFor(conversationId),
            composer = ChatComposerState(),
            isLoading = remoteBacked,
            connectionState = transition.connectionState,
            snapshotAvailability = transition.snapshotAvailability,
            remoteSyncState = transition.remoteSyncState,
            statusMessage = transition.statusMessage,
            errorMessage = null,
            selectionGeneration = transition.selectionGeneration,
        )
    }

    fun beginSelectedConversationHydrate(
        state: ChatSessionState,
        generation: Long,
        statusMessage: String = "Syncing...",
    ): ChatSessionState =
        if (!isCurrentSelection(state, generation)) {
            state
        } else {
            state.copy(
                isLoading = true,
                remoteSyncState = RemoteSyncState.Refreshing,
                statusMessage = statusMessage,
                errorMessage = null,
            )
        }

    fun hydrateCompleted(
        state: ChatSessionState,
        generation: Long,
        statusMessage: String = "Live",
    ): ChatSessionState =
        if (!isCurrentSelection(state, generation)) {
            state
        } else {
            state.copy(
                isLoading = false,
                connectionState = ChatConnectionState.Live,
                snapshotAvailability = SnapshotAvailability.Live,
                remoteSyncState = RemoteSyncState.Live,
                statusMessage = statusMessage,
                errorMessage = null,
            )
        }

    fun hydrateFailed(
        state: ChatSessionState,
        generation: Long,
        errorMessage: String,
        statusMessage: String = "Sync failed",
    ): ChatSessionState =
        if (!isCurrentSelection(state, generation)) {
            state
        } else {
            state.copy(
                isLoading = false,
                remoteSyncState = RemoteSyncState.Failed,
                statusMessage = statusMessage,
                errorMessage = errorMessage,
            )
        }

    fun streamDisconnected(
        state: ChatSessionState,
        generation: Long,
        errorMessage: String,
        statusMessage: String = "Stream disconnected",
    ): ChatSessionState =
        if (!isCurrentSelection(state, generation)) {
            state
        } else {
            state.copy(
                isLoading = false,
                connectionState = ChatConnectionState.StreamDisconnected,
                remoteSyncState = RemoteSyncState.StreamDisconnected,
                statusMessage = statusMessage,
                errorMessage = errorMessage,
            )
        }

    fun timelineMessagesUpdated(
        state: ChatSessionState,
        generation: Long,
        conversationId: String,
        messages: List<UiMessage>,
    ): ChatSessionState =
        if (!isCurrentSelection(state, generation)) {
            state
        } else {
            state.copy(
                messagesByConversationId = state.messagesByConversationId + (conversationId to messages),
                conversations = state.conversations.map { conversation ->
                    if (conversation.id == conversationId) {
                        conversation.copy(
                            lastMessagePreview = messages.lastPreviewOr(conversation.lastMessagePreview),
                            updatedAtLabel = messages.lastTimestampOr(conversation.updatedAtLabel),
                        )
                    } else {
                        conversation
                    }
                },
            )
        }

    fun beginSend(
        state: ChatSessionState,
        draft: ChatComposerSendDraft,
        statusMessage: String = "Sending",
    ): ChatSessionState =
        state.copy(
            composer = draft.nextState,
            isSending = true,
            connectionState = ChatConnectionState.Sending,
            statusMessage = statusMessage,
            errorMessage = null,
        )

    fun sendSucceeded(
        state: ChatSessionState,
        statusMessage: String = "Live",
    ): ChatSessionState =
        state.copy(
            isSending = false,
            connectionState = ChatConnectionState.Live,
            statusMessage = statusMessage,
            errorMessage = null,
        )

    fun sendFailed(
        state: ChatSessionState,
        text: String,
        attachments: List<MessageContentPart.Image>,
        errorMessage: String,
        statusMessage: String = "Send failed",
    ): ChatSessionState =
        state.copy(
            composer = ChatComposerPolicy.restoreAfterSendFailure(text, attachments),
            isSending = false,
            connectionState = ChatConnectionState.SendFailed,
            statusMessage = statusMessage,
            errorMessage = errorMessage,
        )

    fun queueLocalMessage(
        state: ChatSessionState,
        draft: ChatComposerSendDraft,
        message: UiMessage,
        preview: String,
    ): ChatSessionState {
        val conversationId = state.selectedConversationId ?: return state
        val currentMessages = state.messagesByConversationId[conversationId].orEmpty()
        return state.copy(
            composer = draft.nextState,
            conversations = state.conversations.map { conversation ->
                if (conversation.id == conversationId) {
                    conversation.copy(
                        updatedAtLabel = "Queued",
                        lastMessagePreview = preview.ifBlank { "Image attachment" },
                        unreadCount = 0,
                    )
                } else {
                    conversation
                }
            },
            messagesByConversationId = state.messagesByConversationId + (conversationId to currentMessages + message),
            isSending = false,
        )
    }

    fun canSend(state: ChatSessionState): Boolean =
        state.isRemoteBacked &&
            !state.isSending &&
            state.connectionState in sendEnabledStates

    fun shouldShowStatePanel(state: ChatSessionState): Boolean =
        state.connectionState == ChatConnectionState.ConfigNeeded ||
            (state.selectedConversationId == null && (state.connectionState == ChatConnectionState.Loading || state.connectionState == ChatConnectionState.Offline || state.connectionState == ChatConnectionState.NoConversations))

    fun isCurrentSelection(
        state: ChatSessionState,
        generation: Long,
    ): Boolean =
        generation == state.selectionGeneration

    private fun ChatSessionState.canSelect(conversationId: String): Boolean =
        conversationId != selectedConversationId && conversations.any { it.id == conversationId }

    private fun List<ChatConversationSummary>.clearUnreadFor(conversationId: String): List<ChatConversationSummary> =
        map { conversation ->
            if (conversation.id == conversationId) conversation.copy(unreadCount = 0) else conversation
        }

    private data class SelectionTransition(
        val connectionState: ChatConnectionState,
        val snapshotAvailability: SnapshotAvailability,
        val remoteSyncState: RemoteSyncState,
        val statusMessage: String,
        val selectionGeneration: Long,
    ) {
        companion object {
            fun create(
                state: ChatSessionState,
                remoteBacked: Boolean,
                hasSnapshot: Boolean,
            ): SelectionTransition =
                SelectionTransition(
                    connectionState = if (remoteBacked) ChatConnectionState.Live else state.connectionState,
                    snapshotAvailability = if (hasSnapshot) SnapshotAvailability.Persisted else SnapshotAvailability.None,
                    remoteSyncState = if (remoteBacked) RemoteSyncState.Refreshing else RemoteSyncState.Idle,
                    statusMessage = if (remoteBacked) "Syncing..." else state.statusMessage.orEmpty(),
                    selectionGeneration = state.selectionGeneration + if (remoteBacked) 1 else 0,
                )
        }
    }

    private fun List<UiMessage>.lastPreviewOr(fallback: String): String =
        lastOrNull { it.content.isNotBlank() }?.content?.lineSequence()?.firstOrNull()?.take(140) ?: fallback

    private fun List<UiMessage>.lastTimestampOr(fallback: String): String =
        lastOrNull { it.timestamp.isNotBlank() }?.timestamp ?: fallback

    private val sendEnabledStates = setOf(
        ChatConnectionState.Live,
        ChatConnectionState.SendFailed,
        ChatConnectionState.StreamDisconnected,
        ChatConnectionState.NoConversations,
    )

    private val panelStates = setOf(
        ChatConnectionState.ConfigNeeded,
        ChatConnectionState.Offline,
        ChatConnectionState.NoConversations,
    )
}
