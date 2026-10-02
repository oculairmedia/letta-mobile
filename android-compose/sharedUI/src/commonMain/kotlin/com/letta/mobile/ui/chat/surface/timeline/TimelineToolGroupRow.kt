package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowCallbacks
import com.letta.mobile.ui.chat.surface.timeline.rows.ToolRunGroup
import kotlinx.collections.immutable.toImmutableList

/**
 * letta-mobile-bglj6.1: consecutive tool-only messages of one run, folded into ONE tool summary
 * line ("Ran 3 commands", "3 ran - 1 failed"), the same line a run block draws for its calls.
 * Tapping it opens the calls in full.
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
    ToolRunGroup(
        toolCalls = toolCalls,
        context = contexts.forItem(group.singles.last()),
        callbacks = callbacks,
        approvals = approvals,
        startedAtTimestamp = group.singles.first().message.timestamp.takeIf { it.isNotBlank() },
        modifier = modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth(),
    )
}
