package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_reasoning_collapse
import com.letta.mobile.sharedui.resources.rows_reasoning_empty
import com.letta.mobile.sharedui.resources.rows_reasoning_expand
import com.letta.mobile.sharedui.resources.rows_reasoning_shown
import com.letta.mobile.sharedui.resources.rows_reasoning_state_collapsed
import com.letta.mobile.sharedui.resources.rows_reasoning_state_expanded
import com.letta.mobile.sharedui.resources.rows_reasoning_state_working
import com.letta.mobile.sharedui.resources.rows_run_for_duration
import com.letta.mobile.sharedui.resources.rows_thinking
import com.letta.mobile.sharedui.resources.rows_thought
import com.letta.mobile.ui.chat.render.rememberSmoothedStreamingText
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowType
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.chat.surface.ambient.LocalChatWorkingCueAnimated
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: a reasoning step as the Android timeline draws it (feature-chat
 * MessageReasoning): one line, "Thought for 2.4s" beside a muted, ellipsized first line of the
 * reasoning and a trailing chevron; expanded, the reasoning reads below, flush with the header.
 * While it streams the header says "Thinking…" with a spinner and the text reveals as it lands.
 *
 * Open/closed is owner state (expandedReasoningMessageIds via toggleReasoningExpanded), so it
 * survives the row leaving and re-entering the list.
 */
@Composable
internal fun ReasoningRow(message: UiMessage, context: ChatRowContext, callbacks: ChatRowCallbacks) {
    val isActive = context.isStreaming(message)
    val collapsed = message.id !in context.itemState.expandedReasoningMessageIds && !isActive
    val canToggle = !isActive
    val reducedMotion = LocalReducedMotion.current
    val stateText = stringResource(
        when {
            isActive -> Res.string.rows_reasoning_state_working
            collapsed -> Res.string.rows_reasoning_state_collapsed
            else -> Res.string.rows_reasoning_state_expanded
        },
    )
    val clickLabel = stringResource(if (collapsed) Res.string.rows_reasoning_expand else Res.string.rows_reasoning_collapse)
    val click = rememberQuietClick()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isActive || reducedMotion) Modifier else Modifier.animateContentSize(tween(LettaMotionTokens.CONTENT_SIZE_MILLIS)))
            .padding(vertical = LettaDimens.Space.xs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ChatRowTestTags.REASONING_TOGGLE)
                .semantics(mergeDescendants = true) { stateDescription = stateText }
                .then(
                    if (canToggle) {
                        // The same tap-target floor as the run header, so the two rows measure alike.
                        // No hover block: the title lifts instead (the Android row has no inset).
                        Modifier
                            .quietClickable(click, onClickLabel = clickLabel) { callbacks.actions.toggleReasoningExpanded(message.id) }
                            .heightIn(min = LettaDimens.Orb.railSlotHeight)
                            .padding(vertical = LettaDimens.Space.hair)
                    } else {
                        Modifier.padding(vertical = LettaDimens.Space.xs)
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            // Docked over the canvas the panel's ambient glow is the thinking cue, not the spinner.
            AnimatedVisibility(visible = isActive && LocalChatWorkingCueAnimated.current, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                CircularProgressIndicator(
                    modifier = Modifier.size(LettaDimens.Control.icon),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = LettaDimens.Space.hair,
                )
            }
            Text(
                text = reasoningTitle(message, isActive),
                style = ChatRowType.sectionTitle,
                color = when {
                    isActive -> MaterialTheme.colorScheme.primary.copy(alpha = ChatRowAlpha.reasoningActiveTitle)
                    click.lifted -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            val emptyPreview = stringResource(Res.string.rows_reasoning_empty)
            val preview = remember(message.content, emptyPreview) { reasoningPreview(message.content) ?: emptyPreview }
            Text(
                text = if (collapsed) preview else stringResource(Res.string.rows_reasoning_shown),
                style = ChatRowType.listItemSupporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ChatRowAlpha.reasoningPreview),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DisclosureChevron(expanded = !collapsed, enabled = canToggle)
        }
        AnimatedVisibility(visible = !collapsed, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(modifier = Modifier.padding(top = LettaDimens.Space.lg, bottom = LettaDimens.Space.xs)) {
                ReasoningBody(message, isActive)
            }
        }
    }
}

@Composable
private fun reasoningTitle(message: UiMessage, isActive: Boolean): String {
    if (isActive) return stringResource(Res.string.rows_thinking)
    val thought = stringResource(Res.string.rows_thought)
    val latency = message.latencyMs ?: return thought
    return stringResource(Res.string.rows_run_for_duration, thought, formatRunDuration(latency))
}

@Composable
private fun ReasoningBody(message: UiMessage, isActive: Boolean) {
    if (isActive) {
        val shown = if (message.content.isBlank()) stringResource(Res.string.rows_thinking) else message.content
        val text = rememberSmoothedStreamingText(rawText = shown, isStreaming = true)
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    SelectionContainer {
        SharedMarkdownText(
            text = message.content.trim(),
            retainState = false,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The first non-blank line, whitespace collapsed, capped with an ellipsis; null when there is none. */
internal fun reasoningPreview(content: String): String? {
    val firstLine = content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    val normalized = firstLine.replace(WHITESPACE, " ")
    return if (normalized.length <= REASONING_PREVIEW_MAX_LENGTH) {
        normalized
    } else {
        normalized.take(REASONING_PREVIEW_MAX_LENGTH).trimEnd() + ELLIPSIS
    }
}

private val WHITESPACE = Regex("\\s+")
private const val ELLIPSIS = "…"
private const val REASONING_PREVIEW_MAX_LENGTH = 96
