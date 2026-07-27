package com.letta.mobile.feature.chat.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.chat.render.rememberSmoothedStreamingText
import com.letta.mobile.ui.components.LiveStatusText
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.motion.rememberChatMotionPolicy
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.theme.sectionTitle

private const val REASONING_PREVIEW_MAX_LENGTH = 96

internal object ChatReasoningTestTags {
    const val Header = "chat-reasoning-header"
    const val LiveStatus = "chat-reasoning-live-status"
    const val Title = "chat-reasoning-title"
    const val Preview = "chat-reasoning-preview"
    const val Content = "chat-reasoning-content"
}

@Composable
internal fun MessageReasoning(
    message: UiMessage,
    isStreaming: Boolean,
    collapsed: Boolean,
    onToggleCollapsed: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val motionPolicy = rememberChatMotionPolicy()
    // [isStreaming] is ALREADY scoped to this render item by the caller:
    // ChatMessageListLazyColumn derives isStreamingRenderItem as
    // `itemState.isStreaming && renderItem.containsMessageId(newestMessageId)`, so a
    // historical reasoning block receives false while a later message streams.
    //
    // Do NOT additionally gate on message.isPending: that flag is
    // `deliveryState == SENDING` (an outbound optimistic send awaiting server ack) and
    // is always false for server-streamed assistant reasoning, which would leave the
    // live reveal permanently dead on Android.
    val isActive = isStreaming

    val previewText = remember(message.content) { message.content.reasoningPreview() }
    val isCollapsed = collapsed && !isActive
    val clickLabel = if (isCollapsed) "Expand reasoning" else "Collapse reasoning"
    val stateDesc = if (isCollapsed) {
        stringResource(R.string.work_disclosure_state_collapsed)
    } else {
        stringResource(R.string.work_disclosure_state_expanded)
    }

    // letta-mobile-d2z6: gate animateContentSize on !isActive. While
    // assistant tokens are arriving the reasoning bubble grows on every
    // frame; the default 150ms FastOutSlowIn animation produces visible
    // wobble that compounds with the RunBlock layout. The animation is
    // still useful for the user-initiated collapse/expand toggle, so we
    // keep it gated rather than removing it outright.
    //
    // letta-mobile-5e0f.r2: also suppress during pinch-to-zoom so we
    // don't get height-interpolation cascades across many bubbles per
    // pinch frame.
    val isPinching = LocalChatIsPinching.current
    val sizeAnimation = when {
        isPinching -> Modifier
        motionPolicy.isReducedMotionEnabled -> Modifier
        isActive -> Modifier.animateContentSize(animationSpec = ChatMotion.streamingSizeSpec)
        else -> Modifier.animateContentSize(animationSpec = ChatMotion.contentSizeSpec)
    }

    val durationText = remember(message.latencyMs) {
        message.latencyMs?.let(::formatToolExecutionTime)
    }

    val titleText = when {
        isActive -> "Thinking…"
        durationText != null -> stringResource(R.string.work_disclosure_thought_duration, durationText)
        else -> stringResource(R.string.work_disclosure_thought)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(sizeAnimation)
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ChatReasoningTestTags.Header)
                .semantics(mergeDescendants = true) {
                    stateDescription = stateDesc
                }
                .clickable(
                    enabled = onToggleCollapsed != null,
                    onClickLabel = clickLabel,
                ) { onToggleCollapsed?.invoke() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AnimatedVisibility(
                visible = isActive,
                enter = ChatMotion.horizontalEnter(),
                exit = ChatMotion.horizontalExit(),
            ) {
                @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                LoadingIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (isActive && message.content.isBlank()) {
                LiveStatusText(
                    text = "Thinking…",
                    active = true,
                    style = MaterialTheme.typography.sectionTitle,
                    baseColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                    highlightColor = MaterialTheme.colorScheme.primary,
                    motionPolicy = motionPolicy,
                    modifier = Modifier.testTag(ChatReasoningTestTags.LiveStatus),
                )
            } else {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.sectionTitle,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag(ChatReasoningTestTags.Title),
                )
            }

            Text(
                text = if (isCollapsed) previewText else "Shown",
                style = MaterialTheme.typography.listItemSupporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ChatReasoningTestTags.Preview),
            )

            Icon(
                imageVector = LettaIcons.ExpandMore,
                contentDescription = clickLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (onToggleCollapsed != null) 0.8f else 0.4f,
                ),
                modifier = Modifier
                    .size(LettaIconSizing.Inline)
                    .rotate(if (isCollapsed) 0f else 180f),
            )
        }

        AnimatedVisibility(
            visible = !isCollapsed,
            enter = ChatMotion.verticalEnter(slideDivisor = 4),
            exit = ChatMotion.verticalExit(slideDivisor = 4),
        ) {
            val lineColor = if (isActive) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            Column(
                modifier = Modifier
                    .padding(top = 16.dp, start = 8.dp, bottom = 4.dp)
                    .drawBehind {
                        drawLine(
                            color = lineColor,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 3.dp.toPx(),
                        )
                    }
                    .padding(start = 14.dp),
            ) {
                if (isActive) {
                    if (message.content.isBlank()) {
                        LiveStatusText(
                            text = "Thinking…",
                            active = true,
                            style = MaterialTheme.typography.bodyMedium,
                            baseColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            highlightColor = MaterialTheme.colorScheme.onSurface,
                            motionPolicy = motionPolicy,
                            modifier = Modifier.testTag(ChatReasoningTestTags.LiveStatus),
                        )
                    } else {
                        val displayedContent = if (motionPolicy.isReducedMotionEnabled || motionPolicy.terminalSwap.typewriterStepDelayMillis == 0L) {
                            message.content
                        } else {
                            rememberSmoothedStreamingText(
                                rawText = message.content,
                                isStreaming = true,
                            )
                        }
                        Text(
                            text = displayedContent,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(ChatReasoningTestTags.Content),
                        )
                    }
                } else {
                    MarkdownText(
                        text = message.content,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(ChatReasoningTestTags.Content),
                    )
                }
            }
        }
    }
}

internal fun String.reasoningPreview(): String {
    val normalized = lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
        .replace(Regex("\\s+"), " ")

    if (normalized.isEmpty()) return "No reasoning recorded"
    return if (normalized.length <= REASONING_PREVIEW_MAX_LENGTH) {
        normalized
    } else {
        normalized.take(REASONING_PREVIEW_MAX_LENGTH).trimEnd() + "…"
    }
}

