package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.Immutable
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState

/**
 * letta-mobile-bglj6.1: which of the timeline's mutually exclusive bodies is on screen.
 *
 * Lifted from Android's ChatScreenLayout content phases (skeleton, error, no-conversation,
 * content) and desktop's ChatDetailContent (status panel, welcome, list). One pure function
 * decides it for both, so the two clients cannot drift on what "loading" or "empty" means.
 */
@Immutable
internal sealed interface ChatTimelinePhase {
    /** First load of a conversation's messages: the skeleton. */
    data object Loading : ChatTimelinePhase

    /** The conversation (or the connection to it) failed; offers a retry. */
    data class Failed(val message: String) : ChatTimelinePhase

    /**
     * Nothing to show yet: starter prompts. [hasConversation] is false when no conversation is
     * selected at all (Android's NoConversationChatContent), true for a fresh, empty one.
     */
    data class Welcome(val hasConversation: Boolean) : ChatTimelinePhase

    /** The message list (paged or legacy). */
    data object Ready : ChatTimelinePhase
}

/**
 * @param paged the owner supplied a canonical paged timeline. That list reports its own loading,
 *   empty and history-error states (from Paging's load states), so only a conversation-level
 *   failure overrides it.
 */
internal fun chatTimelinePhaseOf(state: ChatUiState, paged: Boolean): ChatTimelinePhase {
    val conversation = state.conversationState
    if (conversation is ConversationState.Error) return ChatTimelinePhase.Failed(conversation.message)
    if (paged) return ChatTimelinePhase.Ready
    val hasContent = state.hasRenderableContent()
    return when {
        hasContent -> ChatTimelinePhase.Ready
        conversation is ConversationState.NoConversation -> ChatTimelinePhase.Welcome(hasConversation = false)
        conversation is ConversationState.Loading -> ChatTimelinePhase.Loading
        state.isLoadingMessages -> ChatTimelinePhase.Loading
        else -> ChatTimelinePhase.Welcome(hasConversation = true)
    }
}

/** Anything the list would draw: messages, a run in flight, or a generated surface. */
private fun ChatUiState.hasRenderableContent(): Boolean =
    messages.isNotEmpty() || isStreaming || isAgentTyping || a2uiSurfaces.isNotEmpty()
