package com.letta.mobile.feature.chat.screen

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
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

@Composable
internal fun ChatScreenLayout(
    state: ChatUiState,
    composerState: ChatComposerState,
    viewModel: AdminChatViewModel,
    contentPadding: PaddingValues,
    chatBackground: ChatBackground,
    chatMode: String,
    navigation: ChatScreenNavigationCallbacks,
    resolvedSubagentSource: ActiveSubagentSource,
    subagentBarState: ChatScreenSubagentBarState,
    activeFontScale: Float,
    onActiveFontScaleChange: (Float) -> Unit,
    bottomInsetDp: Dp,
    floatingBannerMessage: String,
    onFloatingBannerMessageChange: (String) -> Unit,
    streamingRevealPulse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotionEnabled()
    val subagentNavigationScope = rememberCoroutineScope()
    val currentConversationId = viewModel.conversationId?.value

    var tappedSubagentTarget by remember { mutableStateOf<SubagentTodoSheetTarget?>(null) }
    val openSubagentTarget: (SubagentTodoSheetTarget) -> Unit = { target ->
        tappedSubagentTarget = target
        if (target.subagentConversationId == null) {
            subagentNavigationScope.launch {
                val subagent = resolvedSubagentSource.resolveSubagent(target.toolCallId).getOrNull()
                val agentId = target.subagentAgentId ?: subagent?.subagentAgentId
                val conversationId = subagent?.let { resolvedSubagentSource.resolveConversationId(it).getOrNull() }
                if (agentId != null && conversationId != null) {
                    tappedSubagentTarget = target.copy(
                        subagentAgentId = agentId,
                        subagentConversationId = conversationId,
                    )
                }
            }
        }
    }
    var imageViewerState by remember {
        mutableStateOf<Pair<kotlinx.collections.immutable.ImmutableList<UiImageAttachment>, Int>?>(null)
    }
    val openImageViewer: (List<UiImageAttachment>, Int) -> Unit = { attachments, index ->
        imageViewerState = attachments.toImmutableList() to index
    }
    var composerHeightDp by remember { mutableStateOf(0.dp) }
    val bottomPaddingDp = composerHeightDp + bottomInsetDp

    val contentCallbacks = remember(viewModel, openImageViewer, onActiveFontScaleChange) {
        ChatContentCallbacks(
            onSendMessage = { viewModel.sendMessage(it) },
            onRerunMessage = { viewModel.rerunMessage(it) },
            onLoadOlderMessages = { viewModel.loadOlderMessages() },
            onSubmitApproval = { requestId, toolCallIds, approve, reason ->
                viewModel.submitApproval(requestId, toolCallIds, approve, reason)
            },
            onToggleRunCollapsed = viewModel::toggleRunCollapsed,
            onToggleReasoningExpanded = viewModel::toggleReasoningExpanded,
            onA2uiAction = viewModel::submitA2uiAction,
            onDismissA2uiSurface = viewModel::dismissA2uiSurface,
            onAttachmentImageTap = openImageViewer,
            onActiveFontScaleChange = onActiveFontScaleChange,
            onFontScaleChange = { viewModel.setChatFontScale(it) },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        val contentPhase = when {
            state.conversationState is ConversationState.Loading -> "loading"
            state.conversationState is ConversationState.Error -> "error"
            state.conversationState == ConversationState.NoConversation -> "no-conv"
            state.isLoadingMessages && state.messages.isEmpty() -> "loading"
            state.error != null && state.messages.isEmpty() -> "error"
            else -> "ready"
        }

        val truncatedToolResultResolver = remember(viewModel) {
            TruncatedToolResultResolver { messageId ->
                viewModel.onTruncatedToolResultExpanded(messageId)
            }
        }
        CompositionLocalProvider(
            LocalSubagentTodoSheetOpener provides openSubagentTarget,
            LocalStreamingRevealHapticPulse provides streamingRevealPulse,
            LocalTruncatedToolResultResolver provides truncatedToolResultResolver,
        ) {
            Crossfade(
                targetState = contentPhase,
                animationSpec = tween(durationMillis = 200),
                modifier = Modifier.fillMaxSize(),
                label = "chat-content",
            ) { phase ->
                val appearance = ChatContentAppearance(
                    chatMode = chatMode,
                    chatBackground = chatBackground,
                    topPadding = contentPadding.calculateTopPadding(),
                    bottomPadding = bottomPaddingDp,
                    activeFontScale = activeFontScale,
                    scrollToMessageId = viewModel.scrollToMessageId,
                )
                when (phase) {
                    "loading" -> {
                        MessageSkeletonList(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = contentPadding.calculateTopPadding())
                        )
                    }
                    "error" -> {
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
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = contentPadding.calculateTopPadding()),
                        )
                    }
                    "no-conv" -> {
                        NoConversationChatContent(
                            state = state,
                            callbacks = contentCallbacks,
                            appearance = appearance,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        ChatContent(
                            state = state,
                            callbacks = contentCallbacks,
                            appearance = appearance,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        CompositionLocalProvider(
            LocalSubagentTodoSheetOpener provides openSubagentTarget,
        ) {
            ActiveSubagentRings(
                subagents = subagentBarState.activeSubagents,
                now = subagentBarState.lingerTick,
                onRingClick = { subagent ->
                    tappedSubagentTarget = SubagentTodoSheetTarget(
                        toolCallId = subagent.id,
                        description = subagent.description,
                        subagentAgentId = subagent.subagentAgentId,
                        subagentConversationId = subagent.subagentConversationId,
                    )
                },
                onViewConversation = { subagent ->
                    subagentNavigationScope.launch {
                        val agentId = subagent.subagentAgentId?.takeIf { it.isNotBlank() }
                        val conversationId = if (agentId == null) {
                            null
                        } else {
                            resolvedSubagentSource.resolveConversationId(subagent)
                                .getOrNull()
                                ?.takeIf { it.isNotBlank() }
                        }
                        if (agentId != null && conversationId != null && navigation.onViewSubagentConversation != null) {
                            HapticEffects.longPress(haptic)
                            navigation.onViewSubagentConversation.invoke(agentId, conversationId)
                        } else {
                            tappedSubagentTarget = SubagentTodoSheetTarget(
                                toolCallId = subagent.id,
                                description = subagent.description,
                                subagentAgentId = subagent.subagentAgentId,
                                subagentConversationId = subagent.subagentConversationId,
                            )
                            onFloatingBannerMessageChange("Subagent conversation is not available yet")
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = contentPadding.calculateTopPadding() + 8.dp, end = 8.dp),
            )
        }

        ChatScreenComposerColumn(
            state = state,
            composerState = composerState,
            viewModel = viewModel,
            chatMode = chatMode,
            navigation = navigation,
            reducedMotion = reducedMotion,
            bottomInsetDp = bottomInsetDp,
            onComposerHeightChange = { composerHeightDp = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        ChatScreenSubagentTodoSheet(
            target = tappedSubagentTarget,
            resolvedSubagentSource = resolvedSubagentSource,
            resolvedSelfTodoSource = viewModel.selfTodoSource,
            currentConversationId = currentConversationId,
            navigation = navigation,
            onDismiss = { tappedSubagentTarget = null },
            onTargetUpdate = { tappedSubagentTarget = it },
        )

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
                onDismiss = { imageViewerState = null },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (chatMode == "debug" && state.a2uiDebugFrames.isNotEmpty()) {
            A2uiDebugOverlay(
                frames = state.a2uiDebugFrames,
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
    chatMode: String,
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
            }
    ) {
        GoalStatusCard(
            goal = state.goalStatus,
            loading = state.isGoalStatusLoading,
            onRefresh = viewModel::refreshGoalStatus,
            onContinue = viewModel::continueGoal,
            onPause = { viewModel.sendGoalCommand("/goal pause") },
            onResume = { viewModel.sendGoalCommand("/goal resume") },
            onComplete = { viewModel.sendGoalCommand("/goal complete") },
            onClear = { viewModel.sendGoalCommand("/goal clear") },
        )

        val thinkingTokenActive = state.isStreaming ||
            state.isAgentTyping ||
            !state.a2uiThinkingDelayMessage.isNullOrBlank()
        ThinkingTextToken(
            visible = thinkingTokenActive,
            delayMessage = state.a2uiThinkingDelayMessage,
            reducedMotion = reducedMotion,
            reserveSpace = thinkingTokenActive,
        )

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
