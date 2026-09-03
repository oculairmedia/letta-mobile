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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.chat.render.rememberSmoothedStreamingText
import com.letta.mobile.ui.components.LiveStatusText
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.motion.rememberChatMotionPolicy
import com.letta.mobile.ui.preview.LettaPreviewFrame
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.theme.sectionTitle

private const val REASONING_PREVIEW_MAX_LENGTH = 96

internal object ChatReasoningTestTags {
    val Header = "chat-reasoning-header"
    val LiveStatus = "chat-reasoning-live-status"
    val Title = "chat-reasoning-title"
    val Preview = "chat-reasoning-preview"
    val Content = "chat-reasoning-content"
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
    val canToggle = onToggleCollapsed != null && !isActive
    val clickLabel = when {
        !canToggle -> null
        isCollapsed -> stringResource(R.string.reasoning_disclosure_expand)
        else -> stringResource(R.string.reasoning_disclosure_collapse)
    }
    val stateDesc = stringResource(
        when {
            isActive -> R.string.reasoning_disclosure_state_working
            isCollapsed -> R.string.reasoning_disclosure_state_collapsed
            else -> R.string.reasoning_disclosure_state_expanded
        },
    )

    // While assistant tokens arrive, update height directly. Interpolating
    // every token-driven size change compounds with the smoothed text reveal
    // and the enclosing run layout, producing visible vertical wobble. Keep
    // size motion only for terminal user-initiated collapse/expand.
    //
    // letta-mobile-5e0f.r2: also suppress during pinch-to-zoom so we
    // don't get height-interpolation cascades across many bubbles per
    // pinch frame.
    val isPinching = LocalChatIsPinching.current
    val sizeAnimation = when {
        isPinching -> Modifier
        motionPolicy.isReducedMotionEnabled -> Modifier
        isActive -> Modifier
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
            // letta-mobile: MessageReasoning renders standalone (bypasses
            // ChatMessageBubble, which is where every other run-step row gets
            // its horizontal inset from `chatDimens.bubblePaddingHorizontal`).
            // Without matching it here, the "Thought" title sits flush
            // against the run gutter while sibling tool-call rows sit 10dp
            // further right, so their content doesn't line up under a
            // shared run's dot/rail — match the same token.
            .padding(horizontal = MaterialTheme.chatDimens.bubblePaddingHorizontal, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ChatReasoningTestTags.Header)
                .semantics(mergeDescendants = true) {
                    stateDescription = stateDesc
                }
                .then(
                    if (canToggle) {
                        Modifier.clickable(
                            onClickLabel = clickLabel,
                            onClick = { onToggleCollapsed.invoke() },
                        )
                    } else {
                        Modifier
                    },
                )
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
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (canToggle) 0.8f else 0.4f,
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
            Column(
                modifier = Modifier
                    .padding(top = 16.dp, start = 8.dp, bottom = 4.dp),
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

// region Previews

private fun previewReasoningMessage(
    content: String,
    latencyMs: Long? = null,
): UiMessage = UiMessage(
    id = "preview-reasoning",
    role = "assistant",
    content = content,
    timestamp = "2026-08-08T00:00:00Z",
    latencyMs = latencyMs,
    isReasoning = true,
)

@PreviewLightDark
@Composable
private fun MessageReasoningCollapsedPreview() {
    LettaPreviewFrame {
        LettaChatTheme {
            MessageReasoning(
                message = previewReasoningMessage(
                    content = "Let me break this down step by step.\nFirst, the user is asking about the latest sales data.",
                    latencyMs = 1_400,
                ),
                isStreaming = false,
                collapsed = true,
                onToggleCollapsed = {},
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun MessageReasoningExpandedPreview() {
    LettaPreviewFrame {
        LettaChatTheme {
            MessageReasoning(
                message = previewReasoningMessage(
                    content = "The user wants the latest sales numbers. I'll look at the Q3 totals first, then compare against last quarter.",
                    latencyMs = 3_200,
                ),
                isStreaming = false,
                collapsed = false,
                onToggleCollapsed = {},
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun MessageReasoningStreamingPreview() {
    LettaPreviewFrame {
        LettaChatTheme {
            MessageReasoning(
                message = previewReasoningMessage(
                    content = "",
                ),
                isStreaming = true,
                collapsed = false,
                onToggleCollapsed = {},
            )
        }
    }
}

// endregion
