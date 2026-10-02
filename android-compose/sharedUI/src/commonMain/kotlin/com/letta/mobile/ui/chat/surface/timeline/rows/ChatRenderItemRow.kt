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

/**
 * letta-mobile-bglj6.1: renders one timeline item (a single message or a run block) with
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
            when (item) {
                is ChatRenderItem.RunBlock -> RunBlockRow(item.runId, item.messages.map { it.first }, context, callbacks)
                // A lone message holding its run's key draws as that run, as Android does, so the
                // row keeps its shape when a sibling step lands and it becomes a run block.
                is ChatRenderItem.Single -> {
                    val runId = item.stableRunId ?: item.stableRunKey?.removePrefix(RUN_KEY_PREFIX)
                    if (runId != null) {
                        RunBlockRow(runId, listOf(item.message), context, callbacks)
                    } else {
                        ChatMessageRow(item.message, context, callbacks)
                    }
                }
            }
        }
    }
}

private const val RUN_KEY_PREFIX = "run-"

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
    const val TOOL_CARD_TOGGLE = "tool-card-toggle"
    const val TOOL_CARD_BODY = "tool-card-body"
    const val TOOL_FAILURE_BADGE = "tool-failure-badge"
    const val TOOL_OUTPUT = "tool-output"
    const val TOOL_RESULT_PREVIEW = "tool-result-preview"
    const val TOOL_RUN_SUMMARY = "tool-run-summary-row"
    const val TOOL_RUN_DETAILS = "tool-run-details-sheet"
    const val TOOL_RUN_INLINE = "tool-run-details-inline"
    const val DIFF_BLOCK = "tool-diff"
    const val COMPLETED_ACTIVITY_SUMMARY = "completed-activity-summary"
    const val SUBAGENT_DISPATCH = "subagent-dispatch"
    const val SUBAGENT_NOTIFICATION = "subagent-notification"
    const val USER_PROMPT = "chat-user-prompt"
    const val AGENT_TEXT = "chat-agent-text"
    const val REASONING_TOGGLE = "chat-reasoning-toggle"
    const val RUN_BLOCK = "chat-run-block"
    const val RUN_HEADER = "chat-run-header"
    const val APPROVAL_REASON = "chat-approval-reason"
    const val IMAGE_GRID = "chat-image-grid"
    const val IMAGE_VIEWER = "chat-image-viewer"
    const val CLOCK = "chat-row-clock"
}
