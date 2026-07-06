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
     * Non-destructively refresh the conversation LIST from a fresh server fetch
     * (e.g. a periodic poll) without disturbing the open conversation. Unlike
     * [conversationsLoaded] — which resets selection, clears hydrated messages,
     * wipes the composer, and bumps the selection generation — this keeps all of
     * that intact and only:
     *   - replaces the visible, recency-ordered list with the server's,
     *   - preserves any local-only conversations not yet on the server (e.g. a
     *     freshly-created unsent chat identified by [keepLocalIds]) at the front,
     *   - drops hydrated messages for conversations that vanished server-side,
     *   - leaves the current selection untouched when it still exists (so the
     *     open chat, its stream, and its composer are never clobbered).
     *
     * The selection generation is NOT bumped: no re-hydrate is triggered, so a
     * poll landing while the agent is streaming can't reset the timeline.
     */
    fun conversationsRefreshed(
        state: ChatSessionState,
        conversations: List<ChatConversationSummary>,
        keepLocalIds: Set<String> = emptySet(),
        liveStatusMessage: String = "Live",
        emptyStatusMessage: String = "No conversations",
    ): ChatSessionState {
        // Only meaningful once we're remote-backed with a loaded list; ignore
        // otherwise so a late poll can't override a Demo/Offline/ConfigNeeded UI.
        if (!state.isRemoteBacked) return state
        val serverIds = conversations.mapTo(HashSet()) { it.id }
        // Keep local-only conversations (unsent/optimistic) that the server list
        // doesn't know about yet, pinned to the front (most-recent) so they don't
        // vanish under the incoming recency-ordered server list.
        val localOnly = state.conversations.filter { it.id !in serverIds && it.id in keepLocalIds }
        val merged = localOnly + conversations
        if (merged == state.conversations) return state
        val mergedIds = merged.mapTo(HashSet()) { it.id }
        return state.copy(
            conversations = merged,
            // Selection is never touched by a background refresh: the open chat,
            // its stream, and its composer stay bound. A conversation deleted
            // elsewhere simply drops out of the list; the selectedConversation
            // getter returns null gracefully if the selected id is now absent.
            // Drop hydrated messages for conversations that no longer exist so the
            // map doesn't grow unbounded; keep everything still present.
            messagesByConversationId = state.messagesByConversationId.filterKeys { it in mergedIds },
            connectionState = when {
                merged.isEmpty() -> ChatConnectionState.NoConversations
                // Don't downgrade an in-progress/failed transient state to Live.
                state.connectionState == ChatConnectionState.Live ||
                    state.connectionState == ChatConnectionState.NoConversations ->
                    ChatConnectionState.Live
                else -> state.connectionState
            },
            statusMessage = when {
                merged.isEmpty() -> emptyStatusMessage
                state.connectionState == ChatConnectionState.Live ||
                    state.connectionState == ChatConnectionState.NoConversations -> liveStatusMessage
                else -> state.statusMessage
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
    ): ChatSessionState {
        if (conversationId == state.selectedConversationId || state.conversations.none { it.id == conversationId }) {
            return state
        }

        val nextGeneration = if (remoteBacked) {
            state.selectionGeneration + 1
        } else {
            state.selectionGeneration
        }

        return state.copy(
            selectedConversationId = conversationId,
            conversations = state.conversations.map { conversation ->
                if (conversation.id == conversationId) {
                    conversation.copy(unreadCount = 0)
                } else {
                    conversation
                }
            },
            composer = ChatComposerState(),
            isLoading = remoteBacked,
            connectionState = if (remoteBacked) ChatConnectionState.Loading else state.connectionState,
            statusMessage = if (remoteBacked) "Loading messages" else state.statusMessage,
            errorMessage = null,
            selectionGeneration = nextGeneration,
        )
    }

    fun beginSelectedConversationHydrate(
        state: ChatSessionState,
        generation: Long,
        statusMessage: String = "Loading messages",
    ): ChatSessionState =
        if (!isCurrentSelection(state, generation)) {
            state
        } else {
            state.copy(
                isLoading = true,
                connectionState = ChatConnectionState.Loading,
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
                statusMessage = statusMessage,
                errorMessage = null,
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
                        conversation.copy(lastMessagePreview = messages.lastPreviewOr(conversation.lastMessagePreview))
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
            !state.isLoading &&
            state.connectionState in sendEnabledStates

    fun shouldShowStatePanel(state: ChatSessionState): Boolean =
        state.selectedConversationId == null ||
            (state.connectionState == ChatConnectionState.StreamDisconnected && state.selectedMessages.isEmpty()) ||
            state.connectionState in panelStates

    fun isCurrentSelection(
        state: ChatSessionState,
        generation: Long,
    ): Boolean =
        generation == state.selectionGeneration

    private fun List<UiMessage>.lastPreviewOr(fallback: String): String =
        lastOrNull { it.content.isNotBlank() }?.content?.lineSequence()?.firstOrNull()?.take(140) ?: fallback

    private val sendEnabledStates = setOf(
        ChatConnectionState.Live,
        ChatConnectionState.SendFailed,
        ChatConnectionState.StreamDisconnected,
        ChatConnectionState.NoConversations,
    )

    private val panelStates = setOf(
        ChatConnectionState.Loading,
        ChatConnectionState.ConfigNeeded,
        ChatConnectionState.Offline,
        ChatConnectionState.NoConversations,
    )
}
