package com.letta.mobile.ui.shell.pages.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.workspace.WorkspaceFileContent
import com.letta.mobile.data.workspace.WorkspaceFileViewerActions
import com.letta.mobile.data.workspace.WorkspaceFileViewerState
import com.letta.mobile.data.workspace.WorkspacePaths
import com.letta.mobile.ui.chat.render.ToolOutputHighlightKind
import com.letta.mobile.ui.chat.render.ToolOutputHighlightMode
import com.letta.mobile.ui.chat.render.ToolOutputSyntaxColors
import com.letta.mobile.ui.chat.render.cachedToolOutputHighlightSpans
import com.letta.mobile.ui.chat.render.toolOutputSyntaxColors
import com.letta.mobile.ui.host.InspectorPane
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The read-only file viewer shared by every host: a workspace file with line numbers and syntax
 * colours, the lines a tool step touched highlighted and scrolled to, or why it cannot be shown
 * (binary, too large, unreadable). It draws nothing while [state] has no file open.
 *
 * It opens where the window keeps its detail panes ([InspectorPane]): the workspace sidebar on a
 * wide window, a bottom card on a phone.
 */
@Composable
fun WorkspaceFileViewer(
    state: WorkspaceFileViewerState,
    actions: WorkspaceFileViewerActions,
    modifier: Modifier = Modifier,
) {
    val path = state.path ?: return
    InspectorPane(
        title = WorkspacePaths.fileName(path),
        onClose = actions::close,
        modifier = modifier,
        testTag = WorkspaceFileViewerTags.VIEWER,
    ) {
        Text(
            path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.xl),
        )
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = LettaDimens.Space.xs))
        FileViewerBody(state, actions)
    }
}

/** Test tags for the shared file viewer. */
object WorkspaceFileViewerTags {
    const val VIEWER = "workspace_file_viewer"
    const val TEXT = "workspace_file_viewer_text"
    const val PLACEHOLDER = "workspace_file_viewer_placeholder"
    const val RETRY = "workspace_file_viewer_retry"
}

@Composable
private fun FileViewerBody(state: WorkspaceFileViewerState, actions: WorkspaceFileViewerActions) {
    val error = state.error
    when (val content = state.content) {
        is WorkspaceFileContent.Text -> HighlightedFile(content, state.lines)
        is WorkspaceFileContent.Binary -> Placeholder(BINARY_MESSAGE)
        is WorkspaceFileContent.TooLarge -> Placeholder("This file is too large to show here (${content.sizeChars} characters).")
        null -> if (error != null) {
            Placeholder(error) {
                OutlinedButton(onClick = actions::retry, modifier = Modifier.testTag(WorkspaceFileViewerTags.RETRY)) { Text(RETRY_LABEL) }
            }
        }
    }
}

/** The file with a line gutter; [lines] (1-based) are washed and scrolled to once the text is laid out. */
@Composable
private fun HighlightedFile(content: WorkspaceFileContent.Text, lines: IntRange?) {
    val colors = toolOutputSyntaxColors(isError = false)
    val wash = MaterialTheme.colorScheme.primary.copy(alpha = LINE_WASH_ALPHA)
    val lineCount = remember(content.text) { content.text.count { it == '\n' } + 1 }
    val gutter = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val highlighted = remember(content, colors, lines, wash) { highlight(content, colors, lines, wash) }
    val codeStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(layout, lines) {
        val first = lines?.first ?: return@LaunchedEffect
        val laidOut = layout ?: return@LaunchedEffect
        scroll.scrollTo(laidOut.getLineTop((first - 1).coerceIn(0, laidOut.lineCount - 1)).toInt())
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(LettaDimens.Space.md)
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
            .verticalScroll(scroll)
            .padding(LettaDimens.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Text(gutter, style = codeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        SelectionContainer(Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                highlighted,
                style = codeStyle,
                softWrap = false,
                onTextLayout = { layout = it },
                modifier = Modifier.testTag(WorkspaceFileViewerTags.TEXT),
            )
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

/** The file's text with the shared tool-output highlighter's colours, and [lines] washed in [wash]. */
private fun highlight(content: WorkspaceFileContent.Text, colors: ToolOutputSyntaxColors, lines: IntRange?, wash: Color): AnnotatedString {
    val hint = WorkspacePaths.languageHint(content.path)
    val mode = if (hint == JSON_HINT) ToolOutputHighlightMode.Json else ToolOutputHighlightMode.Code
    val spans = cachedToolOutputHighlightSpans(content.text, mode, hint)
    return buildAnnotatedString {
        append(content.text)
        spans.forEach { span -> addStyle(SpanStyle(color = colors.of(span.kind)), span.start, span.end) }
        lines?.let { lineSpan(content.text, it) }?.let { (start, end) -> addStyle(SpanStyle(background = wash), start, end) }
    }
}

/** Where 1-based [lines] start and end in [text]; null when they are past its end. */
internal fun lineSpan(text: String, lines: IntRange): Pair<Int, Int>? {
    var start = 0
    repeat(lines.first - 1) {
        val newline = text.indexOf('\n', start)
        if (newline < 0) return null
        start = newline + 1
    }
    var end = start
    repeat(lines.last - lines.first + 1) {
        val newline = text.indexOf('\n', end)
        end = if (newline < 0) text.length else newline + 1
    }
    return start to end.coerceAtMost(text.length)
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
private const val RETRY_LABEL = "Try again"
private const val LINE_WASH_ALPHA = 0.16f
private const val BINARY_MESSAGE = "This file is not text, so it can't be shown here."
