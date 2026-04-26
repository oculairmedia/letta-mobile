package com.letta.mobile.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import com.letta.mobile.ui.theme.LocalChatIsPinching
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import androidx.compose.material3.FilterChip
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.common.groupMessages
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.components.MessageSkeletonList
import com.letta.mobile.ui.components.StarterPrompts
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.components.LettaInputBar
import com.letta.mobile.ui.components.ScrollToBottomFab
import com.letta.mobile.ui.components.TypingIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.letta.mobile.ui.icons.LettaIcons

@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    chatBackground: ChatBackground = ChatBackground.Default,
    onBugCommand: (() -> Unit)? = null,
    viewModel: AdminChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val fontScale by viewModel.chatFontScale.collectAsStateWithLifecycle()

    var activeFontScale by remember { mutableFloatStateOf(fontScale) }
    LaunchedEffect(fontScale) { activeFontScale = fontScale }

    // Timeline sync loop handles live updates — no on-resume refresh needed.

    val backgroundModifier = when (chatBackground) {
        is ChatBackground.Default -> Modifier
        is ChatBackground.SolidColor -> Modifier.background(chatBackground.color)
        is ChatBackground.Gradient -> Modifier.background(chatBackground.toBrush())
    }

    LettaChatTheme(fontScale = activeFontScale) {
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(state.composerError) {
            val message = state.composerError ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(message)
            viewModel.clearComposerError()
        }

        // letta-mobile-6vsx: use union(ime, navigationBars) instead of
        // bare imePadding() so the bottom inset is min-floored at nav-bar
        // height. With enableEdgeToEdge() + windowSoftInputMode=adjustNothing,
        // bare imePadding() lets the composer animate down to the absolute
        // screen bottom (under the nav bar) during the IME-hide animation,
        // then snaps back up when an outer scaffold's nav-bar inset
        // re-takes effect — the visible "drop then rise" symptom.
        // With the union the IME animation interpolates between IME_height
        // and nav_bar_height, never below, and the composer's resting
        // position is correct from the very start of the hide animation.
        Box(
            modifier = modifier
                .fillMaxSize()
                .then(backgroundModifier)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // letta-mobile-c87t: surfaces a non-modal banner when the
                // lettabot WS gateway substituted a fresh conversation for the
                // one we asked to resume. See ClientModeConversationSwapBanner.
                val swap = state.clientModeConversationSwap
                com.letta.mobile.ui.components.ClientModeConversationSwapBanner(
                    visible = swap != null,
                    onDismiss = { viewModel.dismissClientModeConversationSwap() },
                    requestedConversationIdSuffix = swap?.requestedConversationId?.takeLast(6),
                    newConversationIdSuffix = swap?.newConversationId?.takeLast(6),
                )
                when (val conversationState = state.conversationState) {
                    ConversationState.Loading -> {
                        MessageSkeletonList(modifier = Modifier.weight(1f))
                    }
                    is ConversationState.Error -> {
                        ErrorContent(
                            message = conversationState.message,
                            onRetry = { viewModel.retryConversationLoad() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ConversationState.NoConversation -> {
                        NoConversationContent(modifier = Modifier.weight(1f))
                    }
                    is ConversationState.Ready -> {
                        if (state.isLoadingMessages && state.messages.isEmpty()) {
                            MessageSkeletonList(modifier = Modifier.weight(1f))
                        } else if (state.error != null && state.messages.isEmpty()) {
                            ErrorContent(
                                message = state.error!!,
                                onRetry = { viewModel.loadMessages() },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            ChatContent(
                                state = state,
                                scrollToMessageId = viewModel.scrollToMessageId,
                                onSendMessage = { viewModel.sendMessage(it) },
                                onLoadOlderMessages = { viewModel.loadOlderMessages() },
                                onSubmitApproval = { requestId, toolCallIds, approve, reason ->
                                    viewModel.submitApproval(requestId, toolCallIds, approve, reason)
                                },
                                onInputTextChange = { viewModel.updateInputText(it) },
                                onToggleRunCollapsed = viewModel::toggleRunCollapsed,
                                onToggleReasoningExpanded = viewModel::toggleReasoningExpanded,
                                activeFontScale = activeFontScale,
                                onActiveFontScaleChange = { activeFontScale = it },
                                onFontScaleChange = { viewModel.setChatFontScale(it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                val launchPicker = rememberImageAttachmentPicker(
                    onPicked = { viewModel.addAttachment(it) },
                    onError = { viewModel.reportComposerError(it) },
                )
                ChatComposer(
                    inputText = inputText,
                    pendingAttachments = state.pendingAttachments,
                    isStreaming = state.isStreaming,
                    canSendMessages = viewModel.canSendMessages,
                    onTextChange = { newText ->
                        if (newText.endsWith("\n") && !state.isStreaming &&
                            (inputText.isNotBlank() || state.pendingAttachments.isNotEmpty())
                        ) {
                            if (viewModel.tryHandleSlashCommand(inputText)) {
                                viewModel.updateInputText("")
                                onBugCommand?.invoke()
                            } else {
                                viewModel.sendMessage(inputText)
                            }
                        } else {
                            viewModel.updateInputText(newText)
                        }
                    },
                    onSend = {
                        if (viewModel.tryHandleSlashCommand(it)) {
                            viewModel.updateInputText("")
                            onBugCommand?.invoke()
                        } else {
                            viewModel.sendMessage(it)
                        }
                    },
                    onRemoveAttachment = { viewModel.removeAttachment(it) },
                    onAttachImage = launchPicker,
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun ChatContent(
    state: ChatUiState,
    scrollToMessageId: String? = null,
    onSendMessage: (String) -> Unit,
    onLoadOlderMessages: () -> Unit,
    onSubmitApproval: (String, List<String>, Boolean, String?) -> Unit,
    onInputTextChange: (String) -> Unit,
    onToggleRunCollapsed: (String) -> Unit,
    onToggleReasoningExpanded: (String) -> Unit,
    activeFontScale: Float = 1f,
    onActiveFontScaleChange: (Float) -> Unit = {},
    onFontScaleChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var chatMode by remember { mutableStateOf("interactive") }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var hasScrolledToTarget by remember { mutableStateOf(false) }
    var showFontIndicator by remember { mutableStateOf(false) }
    var pinchTick by remember { mutableStateOf(0L) }
    // letta-mobile-5e0f.r2: pinch-active flag plumbed via
    // LocalChatIsPinching so animateContentSize sites in the chat tree
    // can suppress themselves during the gesture (and re-enable for
    // their normal triggers like collapse/expand). True from the first
    // 2-finger event in a gesture until the user lifts.
    var isPinching by remember { mutableStateOf(false) }

    LaunchedEffect(pinchTick) {
        if (pinchTick > 0) {
            showFontIndicator = true
            delay(1000)
            showFontIndicator = false
        }
    }

    val messageCount by rememberUpdatedState(state.messages.size)

    val isAtBottom by remember {
        derivedStateOf {
            val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
            firstVisible <= 1
        }
    }

    val showScrollFab by remember {
        derivedStateOf {
            val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
            firstVisible > 3
        }
    }

    val shouldLoadOlderMessages by remember {
        derivedStateOf {
            if (!state.hasMoreOlderMessages || state.isLoadingOlderMessages || state.messages.isEmpty()) {
                return@derivedStateOf false
            }

            val lastVisible = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 3
        }
    }

    // letta-mobile-d2z6 follow-up: the streaming/animated scroll branch
    // introduced a duplicate-flash on stream end (the LaunchedEffect
    // re-emitted as isStreaming flipped, racing with the bubble's settled
    // layout). Reverted to the original animateScrollToItem behaviour;
    // bubble flicker is being addressed at the rendering layer instead.
    LaunchedEffect(Unit) {
        snapshotFlow { messageCount }
            .distinctUntilChanged()
            .collect {
                if (it > 0 && isAtBottom && scrollToMessageId == null) {
                    listState.animateScrollToItem(0)
                }
            }
    }

    LaunchedEffect(listState, state.hasMoreOlderMessages, state.isLoadingOlderMessages, state.messages.size) {
        snapshotFlow { shouldLoadOlderMessages }
            .distinctUntilChanged()
            .collect { shouldLoad ->
                if (shouldLoad) {
                    onLoadOlderMessages()
                }
            }
    }

    val dedupedMessages = remember(state.messages, chatMode) {
        val result = mutableListOf<UiMessage>()
        var lastReasoningContent: String? = null
        for (msg in state.messages) {
            if (msg.isReasoning) {
                lastReasoningContent = msg.content
                result.add(msg)
            } else if (msg.role == "assistant" && msg.content == lastReasoningContent) {
                // Skip assistant message that duplicates the reasoning content
            } else {
                lastReasoningContent = null
                result.add(msg)
            }
        }
        when (chatMode) {
            // letta-mobile-5s1n: keep error frames visible in Simple mode so
            // users see when a run aborts (otherwise the bubble is filtered
            // out and the experience degrades to the silent-spinner bug
            // we just fixed).
            "simple" -> result.filter {
                it.role == "user" || (it.role == "assistant" && !it.isReasoning) || it.isError
            }
            else -> result
        }
    }

    val groupedMessages = remember(dedupedMessages) {
        groupMessages(
            messages = dedupedMessages,
            getRole = { it.role },
            getTimestamp = { it.timestamp },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("simple", "interactive", "debug").forEach { mode ->
                FilterChip(
                    selected = chatMode == mode,
                    onClick = { chatMode = mode },
                    label = { Text(mode.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        if (state.messages.isEmpty() && !state.isStreaming) {
            StarterPrompts(
                onPromptClick = onSendMessage,
                modifier = Modifier.weight(1f),
            )
        } else {
            val reversed = remember(groupedMessages) {
                // Defensive: LazyColumn crashes on duplicate item keys. mergeOlderMessages
                // already dedupes by id, but a late streaming tick or reasoning-collapse
                // edge case could still leak duplicates — so we guard here too.
                val seen = HashSet<String>(groupedMessages.size)
                groupedMessages.filter { (msg, _) -> seen.add(msg.id) }.asReversed()
            }
            val renderItems = remember(reversed) {
                groupMessagesForRender(reversed)
            }

            // Scroll to a specific message when navigating from search results
            LaunchedEffect(scrollToMessageId, renderItems.size) {
                if (scrollToMessageId == null || hasScrolledToTarget || renderItems.isEmpty()) return@LaunchedEffect
                val targetIdx = renderItems.indexOfFirst { it.containsMessageId(scrollToMessageId) }
                if (targetIdx >= 0) {
                    // Calculate the actual LazyColumn item index accounting for
                    // the typing indicator and date separators before the target
                    var lazyIndex = if (state.isStreaming) 1 else 0
                    for (j in 0 until targetIdx) {
                        lazyIndex++ // message item
                        // check if a date separator was emitted after this item
                        val prevDate = renderItems.getOrNull(j + 1)?.boundaryTimestamp?.take(10)
                        val curDate = renderItems[j].boundaryTimestamp.take(10)
                        if (prevDate != null && prevDate != curDate) lazyIndex++
                    }
                    listState.scrollToItem(lazyIndex)
                    highlightedMessageId = scrollToMessageId
                    hasScrolledToTarget = true
                    delay(2000)
                    highlightedMessageId = null
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        // letta-mobile-5e0f.r2: continuous reflow pinch.
                        // We push every quantized fontScale change through
                        // to the theme as the user pinches — the chat
                        // tree truly re-flows at gesture rate. The earlier
                        // graphicsLayer band-aid is gone; smoothness is
                        // now achieved by:
                        //   1. ChatTypography is memoized per fontScale in
                        //      LettaChatTheme so identical values reuse
                        //      the same TextStyle instances.
                        //   2. LocalChatTypography / LocalChatFontScale
                        //      use compositionLocalOf (not static) so
                        //      Compose can structural-equality-skip
                        //      readers whose inputs didn't change.
                        //   3. LocalChatIsPinching=true suppresses the
                        //      four animateContentSize sites in the chat
                        //      tree so we don't get cascading 150ms
                        //      height interpolations across many bubbles
                        //      per pinch frame.
                        //
                        // Quantization: 2% steps so we recompose only on
                        // visible changes (~45 steps across 0.7..1.6),
                        // not on every pointer frame.
                        val step = 0.02f
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var gesturePinching = false
                            var rawScale = activeFontScale
                            var committedScale = activeFontScale
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val activePointers = event.changes.filter { it.pressed }
                                if (activePointers.size >= 2) {
                                    if (!gesturePinching) {
                                        gesturePinching = true
                                        isPinching = true
                                        pinchTick = System.nanoTime()
                                    }
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) {
                                        event.changes.forEach { it.consume() }
                                        rawScale = (rawScale * zoom).coerceIn(0.7f, 1.6f)
                                        val snapped = (kotlin.math.round(rawScale / step) * step)
                                            .coerceIn(0.7f, 1.6f)
                                        if (snapped != committedScale) {
                                            committedScale = snapped
                                            onActiveFontScaleChange(snapped)
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            if (gesturePinching) {
                                // Refresh indicator timer at gesture end
                                // so it hides 1s after the user lifts,
                                // not mid-pinch. Persist the committed
                                // scale to the ViewModel here (not on
                                // every step) to avoid repository churn.
                                pinchTick = System.nanoTime()
                                onFontScaleChange(committedScale)
                                isPinching = false
                            }
                        }
                    },
            ) {
                // letta-mobile-5e0f.r2: provide LocalChatIsPinching to
                // the entire chat content tree so animateContentSize
                // sites can suppress themselves during the gesture.
                CompositionLocalProvider(LocalChatIsPinching provides isPinching) {
                LazyColumn(
                    state = listState,
                    // letta-mobile-23h5 (polish 2026-04-19): wider side
                    // gutters so bubbles don't kiss the screen edge.
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    reverseLayout = true,
                ) {
                    if (state.isStreaming) {
                        item(key = "typing") {
                            TypingIndicator(modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }

                    renderItems.forEachIndexed { index, renderItem ->
                        val prevDate = renderItems.getOrNull(index + 1)?.boundaryTimestamp?.take(10)
                        val currentDate = renderItem.boundaryTimestamp.take(10)
                        val showDate = prevDate != null && prevDate != currentDate

                        item(key = renderItem.key) {
                            when (renderItem) {
                                is ChatRenderItem.Single -> {
                                    // letta-mobile-m772.4 follow-up: reasoning bubbles that
                                    // land as Single (because their run had only one message,
                                    // or because the message predates runId tracking) still
                                    // need the collapse affordance — otherwise the body is
                                    // always shown with no toggle. Thread the same callbacks
                                    // RunBlock uses so behaviour is consistent across modes
                                    // and group sizes.
                                    val msg = renderItem.message
                                    // letta-mobile-d2z6 (real fix): when w9l3 marked this
                                    // Single with a stableRunKey it means "this message's
                                    // run has exactly one message *right now* but a sibling
                                    // is likely about to land, promoting it to a RunBlock
                                    // with the same LazyColumn key". Routing both states
                                    // through the same composable (RunBlock) keeps the
                                    // slot's composable subtree identical across the
                                    // transition — Compose just sees the messages list
                                    // grow from 1 to N inside the existing RunBlock. The
                                    // RunBlock's messages.size == 1 short-circuit renders
                                    // exactly what RenderChatMessage would render, so
                                    // there's no visible change for true singletons.
                                    val stableKey = renderItem.stableRunKey
                                    if (stableKey != null) {
                                        val runId = stableKey.removePrefix("run-")
                                        RunBlock(
                                            messages = listOf(msg),
                                            collapsed = runId in state.collapsedRunIds,
                                            onToggleCollapsed = { onToggleRunCollapsed(runId) },
                                            modifier = Modifier.padding(top = 6.dp, bottom = 0.dp),
                                        ) { message, position, rowModifier ->
                                            RenderChatMessage(
                                                message = message,
                                                position = position,
                                                state = state,
                                                chatMode = chatMode,
                                                highlightedMessageId = highlightedMessageId,
                                                onSendMessage = onSendMessage,
                                                onSubmitApproval = onSubmitApproval,
                                                reasoningCollapsed = message.id !in state.expandedReasoningMessageIds,
                                                onToggleReasoning = { onToggleReasoningExpanded(message.id) },
                                                modifier = rowModifier,
                                            )
                                        }
                                    } else {
                                        RenderChatMessage(
                                            message = msg,
                                            position = renderItem.groupPosition,
                                            state = state,
                                            chatMode = chatMode,
                                            highlightedMessageId = highlightedMessageId,
                                            onSendMessage = onSendMessage,
                                            onSubmitApproval = onSubmitApproval,
                                            reasoningCollapsed = msg.id !in state.expandedReasoningMessageIds,
                                            onToggleReasoning = { onToggleReasoningExpanded(msg.id) },
                                        )
                                    }
                                }

                                is ChatRenderItem.RunBlock -> {
                                    val isHighlighted = renderItem.containsMessageId(highlightedMessageId.orEmpty())
                                    val highlightModifier = if (isHighlighted) {
                                        Modifier.background(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                            RoundedCornerShape(12.dp),
                                        )
                                    } else {
                                        Modifier
                                    }
                                    RunBlock(
                                        messages = renderItem.messages.map { it.first },
                                        collapsed = renderItem.runId in state.collapsedRunIds,
                                        onToggleCollapsed = { onToggleRunCollapsed(renderItem.runId) },
                                        modifier = highlightModifier.padding(top = 6.dp, bottom = 0.dp),
                                    ) { message, position, rowModifier ->
                                        RenderChatMessage(
                                            message = message,
                                            position = position,
                                            state = state,
                                            chatMode = chatMode,
                                            highlightedMessageId = highlightedMessageId,
                                            onSendMessage = onSendMessage,
                                            onSubmitApproval = onSubmitApproval,
                                            reasoningCollapsed = message.id !in state.expandedReasoningMessageIds,
                                            onToggleReasoning = { onToggleReasoningExpanded(message.id) },
                                            modifier = rowModifier,
                                        )
                                    }
                                }
                            }
                        }

                        if (showDate) {
                            // Tie the separator key to the boundary message id so
                            // the same date can legitimately appear multiple times
                            // (e.g. after older-page merges) without colliding.
                            item(key = "date-${renderItem.key}") {
                                val date = try {
                                    LocalDate.parse(currentDate)
                                } catch (_: Exception) {
                                    null
                                }
                                if (date != null) {
                                    DateSeparator(date = date)
                                }
                            }
                        }
                    }

                    if (state.isLoadingOlderMessages) {
                        item(key = "older-loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                }
                } // letta-mobile-5e0f.r2: end CompositionLocalProvider(LocalChatIsPinching)

                ScrollToBottomFab(
                    visible = showScrollFab,
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )

                if (showFontIndicator) {
                    // letta-mobile-5e0f.r2: activeFontScale is now updated
                    // continuously during the gesture (memoized typography
                    // makes that cheap), so the indicator reads it directly.
                    Text(
                        text = "${(activeFontScale * 100).toInt()}%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                                RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }

    }
}

@Composable
private fun RenderChatMessage(
    message: UiMessage,
    position: GroupPosition,
    state: ChatUiState,
    chatMode: String,
    highlightedMessageId: String?,
    onSendMessage: (String) -> Unit,
    onSubmitApproval: (String, List<String>, Boolean, String?) -> Unit,
    reasoningCollapsed: Boolean = false,
    onToggleReasoning: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // reverseLayout = true: top = space below (toward newer),
    // bottom = space above (toward older)
    val spacingBelow = when {
        position == GroupPosition.Middle || position == GroupPosition.Last -> 2.dp
        else -> 6.dp
    }
    val spacingAbove = if (message.isReasoning) 12.dp else 0.dp
    val isHighlighted = message.id == highlightedMessageId
    val highlightModifier = if (isHighlighted) {
        Modifier.background(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            RoundedCornerShape(12.dp),
        )
    } else {
        Modifier
    }
    if (chatMode == "debug") {
        DebugMessageCard(
            message = message,
            modifier = modifier.then(highlightModifier).padding(top = spacingBelow, bottom = spacingAbove),
        )
    } else {
        ChatMessageItem(
            message = message,
            groupPosition = position,
            isStreaming = state.isStreaming,
            reasoningCollapsed = reasoningCollapsed,
            onToggleReasoning = onToggleReasoning,
            onGeneratedUiMessage = onSendMessage,
            onApprovalDecision = { requestId, toolCallIds, approve, reason ->
                onSubmitApproval(requestId, toolCallIds, approve, reason)
            },
            approvalInFlight = state.activeApprovalRequestId == message.approvalRequest?.requestId,
            modifier = modifier.then(highlightModifier).padding(top = spacingBelow, bottom = spacingAbove),
        )
    }
}

@Composable
private fun DebugMessageCard(
    message: UiMessage,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "${message.role} | ${message.id}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("content: ${message.content.take(200)}")
                    if (message.content.length > 200) append("...")
                    if (message.isReasoning) append("\nisReasoning: true")
                    message.toolCalls?.forEach { tc ->
                        append("\ntool: ${tc.name}(${tc.arguments.take(100)})")
                        tc.result?.let { append("\nresult: ${it.take(100)}") }
                    }
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun NoConversationContent(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.screen_chat_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.screen_chat_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
