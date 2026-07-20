package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.chat.runtime.ChatConnectionState
import com.letta.mobile.data.chat.runtime.ChatSessionReducer
import com.letta.mobile.data.chat.runtime.ChatSessionState
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Screen pause/resume hooks: presence tracking and offline reconnect.
 */
internal class AdminChatScreenLifecycleCoordinator(
    private val currentConversationTracker: CurrentConversationTracker,
    private val conversationId: () -> ConversationId?,
    private val sessionState: MutableStateFlow<ChatSessionState>,
    private val resolveConversationAndLoad: () -> Unit,
    private val updateSessionState: (reducer: (ChatSessionState) -> ChatSessionState) -> Unit,
) {
    private var lastScreenResumedAtMs = Long.MIN_VALUE / 2

    fun onScreenPaused() {
        currentConversationTracker.setCurrent(null)
    }

    fun onScreenResumed() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastScreenResumedAtMs < 200) return
        lastScreenResumedAtMs = now
        val currentId = conversationId()?.value
        if (currentId != null) {
            currentConversationTracker.setCurrent(currentId)
            val conn = sessionState.value.connectionState
            if (conn == ChatConnectionState.Offline || conn == ChatConnectionState.StreamDisconnected) {
                updateSessionState { current ->
                    ChatSessionReducer.retryConnection(
                        current = current,
                        initial = ChatSessionState(),
                    )
                }
                resolveConversationAndLoad()
            }
        }
    }

    fun onCleared() {
        currentConversationTracker.setCurrent(null)
    }
}
