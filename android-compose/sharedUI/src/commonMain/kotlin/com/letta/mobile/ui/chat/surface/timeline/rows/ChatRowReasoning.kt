package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.letta.mobile.ui.chat.session.ChatMessageId
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.ui.theme.ChatRowMotion
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.stringResource

/**
 * A reasoning step is one of the run's step rows ([RunStepListRow]: the
 * shared [LettaListRow], the run's [RunStatusGlyph] and [DisclosureChevron]), so it reads like the
 * tool calls beside it: "Thinking…" with the run's spinner while it streams, then "Thought for 2.4s"
 * with a check, its first line under the title and a chevron; expanded, the reasoning reads below.
 *
 * Open/closed is owner state (expandedReasoningMessageIds via toggleReasoningExpanded), so it
 * survives the row leaving and re-entering the list.
 */
@Composable
internal fun ReasoningRow(message: UiMessage, context: ChatRowContext, callbacks: ChatRowCallbacks) {
    val isActive = context.isStreaming(message)
    val disclosure = ReasoningDisclosure(
        isActive = isActive,
        collapsed = message.id !in context.itemState.expandedReasoningMessageIds && !isActive,
    )
    val reducedMotion = LocalReducedMotion.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isActive || reducedMotion) Modifier else Modifier.animateContentSize(tween(LettaMotionTokens.CONTENT_SIZE_MILLIS))),
    ) {
        ReasoningHeader(message, disclosure) { callbacks.actions.toggleReasoningExpanded(ChatMessageId(message.id)) }
        ReasoningExpansion(message, disclosure)
    }
}

/** Whether the reasoning still streams (then it neither collapses nor toggles), and whether it is collapsed. */
@Immutable
private data class ReasoningDisclosure(val isActive: Boolean, val collapsed: Boolean)

/** The reasoning's step row: the run's glyph, the title, its first line, and a chevron once it has settled. */
@Composable
private fun ReasoningHeader(message: UiMessage, disclosure: ReasoningDisclosure, onToggle: () -> Unit) {
    val emptyPreview = stringResource(Res.string.rows_reasoning_empty)
    val preview = remember(message.content, emptyPreview) { reasoningPreview(message.content) ?: emptyPreview }
    val spec = RunStepRowSpec(
        title = reasoningTitle(message, disclosure.isActive),
        subtitle = if (disclosure.collapsed) preview else stringResource(Res.string.rows_reasoning_shown),
        tone = if (disclosure.isActive) RunSummaryTone.Working else RunSummaryTone.Done,
        glyphLabel = if (disclosure.isActive) stringResource(Res.string.rows_reasoning_state_working) else null,
        tags = ReasoningTags,
    )
    RunStepListRow(
        spec = spec,
        disclosure = if (disclosure.isActive) null else RunStepDisclosure(!disclosure.collapsed, ReasoningLabels, onToggle),
    )
}

private val ReasoningTags = RunStepRowTags(ChatRowTestTags.REASONING_TOGGLE, ChatRowTestTags.REASONING_GLYPH)

private val ReasoningLabels = RunStepDisclosureLabels(
    expand = Res.string.rows_reasoning_expand,
    collapse = Res.string.rows_reasoning_collapse,
    expandedState = Res.string.rows_reasoning_state_expanded,
    collapsedState = Res.string.rows_reasoning_state_collapsed,
)

/** The reasoning in full under the header while expanded, in line with the row's title. */
@Composable
private fun ColumnScope.ReasoningExpansion(message: UiMessage, disclosure: ReasoningDisclosure) {
    val reducedMotion = LocalReducedMotion.current
    AnimatedVisibility(
        visible = !disclosure.collapsed,
        // Legacy ChatReasoning: slides in from a quarter of its height as it grows.
        enter = ChatRowMotion(reducedMotion).reasoningBodyEnter(),
        exit = ChatRowMotion(reducedMotion).reasoningBodyExit(),
        label = "reasoningExpansion",
    ) {
        Column(modifier = Modifier.padding(horizontal = LettaDimens.Space.sm).padding(top = LettaDimens.Space.xs, bottom = LettaDimens.Space.sm)) {
            ReasoningBody(message, disclosure.isActive)
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
