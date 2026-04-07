package com.letta.mobile.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.common.groupMessages
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.MessageBubbleShape
import com.letta.mobile.ui.components.ScrollToBottomFab
import com.letta.mobile.ui.components.ThinkingSection
import com.letta.mobile.ui.components.TypingIndicator
import com.letta.mobile.ui.theme.customColors
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is UiState.Loading -> LoadingIndicator()
        is UiState.Error -> ErrorContent(
            message = state.message,
            onRetry = { viewModel.loadMessages() },
            modifier = modifier
        )
        is UiState.Success -> ChatContent(
            state = state.data,
            onSendMessage = { viewModel.sendMessage(it) },
            onInputTextChange = { viewModel.updateInputText(it) },
            modifier = modifier
        )
    }
}

@Composable
private fun ChatContent(
    state: ChatUiState,
    onSendMessage: (String) -> Unit,
    onInputTextChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val messageCount by rememberUpdatedState(state.messages.size)
    val isStreaming by rememberUpdatedState(state.isStreaming)

    LaunchedEffect(Unit) {
        snapshotFlow { messageCount }
            .distinctUntilChanged()
            .collect {
                if (it > 0) listState.animateScrollToItem(0)
            }
    }

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
        snapshotFlow { isStreaming }
            .distinctUntilChanged()
            .collect { streaming ->
                if (streaming && isAtBottom) {
                    listState.animateScrollToItem(0)
                }
            }
    }

    val dedupedMessages = remember(state.messages) {
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
        result
    }

    val groupedMessages = remember(dedupedMessages) {
        groupMessages(
            messages = dedupedMessages,
            getRole = { it.role },
            getTimestamp = { it.timestamp },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.messages.isEmpty() && !state.isStreaming) {
            EmptyState(
                icon = Icons.Default.ChatBubbleOutline,
                message = stringResource(R.string.screen_chat_empty),
                modifier = Modifier.weight(1f)
            )
        } else {
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

                    val reversed = groupedMessages.reversed()
                    reversed.forEachIndexed { index, (message, position) ->
                        val prevDate = reversed.getOrNull(index + 1)?.first?.timestamp?.take(10)
                        val currentDate = message.timestamp.take(10)
                        val showDate = prevDate != null && prevDate != currentDate

                        item(key = "${message.id}-$index") {
                            val spacing = when (position) {
                                GroupPosition.Middle, GroupPosition.Last -> 2.dp
                                else -> 6.dp
                            }
                            MessageBubble(
                                message = message,
                                groupPosition = position,
                                isStreaming = state.isStreaming,
                                modifier = Modifier.padding(top = spacing)
                            )
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

        MessageInputBar(
            text = state.inputText,
            onTextChange = onInputTextChange,
            onSend = onSendMessage,
            isStreaming = state.isStreaming
        )
    }
}

@Composable
private fun MessageBubble(
    message: UiMessage,
    groupPosition: GroupPosition = GroupPosition.None,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    val customColors = MaterialTheme.customColors

    if (message.isReasoning) {
        ThinkingSection(
            thinkingText = message.content,
            inProgress = isStreaming,
            modifier = modifier
        )
        return
    }

    val bubbleColor = when {
        isUser -> customColors.userBubbleBgColor
        message.role == "tool" -> customColors.toolBubbleBgColor
        else -> customColors.agentBubbleBgColor
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(
            start = if (isUser) 48.dp else 0.dp,
            end = if (isUser) 0.dp else 48.dp
        ),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(MessageBubbleShape(radius = 16.dp, isFromUser = isUser, groupPosition = groupPosition))
                .background(bubbleColor)
                .padding(12.dp)
        ) {
            Column {
                if (isUser) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                } else {
                    MarkdownText(
                        text = message.content,
                        textColor = MaterialTheme.colorScheme.onSurface
                    )
                }

                message.toolCalls?.takeIf { it.isNotEmpty() }?.let { toolCalls ->
                    Spacer(modifier = Modifier.height(8.dp))
                    toolCalls.forEach { toolCall ->
                        ToolCallCard(toolCall = toolCall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(
    toolCall: UiToolCall,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = "Tool call",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = toolCall.name,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            toolCall.result?.let { result ->
                Spacer(modifier = Modifier.height(4.dp))
                MarkdownText(
                    text = result,
                    textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
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
        modifier = modifier,
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
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.screen_chat_input_hint)) },
                enabled = !isStreaming,
                maxLines = 6
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
