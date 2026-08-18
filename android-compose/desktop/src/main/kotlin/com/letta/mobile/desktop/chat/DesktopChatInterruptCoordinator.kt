package com.letta.mobile.desktop.chat

import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles server-side turn abort requests (stop button) and timeouts with fallback local clearing.
 */
internal class DesktopChatInterruptCoordinator(
    private val scope: CoroutineScope,
    private val onForcedLocalStop: (conversationId: String, reason: String) -> Unit,
) {
    private val _cancellingConversationId = MutableStateFlow<String?>(null)
    val cancellingConversationId: StateFlow<String?> = _cancellingConversationId.asStateFlow()

    private var cancelRequestedAtMs: Long = 0L

    fun stopActiveRun(
        conversationId: String,
        gateway: DesktopChatGateway?,
        streamingConversationId: String?,
        thinkingConversationId: String?,
    ) {
        val active = streamingConversationId ?: thinkingConversationId
        if (active != null && active != conversationId) return
        if (_cancellingConversationId.value == conversationId) {
            onForcedLocalStop(conversationId, "secondStopPress")
            return
        }
        val aborter = gateway as? DesktopTurnAborter
        if (aborter == null) {
            onForcedLocalStop(conversationId, "gatewayCannotAbort")
            return
        }
        _cancellingConversationId.value = conversationId
        cancelRequestedAtMs = System.currentTimeMillis()
        Telemetry.event(
            TELEMETRY_TAG,
            "interrupt.cancelRequested",
            "conversationId" to conversationId,
            "transport" to "appServer",
        )
        scope.launch {
            val dispatched = runCatching { aborter.abortConversationTurn(conversationId) }
            val failure = dispatched.exceptionOrNull()
            if (_cancellingConversationId.value != conversationId) return@launch
            if (failure != null || dispatched.getOrDefault(false).not()) {
                failure?.let {
                    Telemetry.error(TELEMETRY_TAG, "interrupt.abortDispatchFailed", it)
                }
                onForcedLocalStop(conversationId, "abortNotDispatched")
                return@launch
            }
            val settled = withTimeoutOrNull(CANCEL_TERMINAL_TIMEOUT_MS) {
                _cancellingConversationId.first { it != conversationId }
                true
            } ?: false
            if (settled || _cancellingConversationId.value != conversationId) return@launch
            Telemetry.event(
                TELEMETRY_TAG,
                "interrupt.terminalTimeout",
                "conversationId" to conversationId,
                durationMs = CANCEL_TERMINAL_TIMEOUT_MS,
                level = Telemetry.Level.WARN,
            )
            onForcedLocalStop(conversationId, "terminalTimeout")
        }
    }

    fun clearCancelling() {
        _cancellingConversationId.value = null
    }

    fun recordTerminalAfterCancel(conversationId: String) {
        Telemetry.event(
            TELEMETRY_TAG,
            "interrupt.terminalAfterCancel",
            "conversationId" to conversationId,
            durationMs = System.currentTimeMillis() - cancelRequestedAtMs,
        )
    }

    companion object {
        private const val TELEMETRY_TAG = "DesktopChat"
        private const val CANCEL_TERMINAL_TIMEOUT_MS = 30_000L
    }
}
