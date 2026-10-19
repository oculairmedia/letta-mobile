package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition

/**
 * Renders one timeline item (a single message or a run block) with
 * every row type: user prompt, assistant text, reasoning, tool summaries, approvals, A2UI,
 * images and provenance. The look is the Android timeline's (feature-chat ChatMessageList).
 *
 * Every intent leaves through [callbacks] (ChatActions / ChatSurfaceHost); every input
 * comes from [context]. Nothing here reads a CompositionLocal the host must provide.
 */
@Composable
internal fun ChatRenderItemRow(
    item: ChatRenderItem,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
    modifier: Modifier = Modifier,
) {
    LocalRowComposed.current?.invoke(item.key)
    WithRowFontScale(context.fontScale) {
        Column(modifier = modifier.fillMaxWidth()) {
            // A lone message holding its run's key draws as that run, as Android does, so the row
            // keeps its shape when a sibling step lands and it becomes a run block. Both shapes go
            // through the one RunBlockRow call: a live reply streams as such a lone message and
            // settles as a one-message run block, and two call sites tore the run down and rebuilt
            // it at settle, its body drawing empty for a frame (letta-mobile-by4vm.105).
            val run = item.runMessages()
            if (run != null) {
                RunBlockRow(run, context, callbacks, item.loneGroupPosition())
            } else if (item is ChatRenderItem.Single) {
                ChatMessageRow(item.message, context, callbacks, item.groupPosition)
            }
        }
    }
}

/**
 * Test seam: told the item's key each time a row composes, so tests can prove what does not
 * recompose it (a pinch frame). No host provides it.
 */
internal val LocalRowComposed = staticCompositionLocalOf<((key: String) -> Unit)?> { null }

/** The page's timeline text scale (pinch / settings) applied to the row's text only. */
@Composable
private fun WithRowFontScale(fontScale: Float, content: @Composable () -> Unit) {
    if (fontScale == 1f) {
        content()
        return
    }
    val base = LocalDensity.current
    val scaled = remember(base, fontScale) { Density(base.density, base.fontScale * fontScale) }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

/** The full-screen image viewer the timeline shows over everything when an image is tapped. */
@Composable
internal fun ChatImageViewer(
    images: List<UiImageAttachment>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    ChatImageViewerContent(images = images, initialIndex = initialIndex, onDismiss = onDismiss)
}

/** Test tags shared with the desktop row tests (the tool ones keep desktop's names). */
internal object ChatRowTestTags {
    /** The contextual action under a run error. */
    const val ERROR_ACTION = "run-error-action"

    /** "Fork from here" beside a reply's Copy. */
    const val FORK_REPLY = "fork-reply"
    const val TOOL_CARD_TOGGLE = "tool-card-toggle"
    const val TOOL_CARD_BODY = "tool-card-body"
    /** A tool step's leading status glyph: the same [RunStatusGlyph] as the run summary line's. */
    const val TOOL_STATUS_GLYPH = "tool-status-glyph"
    const val TOOL_OUTCOME = "tool-outcome"
    const val TOOL_EXECUTING = "tool-executing"
    const val TOOL_OUTPUT = "tool-output"
    const val TOOL_RESULT_PREVIEW = "tool-result-preview"
    const val CANVAS_ARTIFACT = "chat-canvas-artifact"
    const val CANVAS_ARTIFACT_SHOW = "chat-canvas-artifact-show"
    const val CANVAS_ARTIFACT_STATUS = "chat-canvas-artifact-status"
    const val TOOL_RUN_SUMMARY = "tool-run-summary-row"
    const val TOOL_RUN_DETAILS = "tool-run-details-sheet"
    const val TOOL_RUN_INLINE = "tool-run-details-inline"
    const val DIFF_BLOCK = "tool-diff"
    const val COMPLETED_ACTIVITY_SUMMARY = "completed-activity-summary"
    const val SUBAGENT_DISPATCH = "subagent-dispatch"
    const val SUBAGENT_NOTIFICATION = "subagent-notification"
    const val USER_PROMPT = "chat-user-prompt"
    /** An inter-agent prompt's provenance header, inside its bubble. */
    const val PROMPT_PROVENANCE = "chat-user-prompt-provenance"
    const val AGENT_TEXT = "chat-agent-text"
    const val REASONING_TOGGLE = "chat-reasoning-toggle"
    /** The reasoning row's [RunStatusGlyph]: the run's spinner while it streams, a check once it settles. */
    const val REASONING_GLYPH = "chat-reasoning-glyph"
    const val RUN_BLOCK = "chat-run-block"
    const val RUN_HEADER = "chat-run-header"
    /** The run summary line's leading status glyph (check, spinner, warning). */
    const val RUN_GLYPH = "chat-run-glyph"
    /** One work step (a reasoning or the tool line) listed under an expanded run's summary. */
    const val RUN_WORK_STEP = "chat-run-work-step"
    /** An expanded run's tool steps, listed straight under its summary line (no group row). */
    const val RUN_TOOL_STEPS = "chat-run-tool-steps"
    /** The plain, non-disclosing subheader over a run's tool steps of more than one kind. */
    const val RUN_STEP_SUBHEADER = "chat-run-step-subheader"
    const val APPROVAL_REASON = "chat-approval-reason"
    const val APPROVAL_REJECT_DIALOG = "chat-approval-reject-dialog"
    const val APPROVAL_REJECT_CONFIRM = "chat-approval-reject-confirm"
    const val APPROVAL_TOOL_CALL = "chat-approval-tool-call"
    const val APPROVAL_REQUESTING_INPUT = "chat-approval-requesting-input"
    const val APPROVAL_SLOT = "chat-approval-slot"
    const val APPROVAL_STEP_HEADER = "chat-approval-step-header"
    const val APPROVAL_STEP_COUNTER = "chat-approval-step-counter"
    const val APPROVAL_STEP_BACK = "chat-approval-step-back"
    const val APPROVAL_STEP_NEXT = "chat-approval-step-next"
    const val APPROVAL_QUEUED = "chat-approval-queued"
    const val APPROVAL_BLOCKED_PATH = "chat-approval-blocked-path"
    const val APPROVAL_DIFF = "chat-approval-diff"
    const val APPROVAL_DIFF_SHOW_ALL = "chat-approval-diff-show-all"
    const val APPROVAL_ALWAYS_ALLOW = "chat-approval-always-allow"
    /** The plan an approval proposes (ExitPlanMode, or a write to a plans file). */
    const val APPROVAL_PLAN = "chat-approval-plan"
    const val IMAGE_GRID = "chat-image-grid"
    const val IMAGE_VIEWER = "chat-image-viewer"
    const val CLOCK = "chat-row-clock"
    const val SPEAKER_HEADER = "chat-row-speaker-header"
}

/** The messages [this] draws as a run, or null for a lone message outside any run. */
private fun ChatRenderItem.runMessages(): List<UiMessage>? = when (this) {
    is ChatRenderItem.RunBlock -> messages.map { it.first }
    is ChatRenderItem.Single -> listOf(message).takeIf { stableRunId != null || stableRunKey != null }
}

/** A lone message's place in its speaker's group; a run block has none of its own. */
private fun ChatRenderItem.loneGroupPosition(): GroupPosition = when (this) {
    is ChatRenderItem.RunBlock -> GroupPosition.None
    is ChatRenderItem.Single -> groupPosition
}
