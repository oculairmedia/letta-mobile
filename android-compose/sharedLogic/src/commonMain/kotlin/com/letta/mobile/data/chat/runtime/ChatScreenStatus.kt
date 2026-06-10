package com.letta.mobile.data.chat.runtime

import androidx.compose.runtime.Immutable

/**
 * Platform-neutral descriptor for the high-level condition of the chat screen,
 * computed by a pure function from [ChatSessionState] (and [ChatComposerState]
 * where needed).  Both Android and Compose Desktop switch on this sealed
 * interface to branch their UI instead of each platform independently
 * re-deriving the same meaning from the raw [ChatConnectionState] fields.
 *
 * Design notes:
 * - Every variant is a data class/object so the type is stable for Compose.
 * - No JVM-only types: safe for Kotlin/Native (commonMain).
 * - [errorMessage] surfaces the backend-supplied detail; a null value means
 *   the platform should supply its own fallback copy.
 * - [SendFailed] carries the retryable draft because both platforms restore
 *   the composer from it; the actual retry trigger lives platform-side.
 */
@Immutable
sealed interface ChatScreenStatus {

    /**
     * The backend URL has not been configured.  Composing a message is
     * meaningless until the user visits Settings.
     */
    @Immutable
    data class ConfigNeeded(val errorMessage: String?) : ChatScreenStatus

    /**
     * The configured backend could not be reached, or the conversation list
     * fetch failed.
     */
    @Immutable
    data class BackendOffline(val errorMessage: String?) : ChatScreenStatus

    /**
     * The initial conversation list (or a message hydration) is in progress.
     */
    @Immutable
    data object Loading : ChatScreenStatus

    /**
     * The backend is reachable but there are no conversations for the active
     * account / agent.
     */
    @Immutable
    data object NoConversations : ChatScreenStatus

    /**
     * The last outbound message could not be delivered.  The original text and
     * attachments are preserved in the composer so the user can retry.
     *
     * @param errorMessage backend or network error detail.
     */
    @Immutable
    data class SendFailed(val errorMessage: String?) : ChatScreenStatus

    /**
     * Normal operating state — a conversation is selected, messages may be
     * streaming, and the composer is open.
     *
     * @param selectedConversationId the currently active conversation.
     * @param isSending true while an outbound HTTP/WS send is in flight.
     */
    @Immutable
    data class Ready(
        val selectedConversationId: String?,
        val isSending: Boolean,
    ) : ChatScreenStatus
}

// ---------------------------------------------------------------------------
// Shared computation
// ---------------------------------------------------------------------------

/**
 * Derives the appropriate [ChatScreenStatus] from the current [ChatSessionState].
 *
 * Priority order (first match wins):
 * 1. ConfigNeeded — backend not configured
 * 2. BackendOffline — connection failed
 * 3. Loading — conversation list or hydration in progress
 * 4. NoConversations — connected but empty
 * 5. SendFailed — last send failed; composer has draft restored
 * 6. Ready — normal / sending / stream-disconnected-with-messages
 *
 * This is a pure function with no side effects: safe to call from any
 * platform, in tests, or from a Compose snapshot.
 */
fun chatScreenStatusOf(state: ChatSessionState): ChatScreenStatus = when (state.connectionState) {
    ChatConnectionState.ConfigNeeded ->
        ChatScreenStatus.ConfigNeeded(state.errorMessage)

    ChatConnectionState.Offline ->
        ChatScreenStatus.BackendOffline(state.errorMessage)

    ChatConnectionState.Loading ->
        ChatScreenStatus.Loading

    ChatConnectionState.NoConversations ->
        ChatScreenStatus.NoConversations

    ChatConnectionState.SendFailed ->
        ChatScreenStatus.SendFailed(state.errorMessage)

    ChatConnectionState.Demo,
    ChatConnectionState.Live,
    ChatConnectionState.Sending,
    ChatConnectionState.StreamDisconnected ->
        ChatScreenStatus.Ready(
            selectedConversationId = state.selectedConversationId,
            isSending = state.isSending,
        )
}

/**
 * Returns true when the screen should show a full-pane status panel rather
 * than the message list.  Delegates to [ChatSessionReducer.shouldShowStatePanel]
 * so the two stay in sync.
 */
fun ChatScreenStatus.shouldShowStatePanel(state: ChatSessionState): Boolean =
    ChatSessionReducer.shouldShowStatePanel(state)

/**
 * Returns true when [ChatScreenStatus.BackendOffline] or [ChatScreenStatus.ConfigNeeded]
 * — that is, connection-level errors where a full reconnect retry makes sense.
 * Desktop and Android can both consult this instead of independently listing
 * the retryable states.
 */
val ChatScreenStatus.isConnectionRetryable: Boolean
    get() = when (this) {
        is ChatScreenStatus.BackendOffline,
        is ChatScreenStatus.ConfigNeeded -> true
        is ChatScreenStatus.SendFailed,
        is ChatScreenStatus.Loading,
        is ChatScreenStatus.NoConversations,
        is ChatScreenStatus.Ready -> false
    }

/**
 * Returns true if this status represents any terminal error condition
 * (backend offline, config missing, or a failed send).
 */
val ChatScreenStatus.isError: Boolean
    get() = when (this) {
        is ChatScreenStatus.BackendOffline,
        is ChatScreenStatus.ConfigNeeded,
        is ChatScreenStatus.SendFailed -> true
        is ChatScreenStatus.Loading,
        is ChatScreenStatus.NoConversations,
        is ChatScreenStatus.Ready -> false
    }
