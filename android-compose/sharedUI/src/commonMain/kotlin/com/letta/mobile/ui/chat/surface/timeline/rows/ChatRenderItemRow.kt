package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-bglj6.1: renders one timeline item (a single message or a run block) with
 * every row type: user prompt, assistant text, reasoning, tool cards and diffs, approvals,
 * A2UI, images and provenance, followed by its clock label.
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
    WithRowFontScale(context.fontScale) {
        Column(modifier = modifier.fillMaxWidth()) {
            when (item) {
                is ChatRenderItem.Single -> ChatMessageRow(item.message, context, callbacks)
                is ChatRenderItem.RunBlock -> RunBlockRow(item, context, callbacks)
            }
            ClockLabel(item)
        }
    }
}

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

/** Per-item clock ("9:41 AM"), aligned to the sender's side. */
@Composable
private fun ClockLabel(item: ChatRenderItem) {
    val clock = remember(item.boundaryTimestamp) { messageClockLabel(item.boundaryTimestamp) } ?: return
    val isUser = (item as? ChatRenderItem.Single)?.message?.role?.let(::isUserRole) == true
    Text(
        text = clock,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (isUser) TextAlign.End else TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatRowTestTags.CLOCK)
            .padding(top = LettaDimens.Space.xs, bottom = LettaDimens.Space.hair),
    )
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
    const val DIFF_BLOCK = "tool-diff"
    const val COMPLETED_ACTIVITY_SUMMARY = "completed-activity-summary"
    const val SUBAGENT_DISPATCH = "subagent-dispatch"
    const val SUBAGENT_NOTIFICATION = "subagent-notification"
    const val USER_PROMPT = "chat-user-prompt"
    const val AGENT_TEXT = "chat-agent-text"
    const val REASONING_TOGGLE = "chat-reasoning-toggle"
    const val RUN_BLOCK = "chat-run-block"
    const val RUN_HEADER = "chat-run-header"
    const val RUN_STEPS_TOGGLE = "chat-run-steps-toggle"
    const val APPROVAL_REASON = "chat-approval-reason"
    const val IMAGE_GRID = "chat-image-grid"
    const val IMAGE_VIEWER = "chat-image-viewer"
    const val CLOCK = "chat-row-clock"
}
