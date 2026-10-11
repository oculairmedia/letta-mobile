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
import com.letta.mobile.data.chat.projection.requiresUserInput
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_copy_response
import com.letta.mobile.sharedui.resources.rows_role_error
import com.letta.mobile.sharedui.resources.rows_role_tool_activity
import com.letta.mobile.sharedui.resources.rows_role_tool_output
import com.letta.mobile.ui.chat.provenance.AgentMessageProvenanceLabel
import com.letta.mobile.ui.chat.render.bubbleStyle
import com.letta.mobile.ui.chat.render.rememberSmoothedStreamingText
import com.letta.mobile.ui.chat.render.shouldPulseForStreamingReveal
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
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
    position: GroupPosition = GroupPosition.None,
) {
    when {
        message.isCompaction -> CompactionDividerRow(message.content)
        isUserRole(message.role) && message.subagentNotification == null ->
            UserPromptRow(message, context, callbacks, PromptGrouping.of(position))
        message.isReasoning && message.subagentNotification == null -> ReasoningRow(message, context, callbacks)
        else -> AssistantMessageColumn(message, context, callbacks, position)
    }
}

@Composable
private fun AssistantMessageColumn(
    message: UiMessage,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
    position: GroupPosition,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ChatRowSpacing.messagePart),
    ) {
        if (touchStyle() && showsSpeakerHeader(message, position)) SpeakerHeader(message, context)
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
        message.isError -> ErrorBubble(message.content) { RunErrorActionButton(message.content, context, callbacks) }
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

/**
 * letta-mobile-bglj6.1.23: the legacy bubble's role label (MessageBubbleSurface) on a structured
 * row: generated UI, approvals, a subagent's report, images, a tool's own output. Plain prose and
 * a bare tool-call line stay unlabelled (bubble-less), errors label themselves, and only the
 * first row of a speaker's group carries it.
 */
internal fun showsSpeakerHeader(message: UiMessage, position: GroupPosition): Boolean {
    if (position != GroupPosition.First && position != GroupPosition.None) return false
    if (message.isError) return false
    return when (message.role) {
        "tool" -> true
        "assistant" -> message.hasStructuredContent()
        else -> false
    }
}

/**
 * What turns an assistant message into a bubbled card in the legacy timeline (not
 * shouldRenderBubbleLess). An approval counts only while it waits on the person: a runtime-resolved
 * one draws no card (letta-mobile-bglj6.1.25), so it must not label the row either.
 */
private fun UiMessage.hasStructuredContent(): Boolean {
    val cards = listOf(generatedUi, approvalResponse, subagentNotification)
    return cards.any { it != null } || approvalRequest?.requiresUserInput() == true || attachments.isNotEmpty()
}

/** "Agent" (or "Agent · Live"), "Inter-agent", the single tool's name, or "Tool output". */
@Composable
private fun SpeakerHeader(message: UiMessage, context: ChatRowContext) {
    val style = bubbleStyle(
        role = message.role,
        isStreaming = context.isStreaming(message),
        isAgentMessage = message.agentMessageProvenance != null,
    )
    val toolOutput = stringResource(Res.string.rows_role_tool_output)
    val toolActivity = stringResource(Res.string.rows_role_tool_activity)
    val label = message.toolCalls?.singleOrNull()?.name ?: when {
        message.role != "tool" -> style.roleLabel
        message.content.isNotBlank() -> toolOutput
        else -> toolActivity
    }
    Text(
        text = label,
        style = ChatRowType.roleLabel,
        color = style.roleColor,
        modifier = Modifier.testTag(ChatRowTestTags.SPEAKER_HEADER),
    )
}

/** A server error frame: the error-container bubble with its "Error" label (bubbleStyle isError). */
@Composable
private fun ErrorBubble(text: String, action: @Composable () -> Unit = {}) {
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
                SharedMarkdownText(text = text, textColor = MaterialTheme.colorScheme.onErrorContainer)
            }
            action()
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
    // letta-mobile-bglj6.1.18: while the reply lands, the smoother reveals it progressively
    // (seeded with the already-painted text — pulled forward into PR #1784 as the fix for the
    // settle-glitch test's reveal race), and its reveal steps pulse the streaming haptic cue
    // through the LocalHaptics seam, gated exactly as the legacy Android chat gated it. A host
    // backend makes the pulse audible (bglj6.1.17, PR #1784).
    val displayText = if (params.isStreaming) {
        // letta-mobile-bglj6.1.18: seeded with whatever was already painted (the uoiu6
        // first-word-flash fix), so a stream engaging on visible text keeps it instead of
        // re-revealing it — and a streaming row's first frame already has the settled shape
        // (the invariant ChatRowRunSettleGlitchTest asserts; the unseeded reveal raced the test's
        // measurement and flaked CI). The seeded text was already painted, so it does not pulse.
        val haptics = LocalHaptics.current
        var lastRevealLength by remember { mutableStateOf(params.text.length) }
        rememberSmoothedStreamingText(
            rawText = params.text,
            isStreaming = true,
            seedText = params.text,
            onRevealStep = { revealed ->
                if (shouldPulseForStreamingReveal(lastRevealLength, revealed)) {
                    haptics.play(LettaHapticCue.StreamingPulse)
                }
                lastRevealLength = revealed.length
            },
        )
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
                    textColor = if (params.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    // A rich host renderer owns the streaming reveal (cursor, committed blocks,
                    // settle) itself; the default renderer ignores the flag.
                    isStreaming = params.isStreaming,
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
    val twentyFourHour = systemUses24HourClock()
    val clock = remember(timestamp, twentyFourHour) { messageClockLabel(timestamp, twentyFourHour = twentyFourHour) } ?: return
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
