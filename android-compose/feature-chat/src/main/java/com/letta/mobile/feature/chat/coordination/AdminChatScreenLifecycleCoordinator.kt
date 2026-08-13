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
    /**
     * letta-mobile-wxy4s: force an immediate connection-liveness probe on resume.
     * The transport's periodic probe cannot be trusted while the app is
     * backgrounded (doze), and the recovery branch below is DEAD CODE until the
     * connection state actually flips — which is exactly what happened during the
     * 2026-07-31 incident: the app came back to the foreground, the connection was
     * long dead, nothing had noticed, so `conn` was still Connected and no
     * reconnect ran. Probing first makes that branch reachable.
     */
    private val probeConnection: () -> Unit = {},
    /**
     * letta-mobile-6bqi1: true when the on-screen messages are still present in
     * the VM render cache (device rotation wipes the SESSION state via
     * retryConnection, but the VM-level uiState messages survive). When true,
     * skip the destructive wipe — resolveConversationAndLoad re-establishes the
     * connection without re-entering Loading, so there is no reload flash.
     */
    private val isAlreadyHydrated: () -> Boolean = { false },
) {
    private var lastScreenResumedAtMs = Long.MIN_VALUE / 2

    fun onScreenPaused() {
        currentConversationTracker.setCurrent(null)
    }

    fun onScreenResumed() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastScreenResumedAtMs < 200) return
        lastScreenResumedAtMs = now
        // Probe BEFORE reading connection state: a dead-but-unnoticed connection
        // must be detectable within a probe round-trip rather than a full interval.
        probeConnection()
        val currentId = conversationId()?.value
        if (currentId != null) {
            currentConversationTracker.setCurrent(currentId)
            val conn = sessionState.value.connectionState
            if (conn == ChatConnectionState.Offline || conn == ChatConnectionState.StreamDisconnected) {
                if (!isAlreadyHydrated()) {
                    updateSessionState { current ->
                        ChatSessionReducer.retryConnection(
                            current = current,
                            initial = ChatSessionState(),
                        )
                    }
                }
                resolveConversationAndLoad()
            }
        }
    }

    fun onCleared() {
        currentConversationTracker.setCurrent(null)
    }
}
