package com.letta.mobile.feature.chat.screen

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.a2ui.A2uiSurfaceRenderer
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.SnackbarMessage
import com.letta.mobile.ui.components.AmbientShaderAgentBackground
import com.letta.mobile.ui.components.FloatingBanner
import com.letta.mobile.ui.components.MessageSkeletonList
import com.letta.mobile.ui.components.StarterPrompts
import com.letta.mobile.ui.components.ThinkingShader
import com.letta.mobile.ui.components.ThinkingTextToken
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.feature.chat.coordination.ChatComposerEffect
import com.letta.mobile.data.chat.projection.IncrementalChatRenderItemsCache
import com.letta.mobile.data.chat.projection.toChatDisplayMode
import com.letta.mobile.feature.chat.render.A2uiDebugFrameUi
import com.letta.mobile.feature.chat.render.ChatUiState
import com.letta.mobile.feature.chat.render.ConversationState
import com.letta.mobile.feature.chat.render.buildToolCallTemplate
import com.letta.mobile.feature.chat.subagent.ActiveSubagent
import com.letta.mobile.feature.chat.subagent.ActiveSubagentBar
import kotlinx.collections.immutable.toImmutableList
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource
import com.letta.mobile.feature.chat.subagent.withLingeringTerminals
import com.letta.mobile.feature.chat.subagent.LocalSubagentTodoSheetOpener
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheet
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheetState
import com.letta.mobile.feature.chat.subagent.subagentTodoSheetStateFrom
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheetTarget
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import com.letta.mobile.ui.theme.isExpandedWidth
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.max

/**
 * Feature flag: when false, the tool-affordance chip strip above the
 * composer is suppressed. The component (`ToolAffordanceRow`), the
 * smart-template builder (`buildToolCallTemplate`), and the
 * `AdminChatViewModel.activeAgent` flow all stay wired â€” only the
 * call-site stops feeding tools through, so flipping to true re-enables
 * the row without any other change.
 */
private const val TOOL_AFFORDANCE_ROW_ENABLED = false

@Composable
internal fun ChatScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    chatBackground: ChatBackground = ChatBackground.Default,
    chatMode: String = "simple",
    onBugCommand: (() -> Unit)? = null,
    // letta-mobile-vo9y1: jump from a subagent chip / its todo sheet to that
    // subagent's own conversation. Receives the subagent agent id
    // (`agent-local-*`); the host (AgentScaffold) maps it to a conversation
    // switch. Null when navigation is not available (e.g. previews).
    onViewSubagentConversation: ((String) -> Unit)? = null,
    // letta-mobile-73o2h.2/.3: the WS SEAM for the active-subagent status
    // bar. In production this is left null so the screen binds the real
    // WS-backed source from the view model (the per-socket subagent registry
    // shipped in letta-mobile-73o2h.1). Previews/tests pass an explicit
    // FakeActiveSubagentSource. The bar itself is unchanged â€” only the feed
    // is swapped at this single seam.
    activeSubagentSource: ActiveSubagentSource? = null,
    // letta-mobile-lgm98: optional self-todo source seam (previews/tests pass a
    // fake). Defaults to the viewModel's WS-backed source in production.
    selfTodoSource: com.letta.mobile.feature.chat.subagent.SelfTodoSource? = null,
    viewModel: AdminChatViewModel = hiltViewModel()
) {
    val resolvedSubagentSource = activeSubagentSource ?: viewModel.activeSubagentSource
    val resolvedSelfTodoSource = selfTodoSource ?: viewModel.selfTodoSource
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val composerState by viewModel.composerState.collectAsStateWithLifecycle()
    val fontScale by viewModel.chatFontScale.collectAsStateWithLifecycle()
    val projectBindings = viewModel.projectBindings

    var activeFontScale by remember { mutableFloatStateOf(fontScale) }
    LaunchedEffect(fontScale) { activeFontScale = fontScale }

    // Timeline sync loop handles live updates â€” no on-resume refresh needed.

    val backgroundModifier = when (chatBackground) {
        is ChatBackground.Default -> Modifier
        is ChatBackground.SolidColor -> Modifier.background(chatBackground.color)
        is ChatBackground.Gradient -> Modifier.background(chatBackground.toBrush())
    }

    LettaChatTheme(fontScale = activeFontScale) {
        var floatingBannerMessage by remember { mutableStateOf("") }
        val snackbarDispatcher = LocalSnackbarDispatcher.current
        val density = LocalDensity.current
        val haptic = LocalHapticFeedback.current
        val view = LocalView.current
        val reducedMotion = rememberReducedMotionEnabled()
        // letta-mobile-73o2h.2 / 29h9u: collect the subagent snapshot for the
        // bottom status bar. The source already emits running chips plus
        // briefly-lingering terminal chips (the WS source stamps terminals
        // with `terminalAt`). Here we apply `withLingeringTerminals(now)` so a
        // completed/failed chip stays visible for its dwell, then auto-drops.
        // A coarse 1s tick re-evaluates the window so an expired terminal
        // dismisses on its own without any new WS frame. Snapshots still
        // reduce by replacement (no per-frame rebuild â€” preserves rmzmo).
        val subagentSnapshot by resolvedSubagentSource.activeSubagents
            .collectAsStateWithLifecycle(initialValue = persistentListOf<ActiveSubagent>())
        var lingerTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(subagentSnapshot) {
            // Tick while a terminal chip is lingering (to expire its window) OR
            // while any chip is RUNNING (letta-mobile-dvobc: so the "stuck"
            // ring heuristic re-evaluates elapsed-since-last-update without a
            // new WS frame). When the bar is fully idle there is nothing to
            // re-evaluate, so we idle â€” no wakeups on the hot streaming path.
            while (subagentSnapshot.any { it.isTerminal || it.isActive }) {
                lingerTick = System.currentTimeMillis()
                kotlinx.coroutines.delay(1_000)
            }
            lingerTick = System.currentTimeMillis()
        }
        // letta-mobile-lgm98: the MAIN agent's OWN TodoWrite plan ("self"
        // entry). The shim broadcasts it (jb4gu) and SelfTodoRepository keys it
        // by conversation id; we observe it for the CURRENT conversation and
        // merge it into the bar list so ActiveSubagentBar's isSelf branch can
        // render the SelfChip. The self entry is always-present-while-a-plan-
        // exists (not terminal-lingered), so it is prepended AFTER the linger
        // transform runs over the subagent entries only.
        val currentConversationId = viewModel.conversationId?.value
        val selfEntry by remember(resolvedSelfTodoSource, currentConversationId) {
            if (currentConversationId.isNullOrBlank()) {
                kotlinx.coroutines.flow.flowOf<ActiveSubagent?>(null)
            } else {
                resolvedSelfTodoSource.selfEntry(currentConversationId)
            }
        }.collectAsStateWithLifecycle(initialValue = null)
        val activeSubagents = remember(subagentSnapshot, lingerTick, selfEntry) {
            val subagents = subagentSnapshot.withLingeringTerminals(lingerTick)
            val self = selfEntry
            if (self == null) subagents
            else (listOf(self) + subagents).toImmutableList()
        }
        val windowSizeClass = LocalWindowSizeClass.current
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val navBottomPx = WindowInsets.navigationBars.getBottom(density)
        val bottomBarPx = 0
        val bottomInsetDp = with(density) { max(imeBottomPx, navBottomPx + bottomBarPx).toDp() }
        var ambientAgentStatus by remember { mutableStateOf("Idle") }
        var hadActiveAmbientRun by remember { mutableStateOf(false) }
        // letta-mobile-73o2h.3 / pbnxa: tap-to-todolist sheet. Chips and
        // first-class subagent timeline cards both set the same target so
        // active bar + dispatch/result chrome open one coherent todo surface.
        var tappedSubagentTarget by remember { mutableStateOf<SubagentTodoSheetTarget?>(null) }
        var imageViewerState by remember {
            mutableStateOf<Pair<kotlinx.collections.immutable.ImmutableList<UiImageAttachment>, Int>?>(null)
        }
        val openImageViewer: (List<UiImageAttachment>, Int) -> Unit = { attachments, index ->
            imageViewerState = attachments.toImmutableList() to index
        }

        LaunchedEffect(composerState.error) {
            val message = composerState.error ?: return@LaunchedEffect
            HapticEffects.reject(haptic, view)
            floatingBannerMessage = message
            viewModel.clearComposerError()
        }

        LaunchedEffect(floatingBannerMessage) {
            if (floatingBannerMessage.isNotBlank()) {
                kotlinx.coroutines.delay(2600)
                floatingBannerMessage = ""
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
                state.error != null -> ambientAgentStatus = "Failed"
                state.isStreaming || state.isAgentTyping -> {
                    hadActiveAmbientRun = true
                    ambientAgentStatus = "Running"
                }
                hadActiveAmbientRun -> {
                    ambientAgentStatus = "Completed"
                    kotlinx.coroutines.delay(1400)
                    hadActiveAmbientRun = false
                    ambientAgentStatus = "Idle"
                }
                else -> ambientAgentStatus = "Idle"
            }
        }

        // letta-mobile-ndtc.3: the 60s delay subtitle is rendered inline by
        // ThinkingTextToken below (not as a snackbar/banner). The ViewModel
        // keeps `a2uiThinkingDelayMessage` set until the next thinking start
        // clears it (see startA2uiThinkingIndicator), so the operator sees
        // the subtitle until they take their next action.

        AmbientShaderAgentBackground(
            agentStatus = ambientAgentStatus,
            modifier = modifier
                .fillMaxSize()
                .then(backgroundModifier),
        ) {
            // letta-mobile-vcky.b3: thinking glow declared BEFORE the
            // Column so it paints first (behind everything). Aligned to
            // BottomCenter â€” strip bottom hugs the bottom of the chat
            // region (top of nav bar / IME). The shader peak (uv.y=0.92
            // of a 216dp strip â†’ ~17dp above the strip bottom) lands
            // inside the composer's painted area; the composer (opaque
            // Surface) covers the peak. What's visible above the composer
            // is the long diffuse upper tail. Half-opacity vs. the
            // previous typing-slot strip (0.39 vs 0.78 in the shader).
            // vcky.b6: ease the glow in/out instead of the default
            // FastOutSlowIn curve (which front-loads the change and
            // looks like a pop). EaseInOutCubic spends the same time
            // accelerating and decelerating, so the glow grows from
            // invisible to full smoothly; doubled the duration to 900ms
            // for a gentle build.
            val thinkingAlpha by animateFloatAsState(
                targetValue = if (state.isStreaming) 1f else 0f,
                animationSpec = tween(durationMillis = if (reducedMotion) 0 else 900, easing = EaseInOutCubic),
                label = "thinkingAlpha",
            )
            // TODO: Fix ThinkingShader blending with transparent background
            // Commented out until blend can be fixed to work with edge-to-edge transparent chrome
            /*
            if (thinkingAlpha > 0.001f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .alpha(thinkingAlpha)
                ) {
                    ThinkingShader(
                        // p2auf: theme-controlled chaser. Pass the active
                        // theme's accent triad; the shader deepens each color
                        // (saturates + caps luminance) so even pale theme
                        // accents read as hue on the dark surface, then chases
                        // them across the band over time.
                        tint = MaterialTheme.colorScheme.primary,
                        tint2 = MaterialTheme.colorScheme.tertiary,
                        tint3 = MaterialTheme.colorScheme.secondary,
                        // With transparent background, use a neutral blend target
                        // The alpha gradient overlay will handle the actual blending
                        bgColor = Color.Transparent,
                        animate = !reducedMotion,
                    )
                    // Vertical alpha gradient overlay: 0% at top (transparent) to 100% at bottom
                    // This allows the shader to blend seamlessly with any background
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .matchParentSize()
                    ) {
                        drawRect(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black),
                                startY = 0f,
                                endY = size.height,
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    }
                }
            }
            */
            var composerHeightDp by remember { mutableStateOf(0.dp) }
            val bottomPaddingDp = composerHeightDp + bottomInsetDp

            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                val contentPhase = when {
                    state.conversationState is ConversationState.Loading -> "loading"
                    state.conversationState is ConversationState.Error -> "error"
                    state.conversationState == ConversationState.NoConversation -> "no-conv"
                    state.isLoadingMessages && state.messages.isEmpty() -> "loading"
                    state.error != null && state.messages.isEmpty() -> "error"
                    else -> "ready"
                }

                CompositionLocalProvider(
                    LocalSubagentTodoSheetOpener provides { target ->
                        tappedSubagentTarget = target
                    },
                ) {
                    Crossfade(
                        targetState = contentPhase,
                        animationSpec = tween(durationMillis = 200),
                        modifier = Modifier.fillMaxSize(),
                        label = "chat-content",
                    ) { phase ->
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
                                ErrorContent(
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
                                    scrollToMessageId = viewModel.scrollToMessageId,
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
                                    activeFontScale = activeFontScale,
                                    onActiveFontScaleChange = { activeFontScale = it },
                                    onFontScaleChange = { viewModel.setChatFontScale(it) },
                                    chatMode = chatMode,
                                    modifier = Modifier.fillMaxSize(),
                                    chatBackground = chatBackground,
                                    topPadding = contentPadding.calculateTopPadding(),
                                    bottomPadding = bottomPaddingDp,
                                )
                            }
                            else -> {
                                ChatContent(
                                    state = state,
                                    scrollToMessageId = viewModel.scrollToMessageId,
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
                                    activeFontScale = activeFontScale,
                                    onActiveFontScaleChange = { activeFontScale = it },
                                    onFontScaleChange = { viewModel.setChatFontScale(it) },
                                    chatMode = chatMode,
                                    modifier = Modifier.fillMaxSize(),
                                    chatBackground = chatBackground,
                                    topPadding = contentPadding.calculateTopPadding(),
                                    bottomPadding = bottomPaddingDp,
                                )
                            }
                        }
                    }
                }

                // Floating composer column aligned to BottomCenter
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = bottomInsetDp)
                        .onSizeChanged { size ->
                            composerHeightDp = with(density) { size.height.toDp() }
                        }
                ) {
                    CompositionLocalProvider(
                        LocalSubagentTodoSheetOpener provides { target ->
                            tappedSubagentTarget = target
                        },
                    ) {
                        ActiveSubagentBar(
                            subagents = activeSubagents,
                            now = lingerTick,
                            onChipClick = { subagent ->
                                tappedSubagentTarget = SubagentTodoSheetTarget(
                                    toolCallId = subagent.id,
                                    description = subagent.description,
                                    subagentAgentId = subagent.subagentAgentId,
                                )
                            },
                            onViewConversation = { subagent ->
                                subagent.subagentAgentId
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { agentId -> onViewSubagentConversation?.invoke(agentId) }
                            },
                        )
                    }

                    // letta-mobile-ndtc.3: gradient "thinking" text token —
                    // ephemeral subtitle that appears between the message list /
                    // A2UI surfaces and the composer while awaiting the agent's
                    // first frame. Driven by `isAgentTyping`; switches to the
                    // delay subtitle on the 60s A2UI timeout.
                    ThinkingTextToken(
                        visible = state.isAgentTyping,
                        delayMessage = state.a2uiThinkingDelayMessage,
                        reducedMotion = reducedMotion,
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
                                onBugCommand?.invoke()
                            }
                        },
                        onSend = {
                            if (viewModel.submitComposer(it) == ChatComposerEffect.OpenBugReport) {
                                onBugCommand?.invoke()
                            }
                        },
                        onStop = { viewModel.interruptRun() },
                        onRemoveAttachment = { viewModel.removeAttachment(it) },
                        onAttachImage = launchPicker,
                        availableTools = if (TOOL_AFFORDANCE_ROW_ENABLED) {
                            activeAgent?.tools.orEmpty()
                        } else {
                            emptyList()
                        },
                    )
                }

                // letta-mobile-73o2h.3: tap-to-todolist bottom sheet. One-shot
                // fetch of the tapped subagent's TodoWrite via the source.
                tappedSubagentTarget?.let { target ->
                    var todoState by remember(target.toolCallId) {
                        mutableStateOf<SubagentTodoSheetState>(SubagentTodoSheetState.Loading)
                    }
                    LaunchedEffect(resolvedSubagentSource, resolvedSelfTodoSource, target.toolCallId, currentConversationId) {
                        // letta-mobile-lgm98: the self chip's target carries the
                        // reserved SELF_ID; resolve its checklist from the
                        // self-todo source (keyed by conversation), not the
                        // subagent source (keyed by tool-call id).
                        val todos = if (target.toolCallId == ActiveSubagent.SELF_ID) {
                            // self source returns a plain list (no async fetch)
                            Result.success(resolvedSelfTodoSource.todos(currentConversationId.orEmpty()))
                        } else {
                            resolvedSubagentSource.todos(target.toolCallId)
                        }
                        todoState = subagentTodoSheetStateFrom(todos)
                    }
                    SubagentTodoSheet(
                        description = target.description,
                        state = todoState,
                        onDismiss = { tappedSubagentTarget = null },
                        // letta-mobile-vo9y1: offer "view chat" in the sheet
                        // when the subagent run is correlated and the host can
                        // navigate. Tapping dismisses the sheet then jumps.
                        onViewConversation = target.subagentAgentId
                            ?.takeIf { it.isNotBlank() && onViewSubagentConversation != null }
                            ?.let { agentId ->
                                {
                                    tappedSubagentTarget = null
                                    onViewSubagentConversation?.invoke(agentId)
                                }
                            },
                    )
                }

            FloatingBanner(
                visible = floatingBannerMessage.isNotBlank(),
                text = floatingBannerMessage,
                modifier = Modifier
                    .align(Alignment.TopCenter)
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

            // letta-mobile-arhd: full-screen voice recognition overlay.
            // Sibling of the Column so it floats above the chat content
            // + composer while the user holds the mic. Scrim has no
            // pointer-consuming modifier so the HoldToDictateButton's
            // gesture tracking in the composer underneath keeps firing.
            //
            // Same Hilt-host guard as in ChatComposer: AgentScaffoldHiltTest
            // hosts ChatScreen on a plain ComponentActivity, so we skip the
            // voice overlay when there's no Hilt host (production always
            // has one).
            val voiceActivity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            val voiceIsHiltHost = voiceActivity is dagger.hilt.internal.GeneratedComponentManager<*>
            if (voiceIsHiltHost) {
                val voiceVm: com.letta.mobile.feature.chat.voice.VoiceInputViewModel =
                    androidx.hilt.navigation.compose.hiltViewModel()
                val voiceState by voiceVm.uiState.collectAsStateWithLifecycle()
                com.letta.mobile.ui.components.audio.VoiceRecognizerOverlay(
                    visible = voiceState.recognizing,
                    recognizedText = voiceState.recognizedText,
                    amplitude = voiceState.amplitude,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    }
}

@Composable
private fun A2uiDebugOverlay(
    frames: List<A2uiDebugFrameUi>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = MaterialTheme.shapes.small,
            )
            .padding(LettaSpacing.sm),
    ) {
        Text(
            text = "A2UI frames",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = LettaSpacing.xs))
        LazyColumn(modifier = Modifier.height(LettaSpacing.xxxl.times(2))) {
            items(frames.takeLast(8).asReversed(), key = { it.id }) { frame ->
                Text(
                    text = buildString {
                        append(frame.messageType)
                        frame.surfaceId?.let { append(" / ").append(it) }
                        frame.conversationId?.takeLast(6)?.let { append(" / conv:").append(it) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun shouldShowStarterPromptsForNoConversation(state: ChatUiState): Boolean =
    state.messages.isEmpty() && !state.isStreaming && state.a2uiSurfaces.isEmpty()

@Composable
internal fun NoConversationChatContent(
    state: ChatUiState,
    scrollToMessageId: String? = null,
    onSendMessage: (String) -> Unit,
    onRerunMessage: (com.letta.mobile.data.model.UiMessage) -> Unit,
    onLoadOlderMessages: () -> Unit,
    onSubmitApproval: (String, List<String>, Boolean, String?) -> Unit,
    onToggleRunCollapsed: (String) -> Unit,
    onToggleReasoningExpanded: (String) -> Unit,
    onA2uiAction: (A2uiAction) -> Unit = {},
    onDismissA2uiSurface: (String) -> Unit = {},
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)?,
    activeFontScale: Float = 1f,
    onActiveFontScaleChange: (Float) -> Unit = {},
    onFontScaleChange: (Float) -> Unit = {},
    chatMode: String = "simple",
    modifier: Modifier = Modifier,
    chatBackground: ChatBackground = ChatBackground.Default,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
) {
    // letta-mobile-qkct: a fresh Client Mode send remains in
    // NoConversation until the gateway returns the newly-created
    // conversation id. During that pending window the VM already owns
    // optimistic messages and streaming flags; render the chat body instead
    // of the empty starter prompts so the user's bubble is visible
    // immediately.
    if (shouldShowStarterPromptsForNoConversation(state)) {
        StarterPrompts(
            onPromptClick = onSendMessage,
            modifier = modifier.padding(top = topPadding, bottom = bottomPadding),
        )
    } else {
        ChatContent(
            state = state,
            scrollToMessageId = scrollToMessageId,
            onSendMessage = onSendMessage,
            onRerunMessage = onRerunMessage,
            onLoadOlderMessages = onLoadOlderMessages,
            onSubmitApproval = onSubmitApproval,
            onToggleRunCollapsed = onToggleRunCollapsed,
            onToggleReasoningExpanded = onToggleReasoningExpanded,
            onA2uiAction = onA2uiAction,
            onDismissA2uiSurface = onDismissA2uiSurface,
            onAttachmentImageTap = onAttachmentImageTap,
            activeFontScale = activeFontScale,
            onActiveFontScaleChange = onActiveFontScaleChange,
            onFontScaleChange = onFontScaleChange,
            chatMode = chatMode,
            modifier = modifier,
            chatBackground = chatBackground,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
        )
    }
}

@Composable
private fun ChatContent(
    state: ChatUiState,
    scrollToMessageId: String? = null,
    onSendMessage: (String) -> Unit,
    onRerunMessage: (com.letta.mobile.data.model.UiMessage) -> Unit,
    onLoadOlderMessages: () -> Unit,
    onSubmitApproval: (String, List<String>, Boolean, String?) -> Unit,
    onToggleRunCollapsed: (String) -> Unit,
    onToggleReasoningExpanded: (String) -> Unit,
    onA2uiAction: (A2uiAction) -> Unit = {},
    onDismissA2uiSurface: (String) -> Unit = {},
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)?,
    activeFontScale: Float = 1f,
    onActiveFontScaleChange: (Float) -> Unit = {},
    onFontScaleChange: (Float) -> Unit = {},
    chatMode: String = "simple",
    modifier: Modifier = Modifier,
    chatBackground: ChatBackground = ChatBackground.Default,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
) {
    val renderItemsCache = remember { IncrementalChatRenderItemsCache() }
    val chatDisplayMode = chatMode.toChatDisplayMode()
    val renderItems = remember(state.messages, chatMode, state.messageListChange) {
        renderItemsCache.renderItems(
            messages = state.messages,
            mode = chatDisplayMode,
            change = state.messageListChange,
        )
    }

    var a2uiStackHeightDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Box(modifier = modifier.fillMaxSize()) {
        if (state.messages.isEmpty() && !state.isStreaming && state.a2uiSurfaces.isEmpty()) {
            StarterPrompts(
                onPromptClick = onSendMessage,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPadding, bottom = bottomPadding),
            )
        } else {
            val listBottomPadding = bottomPadding + if (state.a2uiSurfaces.isNotEmpty()) a2uiStackHeightDp else 0.dp
            if (state.messages.isEmpty() && !state.isStreaming) {
                // List is empty but A2UI is shown
            } else {
                ChatMessageList(
                    state = state,
                    renderItems = renderItems,
                    chatMode = chatMode,
                    scrollToMessageId = scrollToMessageId,
                    activeFontScale = activeFontScale,
                    onActiveFontScaleChange = onActiveFontScaleChange,
                    onFontScaleChange = onFontScaleChange,
                    onLoadOlderMessages = onLoadOlderMessages,
                    onSendMessage = onSendMessage,
                    onRerunMessage = onRerunMessage,
                    onSubmitApproval = onSubmitApproval,
                    onToggleRunCollapsed = onToggleRunCollapsed,
                    onToggleReasoningExpanded = onToggleReasoningExpanded,
                    onAttachmentImageTap = onAttachmentImageTap,
                    modifier = Modifier.fillMaxSize(),
                    chatBackground = chatBackground,
                    topPadding = topPadding,
                    bottomPadding = listBottomPadding,
                )
            }
            if (state.a2uiSurfaces.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = bottomPadding)
                        .onSizeChanged { size ->
                            a2uiStackHeightDp = with(density) { size.height.toDp() }
                        }
                ) {
                    A2uiSurfaceStack(
                        surfaces = state.a2uiSurfaces,
                        resolvedActionCounters = state.a2uiResolvedActionCounters,
                        onAction = onA2uiAction,
                        onDismissSurface = onDismissA2uiSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LettaSpacing.lg, vertical = LettaSpacing.sm),
                    )
                }
            }
        }
    }
}

@Composable
private fun A2uiSurfaceStack(
    surfaces: ImmutableMap<String, A2uiSurfaceState>,
    resolvedActionCounters: Map<String, Int>,
    onAction: (A2uiAction) -> Unit,
    onDismissSurface: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (surfaces.isEmpty()) return
    // perf/frame-budget-audit: key the sort on the (immutable) surfaces map so
    // it doesn't re-sort on every recompose of the stack.
    val orderedSurfaces = remember(surfaces) {
        surfaces.values.sortedBy(A2uiSurfaceState::surfaceId)
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(LettaSpacing.sm),
    ) {
        orderedSurfaces.forEach { surface ->
            key(surface.surfaceId) {
                DismissibleA2uiSurface(
                    surfaceId = surface.surfaceId,
                    onDismissSurface = onDismissSurface,
                ) {
                    A2uiSurfaceRenderer(
                        surface = surface,
                        modifier = Modifier.fillMaxWidth(),
                        onAction = onAction,
                        actionResolutionToken = resolvedActionCounters[surface.surfaceId] ?: 0,
                    )
                }
            }
        }
    }
}

@Composable
private fun DismissibleA2uiSurface(
    surfaceId: String,
    onDismissSurface: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    var menuExpanded by remember(surfaceId) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Dismiss A2UI surface") {
                        onDismissSurface(surfaceId)
                        true
                    }
                )
            }
            .longPressPassthrough { menuExpanded = true },
    ) {
        content()
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Dismiss") },
                onClick = {
                    menuExpanded = false
                    onDismissSurface(surfaceId)
                },
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = LettaIcons.Error,
            contentDescription = "Error",
            modifier = Modifier.size(LettaSpacing.xxxl),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(LettaSpacing.lg))
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(LettaSpacing.lg))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

// NoConversationContent (the prior placeholder for ConversationState.
// NoConversation showing only "Start a conversation / Send a message to
// create a new conversation.") was removed when the empty-state for the
// in-chat "New Conversation" path was unified with the chat-list FAB
// path â€” both now render StarterPrompts. The strings
// screen_chat_empty_title and screen_chat_empty_subtitle remain in
// res/values/strings.xml in case a future surface needs them.
