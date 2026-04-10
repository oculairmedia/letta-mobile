package com.letta.mobile.ui.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.components.Accordions
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.MessageActionButton
import com.letta.mobile.ui.components.MessageBubbleShape
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.ThinkingSection
import com.letta.mobile.ui.theme.chatColors
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatTypography

@Composable
internal fun ChatMessageItem(
    message: UiMessage,
    groupPosition: GroupPosition,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == "user"
    val showAvatar = groupPosition == GroupPosition.First || groupPosition == GroupPosition.None
    val avatarSpacer = Modifier.width(32.dp)

    if (message.isReasoning) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            if (showAvatar) {
                MessageAvatar(role = "assistant")
            } else {
                Spacer(modifier = avatarSpacer)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                MessageReasoning(
                    message = message,
                    isStreaming = isStreaming,
                )
                MessageActions(
                    message = message,
                    alignEnd = false,
                )
            }
        }
        return
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isUser) {
            if (showAvatar) {
                MessageAvatar(role = message.role)
            } else {
                Spacer(modifier = avatarSpacer)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.fillMaxWidth(MaterialTheme.chatDimens.bubbleMaxWidthFraction),
        ) {
            MessageBubbleSurface(
                message = message,
                groupPosition = groupPosition,
                isStreaming = isStreaming,
            )
            MessageActions(
                message = message,
                alignEnd = isUser,
            )
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            if (showAvatar) {
                MessageAvatar(role = message.role)
            } else {
                Spacer(modifier = avatarSpacer)
            }
        }
    }
}

@Composable
private fun MessageBubbleSurface(
    message: UiMessage,
    groupPosition: GroupPosition,
    isStreaming: Boolean,
) {
    val isUser = message.role == "user"
    val isLastAssistant = isStreaming && message.role == "assistant"
    val style = bubbleStyle(role = message.role, isStreaming = isLastAssistant)
    val colors = MaterialTheme.chatColors
    val dimens = MaterialTheme.chatDimens
    val typo = MaterialTheme.chatTypography
    val renderer = remember(message.role, message.toolCalls) { resolveRenderer(message) }

    Surface(
        shape = MessageBubbleShape(radius = 12.dp, isFromUser = isUser, groupPosition = groupPosition),
        color = style.containerColor,
        border = BorderStroke(dimens.bubbleBorderWidth, style.borderColor),
        tonalElevation = 0.dp,
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

@Composable
internal fun MessageAvatar(
    role: String,
    modifier: Modifier = Modifier,
) {
    val isUser = role == "user"
    val icon = when (role) {
        "tool" -> Icons.Default.Build
        "assistant" -> Icons.Default.SmartToy
        else -> null
    }
    val containerColor = if (isUser) {
        MaterialTheme.chatColors.userBubble
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        MaterialTheme.chatColors.userText
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier.size(32.dp),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = "Y",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )
            }
        }
    }
}

@Composable
internal fun MessageActions(
    message: UiMessage,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.action_copy)
    val copyText = remember(message) { buildMessageCopyText(message) }
    var showSheet by remember(message.id) { mutableStateOf(false) }

    if (copyText.isBlank()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MessageActionButton(
            label = copyLabel,
            icon = Icons.Default.ContentCopy,
            onClick = { copyToClipboard(context, copyLabel, copyText) },
        )
        IconButton(
            onClick = { showSheet = true },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    ActionSheet(
        show = showSheet,
        onDismiss = { showSheet = false },
        title = message.role.replaceFirstChar { it.uppercase() },
    ) {
        ActionSheetItem(
            text = copyLabel,
            icon = Icons.Default.ContentCopy,
            onClick = {
                showSheet = false
                copyToClipboard(context, copyLabel, copyText)
            },
        )
    }
}

@Composable
internal fun MessageReasoning(
    message: UiMessage,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    ThinkingSection(
        thinkingText = message.content,
        inProgress = isStreaming,
        modifier = modifier,
    )
}

@Composable
internal fun MessageToolCalls(
    toolCalls: List<UiToolCall>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        toolCalls.forEach { toolCall ->
            ToolCallCard(toolCall = toolCall)
        }
    }
}

@Composable
private fun ToolCallCard(toolCall: UiToolCall) {
    var argsExpanded by remember { mutableStateOf(false) }
    val display = remember(toolCall.name) { ToolDisplayRegistry.resolve(toolCall.name, toolCall.arguments) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
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
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
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
                Spacer(modifier = Modifier.size(4.dp))
                MarkdownText(
                    text = result,
                    textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private fun buildMessageCopyText(message: UiMessage): String {
    return buildString {
        if (message.content.isNotBlank()) {
            append(message.content)
        }
        message.toolCalls.orEmpty().forEach { toolCall ->
            if (isNotEmpty()) append("\n\n")
            append("Tool: ")
            append(toolCall.name)
            if (toolCall.arguments.isNotBlank()) {
                append("\nArguments:\n")
                append(toolCall.arguments)
            }
            toolCall.result?.takeIf { it.isNotBlank() }?.let { result ->
                append("\nResult:\n")
                append(result)
            }
        }
    }
}

private fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
