package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.diff.DiffLine
import com.letta.mobile.data.diff.DiffLineKind
import com.letta.mobile.data.diff.UnifiedDiff
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_tool_output_scroll_hint
import com.letta.mobile.sharedui.resources.rows_tool_output_truncated
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

// letta-mobile-bglj6.1: lifted from desktop's DesktopChatDiffBlocks.kt. The desktop-only
// HorizontalScrollbar is dropped (keyboard and touch scrolling remain), and the diff's
// hard-coded greens/reds are theme roles.

/**
 * Inset monospace output with light per-line colouring. A unified diff (file-edit tool
 * output) renders as a reviewable [DiffBlock] instead.
 */
@Composable
internal fun ToolOutputBlock(text: String, isError: Boolean = false) {
    if (!isError && UnifiedDiff.looksLikeDiff(text)) {
        DiffBlock(text)
        return
    }
    val window = remember(text) { ToolOutputWindow.of(text) }
    val blockColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = ChatRowAlpha.errorInset)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.TOOL_OUTPUT),
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = blockColor,
    ) {
        Column {
            ToolOutputViewport(window.visibleLines, isError)
            if (window.isTruncated) {
                Text(
                    text = stringResource(
                        Res.string.rows_tool_output_truncated,
                        window.visibleLines.size,
                        window.totalLineCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
                )
            }
        }
    }
}

@Immutable
private data class ToolOutputWindow(val visibleLines: List<String>, val totalLineCount: Int) {
    val isTruncated: Boolean get() = totalLineCount > visibleLines.size

    companion object {
        fun of(text: String): ToolOutputWindow {
            val lines = text.trim().lines()
            return ToolOutputWindow(lines.take(ChatRowDimens.toolOutputVisibleLines), lines.size)
        }
    }
}

@Composable
private fun ToolOutputViewport(lines: List<String>, isError: Boolean) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val hint = stringResource(Res.string.rows_tool_output_scroll_hint)
    SelectionContainer {
        Column(
            modifier = Modifier
                .semantics { contentDescription = hint }
                .onPreviewKeyEvent { event ->
                    val destination = event.horizontalScrollDestination(scrollState) ?: return@onPreviewKeyEvent false
                    scope.launch { scrollState.scrollTo(destination) }
                    true
                }
                .focusable()
                .horizontalScroll(scrollState)
                .padding(LettaDimens.Space.md),
        ) {
            lines.forEach { line -> ToolOutputLine(line, isError) }
        }
    }
}

private fun KeyEvent.horizontalScrollDestination(state: ScrollState): Int? {
    if (type != KeyEventType.KeyDown) return null
    val delta = when (key) {
        Key.DirectionLeft -> -ChatRowDimens.toolOutputScrollStepPx
        Key.DirectionRight -> ChatRowDimens.toolOutputScrollStepPx
        else -> return null
    }
    return (state.value + delta).coerceIn(0, state.maxValue)
}

@Composable
private fun ToolOutputLine(line: String, isError: Boolean) {
    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = if (isError) MaterialTheme.colorScheme.error else outputLineColor(outputLineKind(line)),
        maxLines = 1,
    )
}

@Composable
private fun outputLineColor(kind: OutputLineKind): Color = when (kind) {
    OutputLineKind.Added, OutputLineKind.Success -> successTint()
    OutputLineKind.Removed, OutputLineKind.Failure -> MaterialTheme.colorScheme.error
    OutputLineKind.Plain -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * A unified diff: an old|new line-number gutter, tinted added/removed rows and muted hunk
 * headers. Git metadata (diff/index/---/+++) is dropped.
 */
@Composable
internal fun DiffBlock(text: String) {
    val lines = remember(text) {
        UnifiedDiff.parse(text).filterNot { it.kind == DiffLineKind.FileHeader }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.DIFF_BLOCK),
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(vertical = LettaDimens.Space.sm)) {
                lines.take(ChatRowDimens.diffVisibleLines).forEach { DiffBlockRow(it) }
            }
        }
    }
}

@Immutable
private data class DiffRowStyle(val background: Color, val marker: String, val text: Color)

@Composable
private fun diffRowStyle(kind: DiffLineKind): DiffRowStyle {
    val success = successTint()
    val error = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return when (kind) {
        DiffLineKind.Added -> DiffRowStyle(success.copy(alpha = ChatRowAlpha.diffRowTint), "+", success)
        DiffLineKind.Removed -> DiffRowStyle(error.copy(alpha = ChatRowAlpha.diffRowTint), "-", error)
        DiffLineKind.Hunk -> DiffRowStyle(MaterialTheme.colorScheme.surfaceContainerHigh, "", muted)
        else -> DiffRowStyle(Color.Transparent, "", muted)
    }
}

@Composable
private fun DiffBlockRow(line: DiffLine) {
    val style = diffRowStyle(line.kind)
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(style.background)
            .padding(horizontal = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiffGutter(line.oldLine)
        DiffGutter(line.newLine)
        Text(text = style.marker, style = mono, color = style.text, modifier = Modifier.width(LettaDimens.Space.md))
        Text(
            text = line.text,
            style = mono,
            color = style.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DiffGutter(lineNumber: Int?) {
    Text(
        text = lineNumber?.toString().orEmpty(),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.width(LettaDimens.Space.xxl).padding(end = LettaDimens.Space.sm),
    )
}
