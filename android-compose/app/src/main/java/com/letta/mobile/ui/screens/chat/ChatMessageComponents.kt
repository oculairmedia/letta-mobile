package com.letta.mobile.ui.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
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
import com.letta.mobile.ui.components.MessageBubbleShape
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.ThinkingSection
import com.letta.mobile.ui.theme.chatColors
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatTypography

private fun UiMessage.displayRoleLabel(defaultLabel: String): String {
    val toolCall = toolCalls?.singleOrNull()
    if (toolCall == null) {
        return if (role == "tool") {
            if (content.isNotBlank()) {
                "Tool output"
            } else {
                "Tool activity"
            }
        } else {
            defaultLabel
        }
    }
    return toolCall.name
}

@OptIn(ExperimentalFoundationApi::class)
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
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.action_copy)
    val copyText = remember(message) { buildMessageCopyText(message) }
    val onLongClick: (() -> Unit)? = if (copyText.isNotBlank()) {
        { copyToClipboard(context, copyLabel, copyText) }
    } else null

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
                onLongClick = onLongClick,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleSurface(
    message: UiMessage,
    groupPosition: GroupPosition,
    isStreaming: Boolean,
    onLongClick: (() -> Unit)? = null,
) {
    val isUser = message.role == "user"
    val isLastAssistant = isStreaming && message.role == "assistant"
    val style = bubbleStyle(role = message.role, isStreaming = isLastAssistant)
    val colors = MaterialTheme.chatColors
    val dimens = MaterialTheme.chatDimens
    val typo = MaterialTheme.chatTypography
    val renderer = remember(message.role, message.toolCalls) { resolveRenderer(message) }
    val bubbleShape = MessageBubbleShape(radius = 12.dp, isFromUser = isUser, groupPosition = groupPosition)

    Surface(
        shape = bubbleShape,
        color = style.containerColor,
        border = BorderStroke(dimens.bubbleBorderWidth, style.borderColor),
        tonalElevation = 0.dp,
        modifier = if (onLongClick != null) {
            Modifier
                .clip(bubbleShape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick,
                )
        } else Modifier,
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
                    text = message.displayRoleLabel(style.roleLabel),
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
    var resultExpanded by remember { mutableStateOf(false) }
    val display = remember(toolCall.name, toolCall.arguments) {
        ToolDisplayRegistry.resolve(toolCall.name, toolCall.arguments)
    }
    val isError = toolCall.status != null && toolCall.status != "success"

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header: emoji + tool name + status icon
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(display.emoji, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = toolCall.name,
                    style = MaterialTheme.chatTypography.toolLabel,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isError) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Error",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                } else if (toolCall.result != null) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Friendly description
            Text(
                text = display.label,
                style = MaterialTheme.chatTypography.toolDetail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Detail line (extracted from arguments)
            display.detailLine?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.chatTypography.codeBlock,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Arguments accordion
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
            // Result accordion
            toolCall.result?.let { result ->
                val preview = if (result.length > 120) result.take(120) + "…" else result
                Accordions(
                    title = if (isError) "Error" else "Result",
                    expanded = resultExpanded,
                    onExpandedChange = { resultExpanded = it },
                ) {
                    MarkdownText(
                        text = result,
                        textColor = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (!resultExpanded) {
                    Text(
                        text = preview,
                        style = MaterialTheme.chatTypography.codeBlock,
                        color = if (isError) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.action_copied), Toast.LENGTH_SHORT).show()
    }
}
