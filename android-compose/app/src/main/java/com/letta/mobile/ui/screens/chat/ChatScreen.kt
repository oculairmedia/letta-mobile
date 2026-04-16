package com.letta.mobile.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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

    // Refresh messages when returning to this screen (catches responses received while away)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.loadMessages()
    }

    val backgroundModifier = when (chatBackground) {
        is ChatBackground.Default -> Modifier
        is ChatBackground.SolidColor -> Modifier.background(chatBackground.color)
        is ChatBackground.Gradient -> Modifier.background(chatBackground.toBrush())
    }

    LettaChatTheme(fontScale = activeFontScale) {
    Column(modifier = modifier.fillMaxSize().then(backgroundModifier).imePadding()) {
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
                        activeFontScale = activeFontScale,
                        onActiveFontScaleChange = { activeFontScale = it },
                        onFontScaleChange = { viewModel.setChatFontScale(it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        LettaInputBar(
            text = inputText,
            onTextChange = { newText ->
                if (newText.endsWith("\n") && !state.isStreaming && inputText.isNotBlank()) {
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
            placeholder = stringResource(R.string.screen_chat_input_hint),
            sendContentDescription = stringResource(R.string.action_send_message),
            enabled = !state.isStreaming && viewModel.canSendMessages,
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
            "simple" -> result.filter { it.role == "user" || (it.role == "assistant" && !it.isReasoning) }
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
            val reversed = remember(groupedMessages) { groupedMessages.asReversed() }

            // Scroll to a specific message when navigating from search results
            LaunchedEffect(scrollToMessageId, reversed.size) {
                if (scrollToMessageId == null || hasScrolledToTarget || reversed.isEmpty()) return@LaunchedEffect
                val targetIdx = reversed.indexOfFirst { (msg, _) -> msg.id == scrollToMessageId }
                if (targetIdx >= 0) {
                    // Calculate the actual LazyColumn item index accounting for
                    // the typing indicator and date separators before the target
                    var lazyIndex = if (state.isStreaming) 1 else 0
                    for (j in 0 until targetIdx) {
                        lazyIndex++ // message item
                        // check if a date separator was emitted after this item
                        val prevDate = reversed.getOrNull(j + 1)?.first?.timestamp?.take(10)
                        val curDate = reversed[j].first.timestamp.take(10)
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
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var isPinching = false
                            var currentScale = activeFontScale
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val activePointers = event.changes.filter { it.pressed }
                                if (activePointers.size >= 2) {
                                    isPinching = true
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) {
                                        event.changes.forEach { it.consume() }
                                        currentScale = (currentScale * zoom).coerceIn(0.7f, 1.6f)
                                        onActiveFontScaleChange(currentScale)
                                        pinchTick = System.nanoTime()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            if (isPinching) {
                                onFontScaleChange(currentScale)
                            }
                        }
                    },
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    reverseLayout = true
                ) {
                    if (state.isStreaming) {
                        item(key = "typing") {
                            TypingIndicator(modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }

                    reversed.forEachIndexed { index, (message, position) ->
                        val prevDate = reversed.getOrNull(index + 1)?.first?.timestamp?.take(10)
                        val currentDate = message.timestamp.take(10)
                        val showDate = prevDate != null && prevDate != currentDate

                        item(key = "${message.id}-$index") {
                            // reverseLayout = true: top = space below (toward newer),
                            // bottom = space above (toward older)
                            val spacingBelow = when {
                                position == GroupPosition.Middle || position == GroupPosition.Last -> 2.dp
                                else -> 6.dp
                            }
                            val spacingAbove = if (message.isReasoning) 12.dp else 0.dp
                            val isHighlighted = message.id == highlightedMessageId
                            val highlightModifier = if (isHighlighted) {
                                Modifier
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                        RoundedCornerShape(12.dp),
                                    )
                            } else {
                                Modifier
                            }
                            if (chatMode == "debug") {
                                DebugMessageCard(
                                    message = message,
                                    modifier = highlightModifier.padding(top = spacingBelow, bottom = spacingAbove),
                                )
                            } else {
                                ChatMessageItem(
                                    message = message,
                                    groupPosition = position,
                                    isStreaming = state.isStreaming,
                                    onGeneratedUiMessage = onSendMessage,
                                    onApprovalDecision = { requestId, toolCallIds, approve, reason ->
                                        onSubmitApproval(requestId, toolCallIds, approve, reason)
                                    },
                                    approvalInFlight = state.activeApprovalRequestId == message.approvalRequest?.requestId,
                                    modifier = highlightModifier.padding(top = spacingBelow, bottom = spacingAbove),
                                )
                            }
                        }

                        if (showDate) {
                            item(key = "date-$currentDate") {
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

                ScrollToBottomFab(
                    visible = showScrollFab,
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )

                if (showFontIndicator) {
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
