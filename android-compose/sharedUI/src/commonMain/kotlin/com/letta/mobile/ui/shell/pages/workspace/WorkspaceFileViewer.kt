package com.letta.mobile.ui.shell.pages.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.letta.mobile.data.workspace.WorkspaceFileContent
import com.letta.mobile.data.workspace.WorkspaceFileViewerActions
import com.letta.mobile.data.workspace.WorkspaceFileViewerState
import com.letta.mobile.data.workspace.WorkspacePaths
import com.letta.mobile.ui.chat.render.ToolOutputHighlightKind
import com.letta.mobile.ui.chat.render.ToolOutputHighlightMode
import com.letta.mobile.ui.chat.render.ToolOutputSyntaxColors
import com.letta.mobile.ui.chat.render.cachedToolOutputHighlightSpans
import com.letta.mobile.ui.chat.render.toolOutputSyntaxColors
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The read-only file viewer shared by desktop and Android (letta-mobile-bzvro.26): a workspace
 * file with line numbers and syntax colours, or why it cannot be shown (binary, too large,
 * unreadable). It draws nothing while [state] has no file open.
 *
 * In a wide window ([LettaDimens.Pane.wideBreakpoint] and up) it is a dialog over the page; on a
 * phone, a bottom sheet.
 */
@Composable
fun WorkspaceFileViewer(
    state: WorkspaceFileViewerState,
    actions: WorkspaceFileViewerActions,
    modifier: Modifier = Modifier,
    options: WorkspaceFileViewerOptions = WorkspaceFileViewerOptions(),
) {
    if (!state.isOpen) return
    BoxWithConstraints(modifier) {
        if (maxWidth >= LettaDimens.Pane.wideBreakpoint && !options.forceSheet) {
            Dialog(onDismissRequest = actions::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.widthIn(max = DialogMaxWidth).heightIn(max = DialogMaxHeight).padding(LettaDimens.Space.xl),
                ) { FileViewerContent(state, actions) }
            }
        } else {
            ModalBottomSheet(onDismissRequest = actions::close) { FileViewerContent(state, actions) }
        }
    }
}

/** Host presentation choices: [forceSheet] uses the sheet even in a wide window. */
@Immutable
data class WorkspaceFileViewerOptions(val forceSheet: Boolean = false)

/** Test tags for the shared file viewer. */
object WorkspaceFileViewerTags {
    const val VIEWER = "workspace_file_viewer"
    const val CLOSE = "workspace_file_viewer_close"
    const val TEXT = "workspace_file_viewer_text"
    const val PLACEHOLDER = "workspace_file_viewer_placeholder"
    const val RETRY = "workspace_file_viewer_retry"
}

@Composable
private fun FileViewerContent(state: WorkspaceFileViewerState, actions: WorkspaceFileViewerActions) {
    val path = state.path.orEmpty()
    Column(Modifier.fillMaxWidth().testTag(WorkspaceFileViewerTags.VIEWER)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = LettaDimens.Space.lg, end = LettaDimens.Space.xs, top = LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(WorkspacePaths.fileName(path), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = actions::close, modifier = Modifier.testTag(WorkspaceFileViewerTags.CLOSE)) {
                Icon(Icons.Outlined.Close, contentDescription = CLOSE_LABEL)
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = LettaDimens.Space.xs))
        FileViewerBody(state, actions)
    }
}

@Composable
private fun FileViewerBody(state: WorkspaceFileViewerState, actions: WorkspaceFileViewerActions) {
    val error = state.error
    when (val content = state.content) {
        is WorkspaceFileContent.Text -> HighlightedFile(content)
        is WorkspaceFileContent.Binary -> Placeholder(BINARY_MESSAGE)
        is WorkspaceFileContent.TooLarge -> Placeholder("This file is too large to show here (${content.sizeChars} characters).")
        null -> if (error != null) {
            Placeholder(error) {
                OutlinedButton(onClick = actions::retry, modifier = Modifier.testTag(WorkspaceFileViewerTags.RETRY)) { Text(RETRY_LABEL) }
            }
        }
    }
}

@Composable
private fun HighlightedFile(content: WorkspaceFileContent.Text) {
    val colors = toolOutputSyntaxColors(isError = false)
    val lines = remember(content.text) { content.text.split('\n') }
    val gutter = remember(lines.size) { lines.indices.joinToString("\n") { (it + 1).toString() } }
    val highlighted = remember(content, colors) { highlight(content, colors) }
    val codeStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(LettaDimens.Space.md)
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
            .verticalScroll(rememberScrollState())
            .padding(LettaDimens.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Text(gutter, style = codeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        SelectionContainer(Modifier.horizontalScroll(rememberScrollState())) {
            Text(highlighted, style = codeStyle, softWrap = false, modifier = Modifier.testTag(WorkspaceFileViewerTags.TEXT))
        }
    }
}

@Composable
private fun Placeholder(message: String, actions: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().padding(LettaDimens.Space.xl).testTag(WorkspaceFileViewerTags.PLACEHOLDER), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            actions()
        }
    }
}

/** The file's text with the shared tool-output highlighter's colours. */
private fun highlight(content: WorkspaceFileContent.Text, colors: ToolOutputSyntaxColors): AnnotatedString {
    val hint = WorkspacePaths.languageHint(content.path)
    val mode = if (hint == JSON_HINT) ToolOutputHighlightMode.Json else ToolOutputHighlightMode.Code
    val spans = cachedToolOutputHighlightSpans(content.text, mode, hint)
    return buildAnnotatedString {
        append(content.text)
        spans.forEach { span -> addStyle(SpanStyle(color = colors.of(span.kind)), span.start, span.end) }
    }
}

private fun ToolOutputSyntaxColors.of(kind: ToolOutputHighlightKind) = when (kind) {
    ToolOutputHighlightKind.Key -> key
    ToolOutputHighlightKind.StringLiteral -> stringLiteral
    ToolOutputHighlightKind.Number -> number
    ToolOutputHighlightKind.Literal -> literal
    ToolOutputHighlightKind.Keyword -> keyword
    ToolOutputHighlightKind.Function -> function
    ToolOutputHighlightKind.Comment -> comment
    ToolOutputHighlightKind.Punctuation -> punctuation
    ToolOutputHighlightKind.Prompt -> prompt
    ToolOutputHighlightKind.Success -> success
    ToolOutputHighlightKind.Warning -> warning
    ToolOutputHighlightKind.Error -> error
    ToolOutputHighlightKind.Header -> header
}

private const val JSON_HINT = "json"
private const val CLOSE_LABEL = "Close file"
private const val RETRY_LABEL = "Try again"
private const val BINARY_MESSAGE = "This file is not text, so it can't be shown here."
private val DialogMaxWidth = LettaDimens.Pane.sidePanelWidth * 2
private val DialogMaxHeight = LettaDimens.Pane.sidePanelWidth * 2
