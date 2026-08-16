package com.letta.mobile.web

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.web.data.WebChatEntry
import com.letta.mobile.data.model.MessageContentPart

private val WebChatColumnMaxWidth = 760.dp

@Composable
internal fun WebChatMessages(
    messages: List<WebChatEntry>,
    selectedAgent: AgentItemState,
    isSending: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty() && !isSending) {
        WebConversationWelcome(selectedAgent, modifier)
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text, isSending) {
        val lastIndex = messages.size + if (isSending) 1 else 0
        if (lastIndex > 0) listState.scrollToItem(lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = if (compact) 16.dp else 28.dp,
            vertical = 24.dp,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "today") {
            Text(
                "Today",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().widthIn(max = WebChatColumnMaxWidth),
            )
        }
        items(messages, key = WebChatEntry::id) { message ->
            Box(Modifier.fillMaxWidth().widthIn(max = WebChatColumnMaxWidth)) {
                if (message.isUser) WebUserPrompt(message) else WebAssistantMessage(message)
            }
        }
        if (isSending) {
            item(key = "thinking") {
                Row(
                    modifier = Modifier.fillMaxWidth().widthIn(max = WebChatColumnMaxWidth),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WebAgentSphere(28.dp)
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${selectedAgent.name} is working",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WebUserPrompt(message: WebChatEntry) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AttachmentSummary(message.attachments)
            if (message.text.isNotBlank()) {
                SelectionContainer {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun WebAssistantMessage(message: WebChatEntry) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        WebAgentSphere(28.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AttachmentSummary(message.attachments)
            if (message.text.isNotBlank()) {
                SelectionContainer {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentSummary(attachments: List<MessageContentPart.Image>) {
    if (attachments.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                if (attachments.size == 1) "1 image" else "${attachments.size} images",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun WebConversationWelcome(agent: AgentItemState, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WebAgentSphere(64.dp)
            Text("Start a conversation with ${agent.name}", style = MaterialTheme.typography.headlineSmall)
            Text(
                agent.description ?: "Send a message to begin working together.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
