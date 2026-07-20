package com.letta.mobile.feature.chat.screen

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.feature.chat.coordination.ChatComposerEffect
import com.letta.mobile.feature.chat.coordination.ChatComposerState
import com.letta.mobile.feature.chat.render.LocalStreamingRevealHapticPulse
import com.letta.mobile.feature.chat.render.LocalTruncatedToolResultResolver
import com.letta.mobile.feature.chat.render.TruncatedToolResultResolver
import com.letta.mobile.feature.chat.subagent.ActiveSubagent
import com.letta.mobile.feature.chat.subagent.ActiveSubagentRings
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource
import com.letta.mobile.feature.chat.subagent.LocalSubagentTodoSheetOpener
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheet
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheetState
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheetTarget
import com.letta.mobile.feature.chat.subagent.subagentTodoSheetStateFrom
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.components.FloatingBanner
import com.letta.mobile.ui.components.MessageSkeletonList
import com.letta.mobile.ui.components.ThinkingTextToken
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.theme.LettaSpacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

/**
 * Feature flag: when false, the tool-affordance chip strip above the
 * composer is suppressed. The component (`ToolAffordanceRow`), the
 * smart-template builder (`buildToolCallTemplate`), and the
 * `AdminChatViewModel.activeAgent` flow all stay wired — only the
 * call-site stops feeding tools through, so flipping to true re-enables
 * the row without any other change.
 */
internal const val TOOL_AFFORDANCE_ROW_ENABLED = true

internal data class ChatScreenLayoutParams(
    val state: ChatUiState,
    val composerState: ChatComposerState,
    val viewModel: AdminChatViewModel,
    val contentPadding: PaddingValues,
    val chatBackground: ChatBackground,
    val chatMode: String,
    val navigation: ChatScreenNavigationCallbacks,
    val resolvedSubagentSource: ActiveSubagentSource,
    val subagentBarState: ChatScreenSubagentBarState,
    val activeFontScale: Float,
    val onActiveFontScaleChange: (Float) -> Unit,
    val bottomInsetDp: Dp,
    val floatingBannerMessage: String,
    val onFloatingBannerMessageChange: (String) -> Unit,
    val streamingRevealPulse: () -> Unit,
)

@Composable
internal fun ChatScreenLayout(
    params: ChatScreenLayoutParams,
    modifier: Modifier = Modifier,
) {
    val localState = rememberChatScreenLayoutLocalState(params)
    val haptic = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotionEnabled()

    Box(modifier = modifier.fillMaxSize()) {
        ChatScreenMainContent(
            params = params,
            contentCallbacks = localState.contentCallbacks,
            bottomPaddingDp = localState.bottomPaddingDp,
            openSubagentTarget = localState.openSubagentTarget,
        )
        ChatScreenSubagentRingsOverlay(
            params = params,
            openSubagentTarget = localState.openSubagentTarget,
            onTargetChange = localState.onTappedSubagentTargetChange,
            subagentNavigationScope = localState.subagentNavigationScope,
            haptic = haptic,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = params.contentPadding.calculateTopPadding() + 8.dp, end = 8.dp),
        )
        ChatScreenComposerColumn(
            state = params.state,
            composerState = params.composerState,
            viewModel = params.viewModel,
            navigation = params.navigation,
            reducedMotion = reducedMotion,
            bottomInsetDp = params.bottomInsetDp,
            onComposerHeightChange = localState.onComposerHeightChange,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        ChatScreenSubagentTodoSheet(
            target = localState.tappedSubagentTarget,
            resolvedSubagentSource = params.resolvedSubagentSource,
            resolvedSelfTodoSource = params.viewModel.selfTodoSource,
            currentConversationId = localState.currentConversationId,
            navigation = params.navigation,
            onDismiss = { localState.onTappedSubagentTargetChange(null) },
            onTargetUpdate = localState.onTappedSubagentTargetChange,
        )
        ChatScreenFloatingOverlays(
            floatingBannerMessage = params.floatingBannerMessage,
            imageViewerState = localState.imageViewerState,
            onImageViewerDismiss = { localState.onImageViewerStateChange(null) },
            chatMode = params.chatMode,
            a2uiDebugFrames = params.state.a2uiDebugFrames,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ChatScreenMainContent(
    params: ChatScreenLayoutParams,
    contentCallbacks: ChatContentCallbacks,
    bottomPaddingDp: Dp,
    openSubagentTarget: (SubagentTodoSheetTarget) -> Unit,
) {
    val contentPhase = chatScreenContentPhase(params.state)
    val truncatedToolResultResolver = remember(params.viewModel) {
        TruncatedToolResultResolver { messageId ->
            params.viewModel.onTruncatedToolResultExpanded(messageId)
        }
    }
    CompositionLocalProvider(
        LocalSubagentTodoSheetOpener provides openSubagentTarget,
        LocalStreamingRevealHapticPulse provides params.streamingRevealPulse,
        LocalTruncatedToolResultResolver provides truncatedToolResultResolver,
    ) {
        Crossfade(
            targetState = contentPhase,
            animationSpec = tween(durationMillis = 200),
            modifier = Modifier.fillMaxSize(),
            label = "chat-content",
        ) { phase ->
            ChatScreenPhaseContent(
                params = ChatScreenPhaseContentParams(
                    phase = phase,
                    state = params.state,
                    viewModel = params.viewModel,
                    contentCallbacks = contentCallbacks,
                    appearance = ChatContentAppearance(
                        chatMode = params.chatMode,
                        chatBackground = params.chatBackground,
                        topPadding = params.contentPadding.calculateTopPadding(),
                        bottomPadding = bottomPaddingDp,
                        activeFontScale = params.activeFontScale,
                        scrollToMessageId = params.viewModel.scrollToMessageId,
                    ),
                    topPadding = params.contentPadding.calculateTopPadding(),
                ),
            )
        }
    }
}

private fun chatScreenContentPhase(state: ChatUiState): String = when {
    state.conversationState is ConversationState.Loading -> "loading"
    state.conversationState is ConversationState.Error -> "error"
    state.conversationState == ConversationState.NoConversation -> "no-conv"
    state.isLoadingMessages && state.messages.isEmpty() -> "loading"
    state.error != null && state.messages.isEmpty() -> "error"
    else -> "ready"
}

private data class ChatScreenPhaseContentParams(
    val phase: String,
    val state: ChatUiState,
    val viewModel: AdminChatViewModel,
    val contentCallbacks: ChatContentCallbacks,
    val appearance: ChatContentAppearance,
    val topPadding: Dp,
)

@Composable
private fun ChatScreenPhaseContent(params: ChatScreenPhaseContentParams) {
    when (params.phase) {
        "loading" -> MessageSkeletonList(
            modifier = Modifier.fillMaxSize().padding(top = params.topPadding),
        )
        "error" -> ChatScreenErrorPhase(params.state, params.viewModel, params.topPadding)
        "no-conv" -> NoConversationChatContent(
            state = params.state,
            callbacks = params.contentCallbacks,
            appearance = params.appearance,
            modifier = Modifier.fillMaxSize(),
        )
        else -> ChatContent(
            state = params.state,
            callbacks = params.contentCallbacks,
            appearance = params.appearance,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ChatScreenErrorPhase(
    state: ChatUiState,
    viewModel: AdminChatViewModel,
    topPadding: Dp,
) {
    val msg = (state.conversationState as? ConversationState.Error)?.message
        ?: state.error ?: "Unknown error"
    val retry = if (state.conversationState is ConversationState.Error) {
        { viewModel.retryConversationLoad() }
    } else {
        { viewModel.loadMessages() }
    }
    ChatScreenErrorContent(
        message = msg,
        onRetry = retry,
        modifier = Modifier.fillMaxSize().padding(top = topPadding),
    )
}

@Composable
private fun BoxScope.ChatScreenSubagentRingsOverlay(
    params: ChatScreenLayoutParams,
    openSubagentTarget: (SubagentTodoSheetTarget) -> Unit,
    onTargetChange: (SubagentTodoSheetTarget?) -> Unit,
    subagentNavigationScope: kotlinx.coroutines.CoroutineScope,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalSubagentTodoSheetOpener provides openSubagentTarget) {
        ActiveSubagentRings(
            subagents = params.subagentBarState.activeSubagents,
            now = params.subagentBarState.lingerTick,
            onRingClick = { subagent ->
                onTargetChange(
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
                        subagentNavigationScope = subagentNavigationScope,
                        haptic = haptic,
                        onTargetChange = onTargetChange,
                        onFloatingBannerMessageChange = params.onFloatingBannerMessageChange,
                    ),
                )
            },
            modifier = modifier,
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
private fun ChatScreenFloatingOverlays(
    floatingBannerMessage: String,
    imageViewerState: Pair<ImmutableList<UiImageAttachment>, Int>?,
    onImageViewerDismiss: () -> Unit,
    chatMode: String,
    a2uiDebugFrames: List<com.letta.mobile.ui.chat.render.A2uiDebugFrameUi>,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        FloatingBanner(
            visible = floatingBannerMessage.isNotBlank(),
            text = floatingBannerMessage,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(LettaSpacing.lg),
        )
        imageViewerState?.let { (viewerAttachments, initialIndex) ->
            ChatImageViewer(
                attachments = viewerAttachments,
                initialPage = initialIndex,
                onDismiss = onImageViewerDismiss,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (chatMode == "debug" && a2uiDebugFrames.isNotEmpty()) {
            A2uiDebugOverlay(
                frames = a2uiDebugFrames,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = LettaSpacing.lg, vertical = LettaSpacing.md),
            )
        }
        ChatScreenVoiceOverlay(modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ChatScreenComposerColumn(
    state: ChatUiState,
    composerState: ChatComposerState,
    viewModel: AdminChatViewModel,
    navigation: ChatScreenNavigationCallbacks,
    reducedMotion: Boolean,
    bottomInsetDp: Dp,
    onComposerHeightChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomInsetDp)
            .onSizeChanged { size ->
                onComposerHeightChange(with(density) { size.height.toDp() })
            },
    ) {
        ChatScreenGoalStatusSection(state, viewModel)
        ChatScreenThinkingTokenSection(state, reducedMotion)
        ChatScreenComposerInputSection(
            state = state,
            composerState = composerState,
            viewModel = viewModel,
            navigation = navigation,
        )
    }
}

@Composable
private fun ChatScreenGoalStatusSection(
    state: ChatUiState,
    viewModel: AdminChatViewModel,
) {
    GoalStatusCard(
        goal = state.goalStatus,
        loading = state.isGoalStatusLoading,
        callbacks = remember(viewModel) {
            GoalStatusCallbacks(
                onRefresh = viewModel::refreshGoalStatus,
                onContinue = viewModel::continueGoal,
                onPause = { viewModel.sendGoalCommand("/goal pause") },
                onResume = { viewModel.sendGoalCommand("/goal resume") },
                onComplete = { viewModel.sendGoalCommand("/goal complete") },
                onClear = { viewModel.sendGoalCommand("/goal clear") },
            )
        },
    )
}

@Composable
private fun ChatScreenThinkingTokenSection(
    state: ChatUiState,
    reducedMotion: Boolean,
) {
    val thinkingTokenActive = state.isStreaming ||
        state.isAgentTyping ||
        !state.a2uiThinkingDelayMessage.isNullOrBlank()
    ThinkingTextToken(
        visible = thinkingTokenActive,
        delayMessage = state.a2uiThinkingDelayMessage,
        reducedMotion = reducedMotion,
        reserveSpace = thinkingTokenActive,
    )
}

@Composable
private fun ChatScreenComposerInputSection(
    state: ChatUiState,
    composerState: ChatComposerState,
    viewModel: AdminChatViewModel,
    navigation: ChatScreenNavigationCallbacks,
) {
    val launchPicker = rememberImageAttachmentPicker(
        onPicked = { viewModel.addAttachment(it) },
        onError = { viewModel.reportComposerError(it) },
        limits = viewModel.attachmentLimits,
    )
    val activeAgent by viewModel.activeAgent.collectAsStateWithLifecycle()
    ChatComposer(
        inputText = composerState.inputText,
        pendingAttachments = composerState.pendingAttachments,
        isStreaming = state.isStreaming,
        canSendMessages = viewModel.canSendMessages,
        onTextChange = { newText ->
            if (viewModel.handleComposerTextChanged(newText) == ChatComposerEffect.OpenBugReport) {
                navigation.onBugCommand?.invoke()
            }
        },
        onSend = {
            if (viewModel.submitComposer(it) == ChatComposerEffect.OpenBugReport) {
                navigation.onBugCommand?.invoke()
            }
        },
        onStop = { viewModel.interruptRun() },
        onRemoveAttachment = { viewModel.removeAttachment(it) },
        onAttachImage = launchPicker,
        slashCommands = composerState.slashCommands,
        onSlashCommandSelected = viewModel::selectSlashCommand,
        onSlashCommandUninstall = viewModel::uninstallSlashCommand,
        availableTools = if (TOOL_AFFORDANCE_ROW_ENABLED) {
            activeAgent?.tools.orEmpty()
        } else {
            emptyList()
        },
    )
}

@Composable
private fun ChatScreenSubagentTodoSheet(
    target: SubagentTodoSheetTarget?,
    resolvedSubagentSource: ActiveSubagentSource,
    resolvedSelfTodoSource: com.letta.mobile.feature.chat.subagent.SelfTodoSource,
    currentConversationId: String?,
    navigation: ChatScreenNavigationCallbacks,
    onDismiss: () -> Unit,
    onTargetUpdate: (SubagentTodoSheetTarget?) -> Unit,
) {
    target?.let { sheetTarget ->
        var todoState by remember(sheetTarget.toolCallId) {
            mutableStateOf<SubagentTodoSheetState>(SubagentTodoSheetState.Loading)
        }
        LaunchedEffect(resolvedSubagentSource, resolvedSelfTodoSource, sheetTarget.toolCallId, currentConversationId) {
            val todos = if (sheetTarget.toolCallId == ActiveSubagent.SELF_ID) {
                Result.success(resolvedSelfTodoSource.todos(currentConversationId.orEmpty()))
            } else {
                resolvedSubagentSource.todos(sheetTarget.toolCallId)
            }
            todoState = subagentTodoSheetStateFrom(todos)
        }
        SubagentTodoSheet(
            description = sheetTarget.description,
            state = todoState,
            onDismiss = onDismiss,
            onViewConversation = sheetTarget.subagentAgentId
                ?.takeIf { it.isNotBlank() && navigation.onViewSubagentConversation != null }
                ?.let { agentId ->
                    sheetTarget.subagentNavigationConversationId?.let { conversationId ->
                        {
                            onTargetUpdate(null)
                            navigation.onViewSubagentConversation?.invoke(agentId, conversationId)
                        }
                    }
                },
        )
    }
}

@Composable
private fun ChatScreenVoiceOverlay(modifier: Modifier = Modifier) {
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
