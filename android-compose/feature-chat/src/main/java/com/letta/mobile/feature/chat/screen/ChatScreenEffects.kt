package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import com.letta.mobile.data.model.ToolReturnStatus
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.ambient.AmbientMotion
import com.letta.mobile.ui.ambient.AmbientMotionStatus
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.haptics.HapticEffects
import kotlin.time.Duration.Companion.milliseconds

internal data class ChatScreenAmbientState(
    val status: String,
    val onStatusChange: (String) -> Unit,
    val hadActiveRun: Boolean,
    val onHadActiveRunChange: (Boolean) -> Unit,
)

/** The chat screen's ambient glow status and activity haptics; the shared page owns its feedback. */
internal data class ChatScreenEffectsParams(
    val state: ChatUiState,
    val hapticsEnabled: Boolean,
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
    val view = LocalView.current

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
                // Same shared hold as desktop: cutting this short cancels the
                // Completed decay mid-flight and Idle animates the envelope
                // back up, so the afterglow reads as a rebound.
                kotlinx.coroutines.delay(
                    AmbientMotion.holdMillis(AmbientMotionStatus.Completed).milliseconds,
                )
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
    // letta-mobile-m60rn: this flag must survive Activity recreation
    // (rotation / fold / locale change). With plain `remember` every config
    // change reset it to false while the ViewModel kept reporting an active
    // stream, so each rotation re-fired the streamingStart cue. As a
    // saveable value it is restored across recreation: an in-flight stream
    // stays silent, and the terminal complete/fail cue still fires exactly
    // once when the stream actually ends.
    var streamingHapticActive by rememberSaveable { mutableStateOf(false) }
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
