package com.letta.mobile.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.components.Accordions
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.theme.chatTypography

interface MessageContentRenderer {
    fun canRender(message: UiMessage): Boolean

    @Composable
    fun Render(message: UiMessage, textColor: Color, modifier: Modifier)
}

object TextMessageRenderer : MessageContentRenderer {
    override fun canRender(message: UiMessage) =
        message.role == "user" || (message.role == "assistant" && message.toolCalls.isNullOrEmpty())

    @Composable
    override fun Render(message: UiMessage, textColor: Color, modifier: Modifier) {
        if (message.role == "user") {
            Text(
                text = message.content,
                style = MaterialTheme.chatTypography.messageBody,
                color = textColor,
                modifier = modifier,
            )
        } else {
            MarkdownText(
                text = message.content,
                textColor = textColor,
                modifier = modifier,
            )
        }
    }
}

object ToolCallRenderer : MessageContentRenderer {
    override fun canRender(message: UiMessage) =
        !message.toolCalls.isNullOrEmpty()

    @Composable
    override fun Render(message: UiMessage, textColor: Color, modifier: Modifier) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (message.content.isNotBlank()) {
                MarkdownText(text = message.content, textColor = textColor)
            }
            message.toolCalls?.forEach { toolCall ->
                ToolCallContent(toolCall = toolCall)
            }
        }
    }
}

@Composable
private fun ToolCallContent(toolCall: UiToolCall) {
    var argsExpanded by remember { mutableStateOf(false) }
    val display = remember(toolCall.name) { ToolDisplayRegistry.resolve(toolCall.name, toolCall.arguments) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(display.emoji, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = display.label,
                    style = MaterialTheme.chatTypography.toolLabel,
                    modifier = Modifier.weight(1f),
                )
                if (toolCall.result != null) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            display.detailLine?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.chatTypography.toolDetail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (toolCall.arguments.isNotBlank()) {
                Accordions(
                    title = "Arguments",
                    expanded = argsExpanded,
                    onExpandedChange = { argsExpanded = it },
                ) {
                    Text(
                        text = toolCall.arguments,
                        style = MaterialTheme.chatTypography.codeBlock,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            toolCall.result?.let { result ->
                Spacer(modifier = Modifier.height(4.dp))
                MarkdownText(
                    text = result,
                    textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

val defaultRenderers = listOf(ToolCallRenderer, TextMessageRenderer)

fun resolveRenderer(message: UiMessage): MessageContentRenderer {
    return defaultRenderers.firstOrNull { it.canRender(message) } ?: TextMessageRenderer
}
