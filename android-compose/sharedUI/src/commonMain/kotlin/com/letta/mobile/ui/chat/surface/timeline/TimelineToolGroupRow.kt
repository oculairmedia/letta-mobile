package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRenderItemRow
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowCallbacks

/**
 * Consecutive tool-only messages of one run, read as ONE group of step lines under the label of what
 * they add up to, the same steps a run block lists. Drawn as the one message [TimelineRow.ToolGroup.item]
 * through the row every message takes, so it is the same row a lone tool message draws as.
 */
@Composable
internal fun ToolGroupRow(
    group: TimelineRow.ToolGroup,
    contexts: TimelineRowContexts,
    callbacks: ChatRowCallbacks,
    modifier: Modifier = Modifier,
) {
    ChatRenderItemRow(
        item = group.item,
        context = contexts.forItem(group.item),
        callbacks = callbacks,
        modifier = modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth(),
    )
}
