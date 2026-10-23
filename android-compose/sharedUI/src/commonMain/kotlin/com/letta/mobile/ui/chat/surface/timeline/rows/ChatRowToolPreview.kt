package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_tool_full_output
import com.letta.mobile.sharedui.resources.rows_tool_open_full_output
import com.letta.mobile.sharedui.resources.rows_tool_open_subagent
import com.letta.mobile.ui.chat.tools.LocalToolPresenters
import com.letta.mobile.ui.chat.tools.title
import com.letta.mobile.ui.host.InspectorPane
import com.letta.mobile.ui.shell.pages.workspace.MemoryPreview
import com.letta.mobile.ui.shell.pages.workspace.ToolPreviewFileChip
import com.letta.mobile.ui.shell.pages.workspace.WorkspaceFilePreview
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/** The host's [MemoryPreview] of tool steps; null where it has no Memory view, and memory steps show no chip. */
val LocalMemoryPreview = staticCompositionLocalOf<MemoryPreview<UiToolCall>?> { null }

/**
 * The detail panes tool steps open, drawn once per timeline where the window keeps its detail
 * panes: the workspace sidebar on a wide window, a bottom card on a phone. [calls] are the
 * conversation's tool calls, read only while a memory entry is open, for its earlier edits.
 */
@Composable
internal fun ToolPreviewPanes(calls: () -> List<UiToolCall>) {
    WorkspaceFilePreview()
    val memory = LocalMemoryPreview.current ?: return
    val presenters = LocalToolPresenters.current
    // Read only while an entry is open: the steps that touched it, newest first.
    memory.Pane(
        edits = { target -> calls().filter { target in presenters.line(it.facts()).previews }.asReversed() },
        draw = { call -> MemoryEditStep(call) },
    )
}

/** One memory step in the Memory view's inspector: its line, then the inset of what it changed. */
@Composable
private fun MemoryEditStep(call: UiToolCall) {
    val line = rememberToolStepLine(call)
    val presenters = LocalToolPresenters.current
    val inset = remember(call, presenters) { presenters.inset(call.facts()) }
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        Text(
            text = listOf(line.title(), line.target).filter { it.isNotBlank() }.joinToString(" "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        inset?.let { ToolInsetView(it) }
    }
}

/**
 * The chips of what a step's presenter said it touched, each routed to where it opens: a file to
 * the file preview (scrolled to its lines), a memory block or MemFS entry to the host's Memory
 * view, a subagent to the host's subagent view. A target the host cannot open shows no chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolPreviewChips(toolCall: UiToolCall, line: ToolStepLine, callbacks: ChatRowCallbacks) {
    if (line.previews.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        line.previews.forEach { target -> ToolPreviewChip(toolCall, line, target, callbacks) }
    }
}

@Composable
private fun ToolPreviewChip(toolCall: UiToolCall, line: ToolStepLine, target: ToolPreviewTarget, callbacks: ChatRowCallbacks) {
    when (target) {
        is ToolPreviewTarget.File -> ToolPreviewFileChip(target)
        is ToolPreviewTarget.Memory, is ToolPreviewTarget.MemoryBlock -> LocalMemoryPreview.current?.Chip(target, toolCall)
        is ToolPreviewTarget.Subagent -> {
            val open = callbacks.openSubagent ?: return
            val callId = toolCall.toolCallId ?: return
            AssistChip(
                onClick = { open(ChatSubagentTarget(callId, line.target.ifBlank { toolCall.name }, target.agentId)) },
                label = { Text(stringResource(Res.string.rows_tool_open_subagent)) },
                modifier = Modifier.testTag(ChatRowTestTags.TOOL_PREVIEW_CHIP),
            )
        }
        // No host opens a task's output from a step yet; its presenter's chip waits for one.
        is ToolPreviewTarget.TaskOutput -> Unit
    }
}

/** "Open full output", which shows all of [text] in a detail pane titled with the step's name. */
@Composable
internal fun FullOutputLink(onOpen: () -> Unit) {
    TextButton(onClick = onOpen, modifier = Modifier.testTag(ChatRowTestTags.TOOL_OPEN_FULL_OUTPUT)) {
        Text(stringResource(Res.string.rows_tool_open_full_output), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * All of an output, a line per row so only the rows on screen are laid out, in the window's
 * detail pane. Its lines are split only once the pane is open.
 */
@Composable
internal fun FullOutputPane(text: String, onClose: () -> Unit) {
    val lines = remember(text) { text.lines() }
    InspectorPane(title = stringResource(Res.string.rows_tool_full_output), onClose = onClose, testTag = ChatRowTestTags.TOOL_FULL_OUTPUT) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.xl)) {
                itemsIndexed(lines) { _, line ->
                    Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}

