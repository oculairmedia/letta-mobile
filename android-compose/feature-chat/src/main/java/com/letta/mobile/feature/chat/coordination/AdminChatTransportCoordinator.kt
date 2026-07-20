package com.letta.mobile.feature.chat.coordination

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
) {
    fun startObserving() {
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
}
