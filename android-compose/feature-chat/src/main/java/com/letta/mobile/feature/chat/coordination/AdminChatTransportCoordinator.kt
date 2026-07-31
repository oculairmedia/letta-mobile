package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.runtime.ChatSessionReducer
import com.letta.mobile.data.chat.runtime.ChatSessionState
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsConnectionState
import com.letta.mobile.ui.chat.render.ChatTransport
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Maps WebSocket connection state to [ChatTransport] for the chat UI.
 */
internal class AdminChatTransportCoordinator(
    private val scope: CoroutineScope,
    private val isShimBackend: StateFlow<Boolean>,
    private val wsChatBridge: WsChatBridge,
    private val uiState: MutableStateFlow<ChatUiState>,
    /**
     * letta-mobile-wxy4s: push connection loss into the chat SESSION state (not
     * just the transport chip), so the screen shows a disconnected/reconnecting
     * state instead of silently rendering cached messages over a dead connection —
     * the 2026-07-31 incident behavior.
     */
    private val updateSessionState: ((reducer: (ChatSessionState) -> ChatSessionState) -> Unit)? = null,
    /** Re-hydrate the open conversation once the supervisor's redial lands. */
    private val onReconnected: (() -> Unit)? = null,
) {
    fun startObserving() {
        startConnectionStateSurfacing()
        scope.launch {
            combine(isShimBackend, wsChatBridge.connection) { isShim, wsState ->
                if (!isShim) return@combine ChatTransport.Rest
                when (wsState) {
                    is WsConnectionState.Idle -> ChatTransport.WsIdle
                    is WsConnectionState.Connecting -> ChatTransport.WsConnecting
                    is WsConnectionState.Connected -> ChatTransport.WsConnected(
                        a2uiEnabled = wsState.a2uiEnabled,
                        catalog = wsState.catalog,
                    )
                    is WsConnectionState.Disconnected -> ChatTransport.WsDisconnected(
                        code = wsState.code,
                        reason = wsState.reason,
                    )
                }
            }.distinctUntilChanged().collect { transport ->
                uiState.update { it.copy(transport = transport) }
            }
        }
    }

    /**
     * letta-mobile-wxy4s: the app-level connection-loss surfacing + recovery hook.
     *
     * The transport-level liveness probe is what makes this reachable at all: it
     * flips the state to Disconnected(willReconnect=true) on a black-holed
     * connection that QUIC itself never reported. This collector turns that flip
     * into (1) a visible StreamDisconnected session state and (2) a re-hydrate on
     * the next Connected, so the recovery paths that were dead code during the
     * incident actually engage.
     */
    private fun startConnectionStateSurfacing() {
        val update = updateSessionState ?: return
        scope.launch {
            var sawDisconnect = false
            wsChatBridge.state.collect { transportState ->
                // Only the live channel transport backs this surface. On a REST
                // backend the channel transport is inert, and its states must not
                // be dressed up as chat-connection loss.
                if (!isShimBackend.value) return@collect
                when (transportState) {
                    is ChannelTransportState.Connected -> {
                        if (!sawDisconnect) return@collect
                        sawDisconnect = false
                        onReconnected?.invoke()
                    }
                    is ChannelTransportState.Disconnected -> {
                        // An auth failure is terminal (the supervisor stops
                        // redialing), so it must not be dressed up as a transient
                        // reconnect.
                        sawDisconnect = !transportState.isAuthFailure
                        update { current ->
                            ChatSessionReducer.streamDisconnected(
                                state = current,
                                generation = current.selectionGeneration,
                                errorMessage = transportState.reason.ifBlank { "Connection lost" },
                                statusMessage = when {
                                    transportState.isAuthFailure -> "Authentication failed"
                                    transportState.willReconnect -> "Reconnecting…"
                                    else -> "Stream disconnected"
                                },
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}
