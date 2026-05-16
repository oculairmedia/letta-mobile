package com.letta.mobile.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.feature.chat.R
import com.letta.mobile.data.model.ToolReturnStatus
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.tooloutput.ToolOutputParser
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.components.MessageBubbleShape
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.LatencyText
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.TextInputDialog
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.chatBubbleSender
import com.letta.mobile.ui.theme.chatColors
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatTypography
import com.letta.mobile.ui.theme.dialogSectionHeading
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.theme.scaledBy
import com.letta.mobile.ui.theme.sectionTitle
import java.util.LinkedHashMap
import kotlinx.collections.immutable.toImmutableList

internal fun UiMessage.displayRoleLabel(defaultLabel: String): String {
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
    reasoningCollapsed: Boolean = true,
    onToggleReasoning: (() -> Unit)? = null,
    onGeneratedUiMessage: ((String) -> Unit)? = null,
    onRerunMessage: ((UiMessage) -> Unit)? = null,
    rerunEnabled: Boolean = true,
    onApprovalDecision: ((String, List<String>, Boolean, String?) -> Unit)? = null,
    approvalInFlight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == "user"
    val showAvatar = false
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.action_copy)
    val copyText = remember(message) { buildMessageCopyText(message) }
    var showMessageActions by remember { mutableStateOf(false) }
    val hasUserActions = isUser && onRerunMessage != null
    val onLongClick: (() -> Unit)? = when {
        hasUserActions -> { { showMessageActions = true } }
        copyText.isNotBlank() -> { { copyToClipboard(context, copyLabel, copyText) } }
        else -> null
    }

    ActionSheet(
        show = showMessageActions,
        onDismiss = { showMessageActions = false },
        title = "Message actions",
    ) {
        if (hasUserActions && rerunEnabled) {
            ActionSheetItem(
                text = "Run again",
                icon = LettaIcons.Refresh,
                onClick = {
                    showMessageActions = false
                    onRerunMessage(message)
                },
            )
        }
        if (copyText.isNotBlank()) {
            ActionSheetItem(
                text = copyLabel,
                icon = LettaIcons.Copy,
                onClick = {
                    showMessageActions = false
                    copyToClipboard(context, copyLabel, copyText)
                },
            )
        }
    }

    // New layout: avatar floats ABOVE the bubble rather than occupying a
    // 40dp-wide gutter next to it. Assistant/tool/reasoning bubbles can then
    // stretch to the full content-area width — a noticeable win on phones
    // where the old gutter consumed ~10% of horizontal space per message.
    // User bubbles stay right-aligned and sized-to-content; their avatar
    // aligns to the right over the bubble.
    val avatarAlignment = if (isUser) Alignment.End else Alignment.Start

    if (message.isReasoning) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            if (showAvatar) {
                MessageAvatar(role = "assistant")
                Spacer(modifier = Modifier.height(4.dp))
            }
            MessageReasoning(
                message = message,
                isStreaming = isStreaming,
                collapsed = reasoningCollapsed,
                onToggleCollapsed = onToggleReasoning,
            )
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = avatarAlignment,
    ) {
        if (showAvatar) {
            MessageAvatar(role = message.role)
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Assistant / tool bubbles take the full content-area width so code
        // blocks, markdown tables, and long messages can breathe. User
        // bubbles are capped by bubbleMaxWidthFraction and sized-to-content,
        // keeping them visually distinct as right-aligned cards.
        val bubbleModifier = if (isUser) {
            Modifier.fillMaxWidth(MaterialTheme.chatDimens.bubbleMaxWidthFraction)
        } else {
            Modifier.fillMaxWidth()
        }
        Column(
            horizontalAlignment = avatarAlignment,
            modifier = bubbleModifier,
        ) {
            MessageBubbleSurface(
                message = message,
                groupPosition = groupPosition,
                isStreaming = isStreaming,
                onGeneratedUiMessage = onGeneratedUiMessage,
                onApprovalDecision = onApprovalDecision,
                approvalInFlight = approvalInFlight,
                onLongClick = onLongClick,
            )
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
        "tool" -> LettaIcons.Tool
        "assistant" -> LettaIcons.Agent
        else -> null
    }

    // Smaller, lighter-weight avatar — 24dp instead of 32dp, with a thin
    // outline for the assistant (matches the leading AI-app convention of a
    // ringed sparkle/icon floating above the response) and a filled pill for
    // the user / tool roles (preserves the current visual weight for those).
    val avatarSize = 24.dp

    if (isUser || icon == null) {
        // Filled pill for user (and any unknown roles): preserves the current
        // "Y" badge / role-letter look.
        val containerColor = MaterialTheme.chatColors.userBubble
        val contentColor = MaterialTheme.chatColors.userText
        Surface(
            modifier = modifier.size(avatarSize),
            shape = MaterialTheme.shapes.small,
            color = containerColor,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(LettaIconSizing.Inline),
                    )
                } else {
                    Text(
                        text = "Y",
                        style = MaterialTheme.typography.chatBubbleSender,
                        color = contentColor,
                    )
                }
            }
        }
    } else {
        // Ringed icon for assistant / tool — no fill, subtle outline.
        val ringColor = MaterialTheme.colorScheme.outlineVariant
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            modifier = modifier
                .size(avatarSize)
                .clip(MaterialTheme.shapes.small)
                .background(Color.Transparent)
                .border(
                    width = 1.dp,
                    color = ringColor,
                    shape = MaterialTheme.shapes.small,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

internal fun buildMessageCopyText(message: UiMessage): String {
    return buildString {
        if (message.content.isNotBlank()) {
            append(message.content)
        }
        message.toolCalls.orEmpty().forEach { toolCall ->
            if (isNotEmpty()) append("\n\n")
            append("Tool: ")
            append(toolCall.name)
            toolCall.executionTimeMs?.let { durationMs ->
                append("\nExecution time: ")
                append(formatToolExecutionTime(durationMs))
            }
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
