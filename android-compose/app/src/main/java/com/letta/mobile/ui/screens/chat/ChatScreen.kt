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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.MessageBubbleShape
import com.letta.mobile.ui.components.ThinkingSection
import com.letta.mobile.ui.theme.customColors

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
    val density = LocalDensity.current

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.messages.isEmpty()) {
            EmptyState(
                icon = Icons.Default.ChatBubbleOutline,
                message = "Start a conversation",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                reverseLayout = true
            ) {
                items(
                    items = state.messages.reversed(),
                    key = { it.id }
                ) { message ->
                    MessageBubble(message = message, isStreaming = state.isStreaming)
                }
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
    message: Message,
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
                .clip(MessageBubbleShape(radius = 16.dp, isFromUser = isUser))
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

                if (!message.toolCalls.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    message.toolCalls.forEach { toolCall ->
                        ToolCallCard(toolCall = toolCall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(
    toolCall: ToolCall,
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

            if (toolCall.result != null) {
                Spacer(modifier = Modifier.height(4.dp))
                MarkdownText(
                    text = toolCall.result,
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
                placeholder = { Text("Type a message\u2026") },
                enabled = !isStreaming,
                maxLines = 4
            )

            Spacer(modifier = Modifier.width(8.dp))

            FilledIconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                    }
                },
                enabled = text.isNotBlank() && !isStreaming
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send message")
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
            Text("Retry")
        }
    }
}
