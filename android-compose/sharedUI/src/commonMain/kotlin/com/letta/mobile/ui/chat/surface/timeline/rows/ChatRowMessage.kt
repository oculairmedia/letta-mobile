package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_copy_response
import com.letta.mobile.ui.chat.provenance.AgentMessageProvenanceLabel
import com.letta.mobile.ui.chat.render.rememberSmoothedStreamingText
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: one stand-alone message (desktop's DesktopMessageBubble): the user
 * prompt card, or an assistant column of reasoning / text / tools / generated UI / approvals.
 */
@Composable
internal fun ChatMessageRow(
    message: UiMessage,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    if (isUserRole(message.role) && message.subagentNotification == null) {
        UserPromptRow(message, context, callbacks)
    } else {
        AssistantMessageColumn(message, context, callbacks)
    }
}

@Composable
private fun AssistantMessageColumn(
    message: UiMessage,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        message.agentMessageProvenance?.let { ProvenanceLabel(message, callbacks) }
        AssistantPrimaryContent(message, context, callbacks)
        MessageImages(message, callbacks)
        message.toolCalls.orEmpty().forEachIndexed { index, toolCall ->
            ToolCard(
                toolCall = toolCall,
                disclosureKey = toolCall.toolCallId?.takeIf { it.isNotBlank() } ?: "${message.id}:$index",
                callbacks = callbacks,
            )
        }
        message.generatedUi?.let { GeneratedUiCard(it, context, callbacks) }
        message.approvalRequest?.let { ApprovalRequestCard(it, context, callbacks) }
        message.approvalResponse?.let { ApprovalResponseCard(it) }
    }
}

@Composable
private fun AssistantPrimaryContent(
    message: UiMessage,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    val notification = message.subagentNotification
    when {
        notification != null -> SubagentNotificationCard(notification, callbacks.openSubagent)
        message.content.isBlank() -> Unit
        message.isReasoning -> ReasoningRow(message, context, callbacks)
        else -> AgentText(AgentTextParams(message.content, message.isError, context.isStreaming(message)))
    }
}

@Composable
internal fun MessageImages(message: UiMessage, callbacks: ChatRowCallbacks) {
    if (message.attachments.isEmpty()) return
    val images = remember(message.attachments) { message.attachments.toImmutableList() }
    ChatImageAttachmentsGrid(tap = ImageTap(images, callbacks.onImageTap), modifier = Modifier.fillMaxWidth())
}

/** Inter-agent provenance above a message: sender -> recipient, expandable to metadata. */
@Composable
internal fun ProvenanceLabel(message: UiMessage, callbacks: ChatRowCallbacks) {
    val provenance = message.agentMessageProvenance ?: return
    var expanded by remember(message.id) { mutableStateOf(false) }
    AgentMessageProvenanceLabel(
        provenance = provenance,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded },
        resolveName = callbacks.resolveAgentName,
        onAgentClick = callbacks.host.openAgent,
    )
}

/** Plain agent narration, full width with no bubble. */
@Immutable
internal data class AgentTextParams(
    val text: String,
    val isError: Boolean,
    val isStreaming: Boolean = false,
)

/**
 * While the reply is landing, the shared smoother reveals it progressively instead of
 * snapping each raw chunk in; settled history renders the full text. The markdown is not
 * retained across updates, so a stream reconciliation that SHORTENS the text shrinks it.
 */
@Composable
internal fun AgentText(params: AgentTextParams) {
    val displayText = if (params.isStreaming) {
        rememberSmoothedStreamingText(rawText = params.text, isStreaming = true)
    } else {
        params.text
    }
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    Box(modifier = Modifier.fillMaxWidth().hoverable(hoverSource).testTag(ChatRowTestTags.AGENT_TEXT)) {
        SelectionContainer {
            SharedMarkdownText(
                text = displayText,
                modifier = Modifier.padding(end = LettaDimens.Space.xxl),
                // Retaining the previous markdown AST while parsing an update can pair stale
                // annotation offsets with a reshaped block (a Compose Desktop crash).
                retainState = false,
                textColor = if (params.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        Surface(
            modifier = Modifier.align(Alignment.TopEnd),
            shape = CircleShape,
            color = if (hovered) {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = ChatRowAlpha.hoverSurface)
            } else {
                Color.Transparent
            },
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            CopyIconButton(
                CopyAction(
                    text = params.text,
                    contentDescription = stringResource(Res.string.rows_copy_response),
                    emphasized = hovered,
                    visible = hovered,
                ),
            )
        }
    }
}
