package com.letta.mobile.feature.chat

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.theme.sectionTitle

private const val REASONING_PREVIEW_MAX_LENGTH = 96

@Composable
internal fun MessageReasoning(
    message: UiMessage,
    isStreaming: Boolean,
    collapsed: Boolean,
    onToggleCollapsed: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val previewText = remember(message.content) { message.content.reasoningPreview() }
    val isCollapsed = collapsed && !isStreaming
    val clickLabel = if (isCollapsed) "Expand reasoning" else "Collapse reasoning"

    // letta-mobile-d2z6: gate animateContentSize on !isStreaming. While
    // assistant tokens are arriving the reasoning bubble grows on every
    // frame; the default 150ms FastOutSlowIn animation produces visible
    // wobble that compounds with the RunBlock layout. The animation is
    // still useful for the user-initiated collapse/expand toggle, so we
    // keep it gated rather than removing it outright.
    //
    // letta-mobile-5e0f.r2: also suppress during pinch-to-zoom so we
    // don't get height-interpolation cascades across many bubbles per
    // pinch frame.
    //
    // letta-mobile-d2z6.s1 (Emmanuel 2026-04-26 01:28 EDT — "add easing
    // to the chunks coming on so it's smoother"): instead of fully
    // suppressing the animation while streaming, swap to a SHORT linear
    // tween (60ms) that's faster than typical token inter-arrival
    // (~80–150ms). Each chunk's height delta interpolates briefly
    // instead of snapping in, but the spec is short enough that
    // successive chunks don't stack into compounding wobble — the
    // animation always finishes (or near-finishes) before the next
    // chunk arrives. The collapse/expand toggle keeps the default
    // (longer, eased) spec by virtue of the !isStreaming branch.
    val isPinching = LocalChatIsPinching.current
    val sizeAnimation = when {
        isPinching -> Modifier
        isStreaming -> Modifier.animateContentSize(
            animationSpec = ChatMotion.streamingSizeSpec,
        )
        else -> Modifier.animateContentSize(animationSpec = ChatMotion.contentSizeSpec)
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
                .clickable(
                    enabled = onToggleCollapsed != null,
                    onClickLabel = clickLabel,
                ) { onToggleCollapsed?.invoke() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AnimatedVisibility(
                visible = isStreaming,
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
                text = if (isStreaming) "Thinking…" else "Reasoning",
                style = MaterialTheme.typography.sectionTitle,
                color = if (isStreaming) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = if (isCollapsed) previewText else "Shown",
                style = MaterialTheme.typography.listItemSupporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = LettaIcons.ExpandMore,
                contentDescription = clickLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (onToggleCollapsed != null) 0.8f else 0.4f),
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
            val lineColor = if (isStreaming) {
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
                // letta-mobile-d2z6 (root cause): MarkdownText re-parses on
                // every content change and re-emits a fresh subtree, which
                // causes the bubble to visibly flicker on each streaming
                // chunk. Use plain Text during streaming and snap to
                // formatted markdown when the stream ends.
                if (isStreaming) {
                    val smoothedContent = rememberSmoothedStreamingText(
                        rawText = message.content,
                        isStreaming = true,
                    )
                    Text(
                        text = smoothedContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MarkdownText(
                        text = message.content,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
