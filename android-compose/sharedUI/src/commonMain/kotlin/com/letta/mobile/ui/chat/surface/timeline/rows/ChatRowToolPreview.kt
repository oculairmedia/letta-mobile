package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.letta.mobile.data.chat.tools.links.OutputLinks
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.workspace.WorkspaceFileOpener
import com.letta.mobile.data.workspace.WorkspaceFileRequest
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_tool_full_output
import com.letta.mobile.sharedui.resources.rows_tool_open_full_output
import com.letta.mobile.sharedui.resources.rows_tool_open_subagent
import com.letta.mobile.ui.host.InspectorPane
import com.letta.mobile.ui.shell.pages.workspace.LocalWorkspaceFiles
import com.letta.mobile.ui.shell.pages.workspace.WorkspaceFileChip
import com.letta.mobile.ui.shell.pages.workspace.WorkspaceFilePreview
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * The detail panes tool steps open, drawn once per timeline where the window keeps its detail
 * panes: the workspace sidebar on a wide window, a bottom card on a phone.
 */
@Composable
internal fun ToolPreviewPanes() {
    WorkspaceFilePreview()
}

/**
 * The chips of what a step's presenter said it touched, each routed to where it opens: a file or
 * a memory entry to the file preview (scrolled to its lines), a subagent to the host's subagent
 * view. A target the host cannot open shows no chip.
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
        is ToolPreviewTarget.File -> WorkspaceFileChip(WorkspaceFileRequest(target.path, lines = target.lines))
        is ToolPreviewTarget.Memory -> WorkspaceFileChip(WorkspaceFileRequest(target.path, memoryTool = true))
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

/**
 * [text] with its URLs and file paths as links: a URL opens in the browser, a path (with an
 * optional `:line`) in the file preview. Only a step's kept window is linked, never a whole output.
 */
@Composable
internal fun rememberLinkedText(text: String): AnnotatedString {
    val files = LocalWorkspaceFiles.current
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, files, linkColor) {
        val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            var at = 0
            for (match in OutputLinks.find(text)) {
                append(text, at, match.range.first)
                val link = outputLink(match, files?.opener, styles)
                if (link == null) append(match.value) else withLink(link) { append(match.value) }
                at = match.range.last + 1
            }
            append(text, at, text.length)
        }
    }
}

private fun outputLink(
    match: OutputLinks.Match,
    opener: WorkspaceFileOpener?,
    styles: TextLinkStyles,
): LinkAnnotation? = when (match) {
    is OutputLinks.Match.Url -> LinkAnnotation.Url(match.value, styles)
    is OutputLinks.Match.Path -> opener?.let { files ->
        LinkAnnotation.Clickable(match.value, styles) { files.open(WorkspaceFileRequest(match.path, lines = match.line?.let { it..it })) }
    }
}
