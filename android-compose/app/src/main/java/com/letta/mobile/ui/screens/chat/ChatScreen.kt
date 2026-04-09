package com.letta.mobile.ui.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import androidx.compose.material3.FilterChip
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.common.groupMessages
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.components.Accordions
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.MessageSkeletonList
import com.letta.mobile.ui.components.StarterPrompts
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.chatColors
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatTypography
import com.letta.mobile.ui.components.MessageBubbleShape
import com.letta.mobile.ui.components.ScrollToBottomFab
import com.letta.mobile.ui.components.ThinkingSection
import com.letta.mobile.ui.components.TypingIndicator
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()

    LettaChatTheme {
    Column(modifier = modifier.fillMaxSize()) {
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
                onSendMessage = { viewModel.sendMessage(it) },
                onInputTextChange = { viewModel.updateInputText(it) },
                modifier = Modifier.weight(1f),
            )
        }

        MessageInputBar(
            text = inputText,
            onTextChange = { viewModel.updateInputText(it) },
            onSend = { viewModel.sendMessage(it) },
            isStreaming = state.isStreaming,
        )
    }
    }
}

@Composable
private fun ChatContent(
    state: ChatUiState,
    onSendMessage: (String) -> Unit,
    onInputTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var chatMode by remember { mutableStateOf("interactive") }

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

    LaunchedEffect(Unit) {
        snapshotFlow { messageCount }
            .distinctUntilChanged()
            .collect {
                if (it > 0 && isAtBottom) {
                    listState.scrollToItem(0)
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
            Box(modifier = Modifier.weight(1f)) {
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
                            val spacing = when (position) {
                                GroupPosition.Middle, GroupPosition.Last -> 2.dp
                                else -> 6.dp
                            }
                            if (chatMode == "debug") {
                                DebugMessageCard(
                                    message = message,
                                    modifier = Modifier.padding(top = spacing),
                                )
                            } else {
                                MessageBubble(
                                    message = message,
                                    groupPosition = position,
                                    isStreaming = state.isStreaming,
                                    modifier = Modifier.padding(top = spacing),
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
                }

                ScrollToBottomFab(
                    visible = showScrollFab,
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
            }
        }

    }
}

@Composable
private fun MessageBubble(
    message: UiMessage,
    groupPosition: GroupPosition = GroupPosition.None,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (message.isReasoning) {
        ThinkingSection(
            thinkingText = message.content,
            inProgress = isStreaming,
            modifier = modifier
        )
        return
    }

    val isUser = message.role == "user"
    val isLastAssistant = isStreaming && message.role == "assistant"
    val style = bubbleStyle(role = message.role, isStreaming = isLastAssistant)
    val colors = MaterialTheme.chatColors
    val dimens = MaterialTheme.chatDimens
    val typo = MaterialTheme.chatTypography
    val renderer = remember(message.role, message.toolCalls) { resolveRenderer(message) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (style.alignEnd) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = MessageBubbleShape(radius = 12.dp, isFromUser = isUser, groupPosition = groupPosition),
            color = style.containerColor,
            border = BorderStroke(dimens.bubbleBorderWidth, style.borderColor),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(dimens.bubbleMaxWidthFraction),
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = dimens.bubblePaddingHorizontal,
                    vertical = dimens.bubblePaddingVertical,
                ),
                verticalArrangement = Arrangement.spacedBy(dimens.messageSpacing),
            ) {
                if (groupPosition == GroupPosition.First || groupPosition == GroupPosition.None) {
                    Text(
                        text = style.roleLabel,
                        style = typo.roleLabel,
                        color = style.roleColor,
                    )
                }

                val textColor = if (isUser) colors.userText else colors.agentText
                renderer.Render(message = message, textColor = textColor, modifier = Modifier)
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
private fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    Surface(
        modifier = modifier.imePadding(),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { newText ->
                    if (newText.endsWith("\n") && !isStreaming && text.isNotBlank()) {
                        onSend(text)
                        keyboardController?.hide()
                    } else {
                        onTextChange(newText)
                    }
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.screen_chat_input_hint)) },
                enabled = !isStreaming,
                maxLines = 4,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        if (text.isNotBlank() && !isStreaming) {
                            onSend(text)
                            keyboardController?.hide()
                        }
                    },
                ),
            )

            Spacer(modifier = Modifier.width(8.dp))

            FilledIconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        keyboardController?.hide()
                    }
                },
                enabled = text.isNotBlank() && !isStreaming
            ) {
                Icon(Icons.Default.Send, contentDescription = stringResource(R.string.action_send_message))
            }
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
            imageVector = Icons.Default.Error,
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
