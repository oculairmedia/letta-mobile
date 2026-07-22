package com.letta.mobile.desktop.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.desktop.DesktopTooltip
import com.letta.mobile.ui.chat.render.rememberSmoothedStreamingText
import kotlinx.coroutines.delay

import kotlin.time.Duration.Companion.milliseconds
@Composable
internal fun DesktopMessageBubble(
    message: UiMessage,
    streamingMessageId: StreamingMessageId? = null,
) {
    if (MessageRoleToken(message.role).isUser()) {
        UserPrompt(message)
        return
    }
    AssistantMessageColumn(message = message, streamingMessageId = streamingMessageId)
}

@Composable
private fun AssistantMessageColumn(
    message: UiMessage,
    streamingMessageId: StreamingMessageId?,
) {
    // Assistant message (standalone): reasoning / text / tool cards, full-width.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (message.isReasoning && message.content.isNotBlank()) {
            ReasoningRow(message.content)
        } else if (message.content.isNotBlank()) {
            AgentText(
                AgentTextParams(
                    text = message.content,
                    isError = message.isError,
                    isStreaming = streamingMessageId?.value == message.id,
                ),
            )
        }
        DesktopImageAttachmentsGrid(
            attachments = message.attachments,
            modifier = Modifier.fillMaxWidth(),
        )
        message.toolCalls.orEmpty().forEachIndexed { index, toolCall ->
            ToolCard(
                toolCall = toolCall,
                disclosureKey = toolCall.toolCallId ?: "${message.id}:$index",
            )
        }
        message.generatedUi?.let { GeneratedUiCard(it) }
        message.approvalRequest?.let { ApprovalRequestCard(it) }
        message.approvalResponse?.let { ApprovalResponseCard(it) }
    }
}

/**
 * Small, functional copy affordance: click copies [text] to the clipboard and
 * the glyph briefly flips to a green check. Replaces the former decorative copy
 * glyphs that did nothing on click.
 */
@Composable
internal fun CopyIconButton(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    config: CopyActionConfig = CopyActionConfig(),
) {
    val clipboard = LocalClipboardManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200.milliseconds)
            copied = false
        }
    }
    val visualAlpha by animateFloatAsState(
        targetValue = if (config.emphasized or focused or hovered or copied) 1f else 0.58f,
        animationSpec = tween(durationMillis = 120),
        label = "copyActionAlpha",
    )
    val resolvedDescription = if (copied) "Copied" else config.contentDescription
    DesktopTooltip(text = resolvedDescription) {
        CopyButtonVisual(
            state = CopyButtonVisualState(copied, focused, visualAlpha),
            style = CopyButtonVisualStyle(tint, resolvedDescription),
            interaction = CopyButtonInteraction(interactionSource) {
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
            modifier = modifier,
        )
    }
}

internal data class CopyActionConfig(
    val contentDescription: String = "Copy",
    val emphasized: Boolean = true,
)

@Composable
private fun CopyButtonVisual(
    state: CopyButtonVisualState,
    style: CopyButtonVisualStyle,
    interaction: CopyButtonInteraction,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = 36.dp, minHeight = 36.dp)
            .graphicsLayer { alpha = state.visualAlpha }
            .clip(CircleShape)
            .border(1.dp, state.focusBorderColor(), CircleShape)
            .semantics { contentDescription = style.description }
            .clickable(
                interactionSource = interaction.source,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = interaction.onCopy,
            )
            .focusable(interactionSource = interaction.source),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (state.copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
            contentDescription = null,
            tint = if (state.copied) Color(0xFF34C759) else style.tint,
            modifier = Modifier.size(14.dp),
        )
    }
}

private data class CopyButtonVisualState(
    val copied: Boolean,
    val focused: Boolean,
    val visualAlpha: Float,
) {
    @Composable
    fun focusBorderColor(): Color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
}

private data class CopyButtonVisualStyle(val tint: Color, val description: String)

private data class CopyButtonInteraction(
    val source: MutableInteractionSource,
    val onCopy: () -> Unit,
)

/** User prompt — teal bubble, right-aligned, with a copy affordance. */
@Composable
internal fun UserPrompt(message: UiMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (message.content.isNotBlank()) {
                    SelectionContainer {
                        Text(text = message.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                DesktopImageAttachmentsGrid(
                    attachments = message.attachments,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // Copy is the only wired affordance — a message "edit"/resend needs
        // conversation-fork support that isn't in place, so it's omitted rather
        // than shown as a dead control.
        if (message.content.isNotBlank()) {
            CopyIconButton(
                text = message.content,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                config = CopyActionConfig(contentDescription = "Copy message"),
            )
        }
    }
}

/**
 * Inline "thinking" indicator shown in the thread while a send is in flight:
 * the agent's teal sphere avatar + three breathing dots, so the user gets
 * immediate feedback before the response starts streaming.
 */
@Composable
internal fun ThinkingMessageRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentActivityOrb(size = 40.dp, activity = AgentActivity.Working)
        ThinkingDots()
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinkingDots")
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 160, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "thinkingDot$index",
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@JvmInline
internal value class IsoTimestamp(val value: String)

/** Formats a message's ISO timestamp as a local clock label, e.g. "9:41 AM". */
internal fun messageClockLabel(iso: String): String? =
    messageClockLabel(IsoTimestamp(iso))

internal fun messageClockLabel(iso: IsoTimestamp): String? {
    if (iso.value.isBlank()) return null
    val zone = java.time.ZoneId.systemDefault()
    val zoned = runCatching { java.time.Instant.parse(iso.value).atZone(zone) }
        .recoverCatching { java.time.OffsetDateTime.parse(iso.value).atZoneSameInstant(zone) }
        .recoverCatching { java.time.LocalDateTime.parse(iso.value).atZone(zone) }
        .getOrNull() ?: return null
    return zoned.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
}

/**
 * The id of the assistant message inside this render item that should be
 * smoothed while a reply is in flight: the newest non-reasoning, non-tool,
 * non-blank assistant narration. Within a [ChatRenderItem.RunBlock] the
 * messages are in chat order (oldest first), so the newest narration is the
 * last matching one. Returns null for user prompts or items with no narration.
 */
internal fun ChatRenderItem.streamingCandidateMessageId(): String? = when (this) {
    is ChatRenderItem.SkillEnvelopeChip -> null
    is ChatRenderItem.Single -> message
        .takeIf {
            !MessageRoleToken(it.role).isUser() &&
                !it.isReasoning &&
                it.toolCalls.isNullOrEmpty() &&
                it.content.isNotBlank()
        }
        ?.id
    is ChatRenderItem.RunBlock -> messages
        .map { it.first }
        .lastOrNull { !it.isReasoning && it.toolCalls.isNullOrEmpty() && it.content.isNotBlank() }
        ?.id
}

/** Plain agent narration text, full width (no bubble), per the detailed board. */
internal data class AgentTextParams(
    val text: String,
    val isError: Boolean,
    val isStreaming: Boolean = false,
)

@Composable
internal fun AgentText(text: String, isError: Boolean, isStreaming: Boolean = false) =
    AgentText(AgentTextParams(text = text, isError = isError, isStreaming = isStreaming))

@Composable
internal fun AgentText(params: AgentTextParams) {
    // While the agent's reply is still landing, reveal the latest assistant
    // message progressively via the shared smoother (the same hook Android
    // uses) instead of snapping in each raw chunk. Settled history renders the
    // full text directly. The smoother continues revealing the buffered tail at
    // its own cadence even after [isStreaming] flips back to false, so the reply
    // still finishes smoothly once the in-flight signal clears.
    val displayText = if (params.isStreaming) {
        rememberSmoothedStreamingText(rawText = params.text, isStreaming = true)
    } else {
        params.text
    }
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hoverInteraction),
    ) {
        SelectionContainer {
            com.letta.mobile.ui.markdown.SharedMarkdownText(
                text = displayText,
                modifier = Modifier.padding(end = 32.dp),
                textColor = if (params.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Surface(
            modifier = Modifier.align(Alignment.TopEnd),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isHovered) 0.94f else 0.35f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            CopyIconButton(
                text = params.text,
                config = CopyActionConfig(contentDescription = "Copy response", emphasized = isHovered),
            )
        }
    }
}
