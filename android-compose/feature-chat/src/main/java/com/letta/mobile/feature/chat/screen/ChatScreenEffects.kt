package com.letta.mobile.feature.chat.screen

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import com.letta.mobile.data.model.ToolReturnStatus
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.feature.chat.coordination.ChatComposerState
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.SnackbarMessage
import com.letta.mobile.ui.haptics.HapticEffects

// letta-mobile-gi9o0: minimum gap between reveal-synchronized streaming
// haptics. Pulses are triggered by the smoothed text actually revealing (word
// boundaries / small character buckets), not by an independent timer.
internal const val STREAMING_REVEAL_HAPTIC_MIN_INTERVAL_MS = 96L

internal data class ChatScreenAmbientState(
    val status: String,
    val onStatusChange: (String) -> Unit,
    val hadActiveRun: Boolean,
    val onHadActiveRunChange: (Boolean) -> Unit,
)

internal data class ChatScreenEffectsParams(
    val state: ChatUiState,
    val composerState: ChatComposerState,
    val hapticsEnabled: Boolean,
    val viewModel: AdminChatViewModel,
    val floatingBannerMessage: String,
    val onFloatingBannerMessageChange: (String) -> Unit,
    val ambient: ChatScreenAmbientState,
)

@Composable
internal fun rememberChatScreenAmbientState(): ChatScreenAmbientState {
    var ambientAgentStatus by remember { mutableStateOf("Idle") }
    var hadActiveAmbientRun by remember { mutableStateOf(false) }
    return ChatScreenAmbientState(
        status = ambientAgentStatus,
        onStatusChange = { ambientAgentStatus = it },
        hadActiveRun = hadActiveAmbientRun,
        onHadActiveRunChange = { hadActiveAmbientRun = it },
    )
}

@Composable
internal fun ChatScreenEffects(params: ChatScreenEffectsParams) {
    val snackbarDispatcher = LocalSnackbarDispatcher.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    ChatScreenComposerErrorEffect(
        state = ChatScreenComposerErrorEffectState(composerError = params.composerState.error),
        haptics = ChatScreenComposerErrorEffectHaptics(haptic = haptic, view = view),
        callbacks = ChatScreenComposerErrorEffectCallbacks(
            onFloatingBannerMessageChange = params.onFloatingBannerMessageChange,
            onClearComposerError = params.viewModel::clearComposerError,
        ),
    )

    ChatScreenFloatingBannerDismissEffect(
        floatingBannerMessage = params.floatingBannerMessage,
        onFloatingBannerMessageChange = params.onFloatingBannerMessageChange,
    )

    ChatScreenA2uiSnackbarEffect(
        snackbar = params.state.a2uiActionSnackbar,
        snackbarDispatcher = snackbarDispatcher,
        onMarkShown = params.viewModel::markA2uiActionSnackbarShown,
        onRetry = params.viewModel::submitA2uiAction,
    )

    ChatScreenErrorSnackbarEffect(
        error = params.state.error,
        hasMessages = params.state.messages.isNotEmpty(),
        snackbarDispatcher = snackbarDispatcher,
        onClearError = params.viewModel::clearError,
    )

    ChatScreenAmbientStatusEffect(state = params.state, ambient = params.ambient)

    ChatScreenStreamingHapticEffect(
        isStreaming = params.state.isStreaming,
        error = params.state.error,
        hapticsEnabled = params.hapticsEnabled,
        view = view,
    )

    ChatScreenPendingToolHapticEffect(
        pendingTools = params.state.pendingTools,
        hapticsEnabled = params.hapticsEnabled,
        view = view,
    )

    ChatScreenResolvedToolHapticEffect(
        messages = params.state.messages,
        hapticsEnabled = params.hapticsEnabled,
        view = view,
    )
}

private data class ChatScreenComposerErrorEffectState(
    val composerError: String?,
)

private data class ChatScreenComposerErrorEffectHaptics(
    val haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    val view: android.view.View,
)

private data class ChatScreenComposerErrorEffectCallbacks(
    val onFloatingBannerMessageChange: (String) -> Unit,
    val onClearComposerError: () -> Unit,
)

@Composable
private fun ChatScreenComposerErrorEffect(
    state: ChatScreenComposerErrorEffectState,
    haptics: ChatScreenComposerErrorEffectHaptics,
    callbacks: ChatScreenComposerErrorEffectCallbacks,
) {
    LaunchedEffect(state.composerError) {
        val message = state.composerError ?: return@LaunchedEffect
        HapticEffects.reject(haptics.haptic, haptics.view)
        callbacks.onFloatingBannerMessageChange(message)
        callbacks.onClearComposerError()
    }
}

@Composable
private fun ChatScreenFloatingBannerDismissEffect(
    floatingBannerMessage: String,
    onFloatingBannerMessageChange: (String) -> Unit,
) {
    LaunchedEffect(floatingBannerMessage) {
        if (floatingBannerMessage.isNotBlank()) {
            kotlinx.coroutines.delay(2600)
            onFloatingBannerMessageChange("")
        }
    }
}

@Composable
private fun ChatScreenA2uiSnackbarEffect(
    snackbar: com.letta.mobile.ui.chat.render.A2uiActionSnackbarUi?,
    snackbarDispatcher: com.letta.mobile.ui.common.SnackbarDispatcher,
    onMarkShown: (Long) -> Unit,
    onRetry: (com.letta.mobile.data.a2ui.A2uiAction) -> Unit,
) {
    LaunchedEffect(snackbar) {
        val current = snackbar ?: return@LaunchedEffect
        snackbarDispatcher.dispatch(
            SnackbarMessage(
                message = current.message,
                actionLabel = current.actionLabel,
                duration = current.duration,
                onAction = current.retryAction?.let { retry -> { onRetry(retry) } },
            ),
        )
        onMarkShown(current.id)
    }
}

@Composable
private fun ChatScreenErrorSnackbarEffect(
    error: String?,
    hasMessages: Boolean,
    snackbarDispatcher: com.letta.mobile.ui.common.SnackbarDispatcher,
    onClearError: () -> Unit,
) {
    LaunchedEffect(error) {
        val err = error ?: return@LaunchedEffect
        if (!hasMessages) return@LaunchedEffect
        snackbarDispatcher.dispatch(
            SnackbarMessage(
                message = err,
                duration = SnackbarDuration.Long,
            ),
        )
        onClearError()
    }
}

@Composable
private fun ChatScreenAmbientStatusEffect(
    state: ChatUiState,
    ambient: ChatScreenAmbientState,
) {
    LaunchedEffect(state.error, state.isAgentTyping, state.isStreaming) {
        when {
            state.error != null -> ambient.onStatusChange("Failed")
            state.isStreaming || state.isAgentTyping -> {
                ambient.onHadActiveRunChange(true)
                ambient.onStatusChange("Running")
            }
            ambient.hadActiveRun -> {
                ambient.onStatusChange("Completed")
                kotlinx.coroutines.delay(1400)
                ambient.onHadActiveRunChange(false)
                ambient.onStatusChange("Idle")
            }
            else -> ambient.onStatusChange("Idle")
        }
    }
}

@Composable
private fun ChatScreenStreamingHapticEffect(
    isStreaming: Boolean,
    error: String?,
    hapticsEnabled: Boolean,
    view: android.view.View,
) {
    var streamingHapticActive by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming, error, hapticsEnabled) {
        if (!hapticsEnabled) {
            streamingHapticActive = false
            return@LaunchedEffect
        }
        when {
            isStreaming && !streamingHapticActive -> {
                streamingHapticActive = true
                HapticEffects.streamingStart(view, enabled = true)
            }
            !isStreaming && streamingHapticActive -> {
                streamingHapticActive = false
                if (error != null) {
                    HapticEffects.toolCallFailed(view, enabled = true)
                } else {
                    HapticEffects.streamingComplete(view, enabled = true)
                }
            }
        }
    }
}

@Composable
private fun ChatScreenPendingToolHapticEffect(
    pendingTools: kotlinx.collections.immutable.ImmutableList<com.letta.mobile.ui.chat.render.PendingToolCall>,
    hapticsEnabled: Boolean,
    view: android.view.View,
) {
    val greetedToolIds = remember { HashSet<String>() }
    LaunchedEffect(pendingTools, hapticsEnabled) {
        if (!hapticsEnabled) return@LaunchedEffect
        pendingTools.forEach { pending ->
            if (greetedToolIds.add(pending.id)) {
                HapticEffects.toolCallStarted(view, enabled = true)
            }
        }
    }
}

@Composable
private fun ChatScreenResolvedToolHapticEffect(
    messages: List<com.letta.mobile.data.model.UiMessage>,
    hapticsEnabled: Boolean,
    view: android.view.View,
) {
    val greetedToolIds = remember { HashSet<String>() }
    val resolvedToolIds = remember { HashSet<String>() }
    LaunchedEffect(messages, hapticsEnabled) {
        if (!hapticsEnabled) return@LaunchedEffect
        if (greetedToolIds.size == resolvedToolIds.size) return@LaunchedEffect
        messages.forEach { message ->
            message.toolCalls?.forEach toolCall@{ toolCall ->
                val id = toolCall.toolCallId ?: return@toolCall
                if (id !in greetedToolIds) return@toolCall
                if (!isTerminalToolCall(toolCall)) return@toolCall
                if (!resolvedToolIds.add(id)) return@toolCall
                if (ToolReturnStatus.isError(toolCall.status)) {
                    HapticEffects.toolCallFailed(view, enabled = true)
                } else {
                    HapticEffects.toolCallSucceeded(view, enabled = true)
                }
            }
        }
    }
}

private fun isTerminalToolCall(toolCall: UiToolCall): Boolean {
    if (ToolReturnStatus.isError(toolCall.status)) return true
    if (toolCall.result != null) return true
    if (toolCall.status == ToolReturnStatus.SUCCESS) return true
    return toolCall.status == "warning"
}

@Composable
internal fun rememberStreamingRevealHapticPulse(
    hapticsEnabled: Boolean,
): () -> Unit {
    val view = LocalView.current
    var lastRevealHapticAt by remember { mutableLongStateOf(0L) }
    return {
        if (hapticsEnabled) {
            val now = System.currentTimeMillis()
            if (now - lastRevealHapticAt >= STREAMING_REVEAL_HAPTIC_MIN_INTERVAL_MS) {
                lastRevealHapticAt = now
                HapticEffects.streamingPulse(view, enabled = true)
            }
        }
    }
}
