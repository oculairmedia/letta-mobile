package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowCallbacks
import com.letta.mobile.ui.chat.surface.timeline.rows.RunToolSteps
import com.letta.mobile.ui.chat.surface.timeline.rows.ToolRunCalls
import kotlinx.collections.immutable.toImmutableList

/**
 * Consecutive tool-only messages of one run, read as ONE group of step lines under the label of what
 * they add up to, the same steps a run block lists. A request they wait on keeps its card under them.
 */
@Composable
internal fun ToolGroupRow(
    group: TimelineRow.ToolGroup,
    contexts: TimelineRowContexts,
    callbacks: ChatRowCallbacks,
    modifier: Modifier = Modifier,
) {
    val toolCalls = remember(group) { group.singles.flatMap { it.message.toolCalls.orEmpty() }.toImmutableList() }
    val approvals = remember(group) { group.singles.mapNotNull { it.message.approvalRequest }.toImmutableList() }
    RunToolSteps(
        calls = ToolRunCalls(toolCalls = toolCalls, approvals = approvals),
        context = contexts.forItem(group.singles.last()),
        callbacks = callbacks,
        modifier = modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth(),
    )
}
