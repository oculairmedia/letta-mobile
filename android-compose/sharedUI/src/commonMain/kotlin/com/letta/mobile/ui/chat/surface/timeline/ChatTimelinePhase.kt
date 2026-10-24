package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.Immutable
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState

/**
 * Which of the timeline's mutually exclusive bodies is on screen.
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

    /** No transcript rows: show the welcome, a waiting cue, or a settled empty state. */
    data class Empty(
        val hasConversation: Boolean,
        val showWelcome: Boolean,
        val waiting: Boolean,
    ) : ChatTimelinePhase

    /** The message list (paged or legacy). */
    data object Ready : ChatTimelinePhase

}

/**
 * @param paged the owner supplied a canonical paged timeline. That list reports its own loading,
 *   empty and history-error states (from Paging's load states), so only a conversation-level
 *   failure overrides it.
 */
internal fun chatTimelinePhaseOf(
    state: ChatUiState,
    paged: Boolean,
    canSendStarterPrompts: Boolean = true,
): ChatTimelinePhase {
    val conversation = state.conversationState
    if (conversation is ConversationState.Error) return ChatTimelinePhase.Failed(conversation.message)
    if (paged) return ChatTimelinePhase.Ready
    if (state.hasRenderableContent() || (canSendStarterPrompts && state.isRunInFlight)) return ChatTimelinePhase.Ready
    return chatTimelineEmptyPhaseOf(state, canSendStarterPrompts)
}

/** Phase shown when the timeline has no rows, including a confirmed empty paged history. */
internal fun chatTimelineEmptyPhaseOf(
    state: ChatUiState,
    canSendStarterPrompts: Boolean,
): ChatTimelinePhase {
    val conversation = state.conversationState
    return when {
        conversation is ConversationState.NoConversation && canSendStarterPrompts ->
            ChatTimelinePhase.Empty(hasConversation = false, showWelcome = true, waiting = false)
        conversation is ConversationState.Loading || state.isLoadingMessages -> ChatTimelinePhase.Loading
        else -> ChatTimelinePhase.Empty(
            hasConversation = conversation !is ConversationState.NoConversation,
            showWelcome = canSendStarterPrompts,
            waiting = !canSendStarterPrompts && state.isRunInFlight,
        )
    }
}

/** Anything a transcript row or generated surface would draw. */
private fun ChatUiState.hasRenderableContent(): Boolean = messages.isNotEmpty() || a2uiSurfaces.isNotEmpty()
