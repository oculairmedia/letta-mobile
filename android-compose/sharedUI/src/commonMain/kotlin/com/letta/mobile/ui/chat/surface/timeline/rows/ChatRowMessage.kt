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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_copy_response
import com.letta.mobile.sharedui.resources.rows_role_error
import com.letta.mobile.ui.chat.provenance.AgentMessageProvenanceLabel
import com.letta.mobile.ui.chat.render.rememberSmoothedStreamingText
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.ui.theme.ChatBubbleShapes
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.ChatRowType
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: one stand-alone message, drawn as the Android timeline draws it
 * (feature-chat ChatMessageItem / MessageBubbleSurface): the user's prompt as a bubble, plain
 * assistant prose bubble-less on the page, reasoning as a one-line "Thought" disclosure, tool
 * calls as one summary line, and structured content (generated UI, approvals) as cards.
 */
@Composable
internal fun ChatMessageRow(
    message: UiMessage,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    when {
        isUserRole(message.role) && message.subagentNotification == null -> UserPromptRow(message, context, callbacks)
        message.isReasoning && message.subagentNotification == null -> ReasoningRow(message, context, callbacks)
        else -> AssistantMessageColumn(message, context, callbacks)
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
        verticalArrangement = Arrangement.spacedBy(ChatRowSpacing.messagePart),
    ) {
        message.agentMessageProvenance?.let { ProvenanceLabel(message, callbacks) }
        AssistantPrimaryContent(message, context, callbacks)
        MessageImages(message, callbacks)
        val toolCalls = message.toolCalls.orEmpty()
        if (toolCalls.isNotEmpty()) {
            ToolRunGroup(
                calls = ToolRunCalls(
                    toolCalls = remember(toolCalls) { toolCalls.toImmutableList() },
                    startedAtTimestamp = message.timestamp.takeIf { it.isNotBlank() },
                ),
                context = context,
                callbacks = callbacks,
            )
        }
        message.generatedUi?.let { GeneratedUiCard(it, context, callbacks) }
        if (message.artifacts.isNotEmpty()) CanvasArtifactCards(message.artifacts, callbacks)
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
        message.isError -> ErrorBubble(message.content)
        else -> AgentText(
            AgentTextParams(
                text = message.content,
                isError = false,
                isStreaming = context.isStreaming(message),
                deliveryTimestamp = message.timestamp.takeIf { message.id == context.newestMessageId && !context.isStreaming(message) },
            ),
        )
    }
}

/** A server error frame: the error-container bubble with its "Error" label (bubbleStyle isError). */
@Composable
private fun ErrorBubble(text: String) {
    Surface(
        shape = ChatBubbleShapes.agent(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = ChatRowSpacing.bubblePaddingHorizontal, vertical = ChatRowSpacing.bubblePaddingVertical),
            verticalArrangement = Arrangement.spacedBy(ChatRowSpacing.messagePart),
        ) {
            Text(text = stringResource(Res.string.rows_role_error), style = ChatRowType.roleLabel)
            SelectionContainer {
                SharedMarkdownText(text = text, retainState = false, textColor = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
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
internal fun ProvenanceLabel(message: UiMessage, callbacks: ChatRowCallbacks, contentColor: Color? = null) {
    val provenance = message.agentMessageProvenance ?: return
    var expanded by remember(message.id) { mutableStateOf(false) }
    AgentMessageProvenanceLabel(
        provenance = provenance,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded },
        resolveName = callbacks.resolveAgentName,
        onAgentClick = callbacks.host.openAgent,
        contentColor = contentColor,
    )
}

/** Plain agent narration, full width with no bubble. */
@Immutable
internal data class AgentTextParams(
    val text: String,
    val isError: Boolean,
    val isStreaming: Boolean = false,
    /** Set on the conversation's newest settled reply: its delivery time reads under the text. */
    val deliveryTimestamp: String? = null,
)

/**
 * Bubble-less assistant prose on the page (Android's bubbleLess MessageBubbleSurface): the
 * bubble's vertical inset, no horizontal one, so the text shares the timeline's side gutter.
 *
 * While the reply is landing, the shared smoother reveals it progressively instead of snapping
 * each raw chunk in; settled history renders the full text. The markdown is not retained across
 * updates, so a stream reconciliation that SHORTENS the text shrinks it.
 *
 * Where a pointer can hover (desktop), a copy action appears over the end of the first line;
 * hidden, it neither shows nor takes taps.
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
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = ChatRowSpacing.bubblePaddingVertical),
            verticalArrangement = Arrangement.spacedBy(ChatRowSpacing.messagePart),
        ) {
            SelectionContainer {
                SharedMarkdownText(
                    text = displayText,
                    // Retaining the previous markdown AST while parsing an update can pair stale
                    // annotation offsets with a reshaped block (a Compose Desktop crash).
                    retainState = false,
                    textColor = if (params.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            params.deliveryTimestamp?.let { DeliveryTime(it) }
        }
        // The bubble inset centres the action on the first line of text.
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

/** "4:06 PM" under the newest reply (DeliveryTimeText): small, at half strength. */
@Composable
private fun DeliveryTime(timestamp: String) {
    val clock = remember(timestamp) { messageClockLabel(timestamp) } ?: return
    Text(
        text = clock,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .testTag(ChatRowTestTags.CLOCK)
            .padding(top = ChatRowSpacing.deliveryTimeGap)
            .alpha(ChatRowAlpha.deliveryTime),
    )
}
