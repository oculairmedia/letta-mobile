package com.letta.mobile.feature.chat.screen

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import com.letta.mobile.data.model.ToolReturnStatus
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
internal fun ChatScreenEffects(
    state: ChatUiState,
    composerState: ChatComposerState,
    hapticsEnabled: Boolean,
    viewModel: AdminChatViewModel,
    floatingBannerMessage: String,
    onFloatingBannerMessageChange: (String) -> Unit,
    ambient: ChatScreenAmbientState,
) {
    val snackbarDispatcher = LocalSnackbarDispatcher.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    LaunchedEffect(composerState.error) {
        val message = composerState.error ?: return@LaunchedEffect
        HapticEffects.reject(haptic, view)
        onFloatingBannerMessageChange(message)
        viewModel.clearComposerError()
    }

    LaunchedEffect(floatingBannerMessage) {
        if (floatingBannerMessage.isNotBlank()) {
            kotlinx.coroutines.delay(2600)
            onFloatingBannerMessageChange("")
        }
    }

    LaunchedEffect(state.a2uiActionSnackbar) {
        val snackbar = state.a2uiActionSnackbar ?: return@LaunchedEffect
        snackbarDispatcher.dispatch(
            SnackbarMessage(
                message = snackbar.message,
                actionLabel = snackbar.actionLabel,
                duration = snackbar.duration,
                onAction = snackbar.retryAction?.let { retry ->
                    { viewModel.submitA2uiAction(retry) }
                },
            )
        )
        viewModel.markA2uiActionSnackbarShown(snackbar.id)
    }

    LaunchedEffect(state.error) {
        val err = state.error ?: return@LaunchedEffect
        if (state.messages.isNotEmpty()) {
            snackbarDispatcher.dispatch(
                SnackbarMessage(
                    message = err,
                    duration = SnackbarDuration.Long,
                )
            )
            viewModel.clearError()
        }
    }

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

    ChatScreenHapticEffects(
        state = state,
        hapticsEnabled = hapticsEnabled,
        haptic = haptic,
        view = view,
    )
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

@Composable
private fun ChatScreenHapticEffects(
    state: ChatUiState,
    hapticsEnabled: Boolean,
    haptic: HapticFeedback,
    view: android.view.View,
) {
    var streamingHapticActive by remember { mutableStateOf(false) }
    LaunchedEffect(state.isStreaming, state.error, hapticsEnabled) {
        if (!hapticsEnabled) {
            streamingHapticActive = false
            return@LaunchedEffect
        }
        when {
            state.isStreaming && !streamingHapticActive -> {
                streamingHapticActive = true
                HapticEffects.streamingStart(view, enabled = true)
            }
            !state.isStreaming && streamingHapticActive -> {
                streamingHapticActive = false
                if (state.error != null) {
                    HapticEffects.toolCallFailed(view, enabled = true)
                } else {
                    HapticEffects.streamingComplete(view, enabled = true)
                }
            }
        }
    }

    val greetedToolIds = remember { HashSet<String>() }
    val resolvedToolIds = remember { HashSet<String>() }
    LaunchedEffect(state.pendingTools, hapticsEnabled) {
        if (!hapticsEnabled) return@LaunchedEffect
        state.pendingTools.forEach { pending ->
            if (greetedToolIds.add(pending.id)) {
                HapticEffects.toolCallStarted(view, enabled = true)
            }
        }
    }
    LaunchedEffect(state.messages, hapticsEnabled) {
        if (!hapticsEnabled) return@LaunchedEffect
        if (greetedToolIds.size == resolvedToolIds.size) return@LaunchedEffect
        state.messages.forEach { message ->
            message.toolCalls?.forEach toolCall@{ toolCall ->
                val id = toolCall.toolCallId ?: return@toolCall
                if (id !in greetedToolIds) return@toolCall
                val isTerminal = ToolReturnStatus.isError(toolCall.status) ||
                    toolCall.result != null ||
                    toolCall.status == ToolReturnStatus.SUCCESS ||
                    toolCall.status == "warning"
                if (isTerminal && resolvedToolIds.add(id)) {
                    if (ToolReturnStatus.isError(toolCall.status)) {
                        HapticEffects.toolCallFailed(view, enabled = true)
                    } else {
                        HapticEffects.toolCallSucceeded(view, enabled = true)
                    }
                }
            }
        }
    }
}
