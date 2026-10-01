package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.feature.chat.subagent.ActiveSubagent
import com.letta.mobile.feature.chat.subagent.ActiveSubagentRings
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource
import com.letta.mobile.feature.chat.subagent.LocalSubagentTodoSheetOpener
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheet
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheetState
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheetTarget
import com.letta.mobile.feature.chat.subagent.subagentTodoSheetStateFrom
import com.letta.mobile.ui.haptics.HapticEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// letta-mobile-bglj6.1: the chat screen's subagent rings, todo sheet and dictation overlay, drawn
// over the shared chat page (see shared/SharedChatSubagents.kt and shared/SharedChatPage.kt).

internal data class ChatScreenSubagentRingsOverlayParams(
    val barState: ChatScreenSubagentBarState,
    val resolvedSubagentSource: ActiveSubagentSource,
    val navigation: ChatScreenNavigationCallbacks,
    val onFloatingBannerMessageChange: (String) -> Unit,
    val openSubagentTarget: (SubagentTodoSheetTarget) -> Unit,
    val onTargetChange: (SubagentTodoSheetTarget?) -> Unit,
    val subagentNavigationScope: kotlinx.coroutines.CoroutineScope,
    val haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    val modifier: Modifier = Modifier,
)

internal data class ChatScreenSubagentTodoSheetParams(
    val target: SubagentTodoSheetTarget?,
    val resolvedSubagentSource: ActiveSubagentSource,
    val resolvedSelfTodoSource: com.letta.mobile.feature.chat.subagent.SelfTodoSource,
    val currentConversationId: String?,
    val navigation: ChatScreenNavigationCallbacks,
    val onDismiss: () -> Unit,
    val onTargetUpdate: (SubagentTodoSheetTarget?) -> Unit,
)

@Composable
internal fun ChatScreenSubagentRingsOverlay(params: ChatScreenSubagentRingsOverlayParams) {
    CompositionLocalProvider(LocalSubagentTodoSheetOpener provides params.openSubagentTarget) {
        ActiveSubagentRings(
            subagents = params.barState.activeSubagents,
            now = params.barState.lingerTick,
            onRingClick = { subagent ->
                params.onTargetChange(
                    SubagentTodoSheetTarget(
                        toolCallId = subagent.id,
                        description = subagent.description,
                        subagentAgentId = subagent.subagentAgentId,
                        subagentConversationId = subagent.subagentConversationId,
                    ),
                )
            },
            onViewConversation = { subagent ->
                handleSubagentViewConversation(
                    SubagentViewConversationParams(
                        subagent = subagent,
                        resolvedSubagentSource = params.resolvedSubagentSource,
                        navigation = params.navigation,
                        subagentNavigationScope = params.subagentNavigationScope,
                        haptic = params.haptic,
                        onTargetChange = params.onTargetChange,
                        onFloatingBannerMessageChange = params.onFloatingBannerMessageChange,
                    ),
                )
            },
            modifier = params.modifier,
        )
    }
}

private data class SubagentViewConversationParams(
    val subagent: ActiveSubagent,
    val resolvedSubagentSource: ActiveSubagentSource,
    val navigation: ChatScreenNavigationCallbacks,
    val subagentNavigationScope: kotlinx.coroutines.CoroutineScope,
    val haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    val onTargetChange: (SubagentTodoSheetTarget?) -> Unit,
    val onFloatingBannerMessageChange: (String) -> Unit,
)

private fun handleSubagentViewConversation(params: SubagentViewConversationParams) {
    params.subagentNavigationScope.launch {
        val navigationTarget = resolveSubagentConversationNavigation(params)
        if (navigationTarget != null) {
            HapticEffects.longPress(params.haptic)
            params.navigation.onViewSubagentConversation?.invoke(
                navigationTarget.agentId,
                navigationTarget.conversationId,
            )
            return@launch
        }
        openSubagentTodoFallback(params)
    }
}

private data class SubagentConversationNavigationTarget(
    val agentId: String,
    val conversationId: String,
)

private suspend fun resolveSubagentConversationNavigation(
    params: SubagentViewConversationParams,
): SubagentConversationNavigationTarget? {
    val agentId = params.subagent.subagentAgentId?.takeIf { it.isNotBlank() } ?: return null
    val conversationId = params.resolvedSubagentSource
        .resolveConversationId(params.subagent)
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    if (params.navigation.onViewSubagentConversation == null) return null
    return SubagentConversationNavigationTarget(agentId, conversationId)
}

private fun openSubagentTodoFallback(params: SubagentViewConversationParams) {
    params.onTargetChange(
        SubagentTodoSheetTarget(
            toolCallId = params.subagent.id,
            description = params.subagent.description,
            subagentAgentId = params.subagent.subagentAgentId,
            subagentConversationId = params.subagent.subagentConversationId,
        ),
    )
    params.onFloatingBannerMessageChange("Subagent conversation is not available yet")
}

@Composable
internal fun ChatScreenSubagentTodoSheet(params: ChatScreenSubagentTodoSheetParams) {
    params.target?.let { sheetTarget ->
        var todoState by remember(sheetTarget.toolCallId) {
            mutableStateOf<SubagentTodoSheetState>(SubagentTodoSheetState.Loading)
        }
        LaunchedEffect(
            params.resolvedSubagentSource,
            params.resolvedSelfTodoSource,
            sheetTarget.toolCallId,
            params.currentConversationId,
        ) {
            val todos = if (sheetTarget.toolCallId == ActiveSubagent.SELF_ID) {
                Result.success(params.resolvedSelfTodoSource.todos(params.currentConversationId.orEmpty()))
            } else {
                params.resolvedSubagentSource.todos(sheetTarget.toolCallId)
            }
            todoState = subagentTodoSheetStateFrom(todos)
        }
        SubagentTodoSheet(
            description = sheetTarget.description,
            state = todoState,
            onDismiss = params.onDismiss,
            onViewConversation = sheetTarget.subagentAgentId
                ?.takeIf { it.isNotBlank() && params.navigation.onViewSubagentConversation != null }
                ?.let { agentId ->
                    sheetTarget.subagentNavigationConversationId?.let { conversationId ->
                        {
                            params.onTargetUpdate(null)
                            params.navigation.onViewSubagentConversation?.invoke(agentId, conversationId)
                        }
                    }
                },
        )
    }
}

@Composable
internal fun ChatScreenVoiceOverlay(modifier: Modifier = Modifier) {
    val voiceActivity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val voiceIsHiltHost = voiceActivity is dagger.hilt.internal.GeneratedComponentManager<*>
    if (voiceIsHiltHost) {
        val voiceVm: com.letta.mobile.feature.chat.voice.VoiceInputViewModel =
            hiltViewModel()
        val voiceState by voiceVm.uiState.collectAsStateWithLifecycle()
        com.letta.mobile.ui.components.audio.VoiceRecognizerOverlay(
            visible = voiceState.recognizing,
            recognizedText = voiceState.recognizedText,
            amplitude = voiceState.amplitude,
            modifier = modifier,
        )
    }
}


/**
 * Opens [target]'s todo sheet at once, then (when the dispatch has no conversation yet) resolves
 * the subagent's agent and conversation in [scope] and re-targets the sheet so it can offer
 * "view conversation". Used by the shared chat page's rings and dispatch rows.
 */
internal fun openSubagentTodoSheet(
    target: SubagentTodoSheetTarget,
    source: ActiveSubagentSource,
    scope: CoroutineScope,
    onTarget: (SubagentTodoSheetTarget) -> Unit,
) {
    onTarget(target)
    if (target.subagentConversationId != null) return
    scope.launch {
        val subagent = source.resolveSubagent(target.toolCallId).getOrNull()
        val agentId = target.subagentAgentId ?: subagent?.subagentAgentId
        val conversationId = subagent?.let { source.resolveConversationId(it).getOrNull() }
        if (agentId != null && conversationId != null) {
            onTarget(target.copy(subagentAgentId = agentId, subagentConversationId = conversationId))
        }
    }
}
