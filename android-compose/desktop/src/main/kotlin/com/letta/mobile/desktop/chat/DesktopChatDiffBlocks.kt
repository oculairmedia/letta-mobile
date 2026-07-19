package com.letta.mobile.desktop.chat

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.diff.DiffLine
import com.letta.mobile.data.diff.DiffLineKind
import com.letta.mobile.data.diff.UnifiedDiff
import com.letta.mobile.ui.theme.customColors
import kotlinx.coroutines.launch

internal data class ToolOutputBlockParams(
    val text: String,
    val isError: Boolean = false,
)

/** Inset output block (monospace) with light per-line colorization. */
@Composable
internal fun ToolOutputBlock(text: String, isError: Boolean = false) =
    ToolOutputBlock(ToolOutputBlockParams(text = text, isError = isError))

@Composable
internal fun ToolOutputBlock(params: ToolOutputBlockParams) {
    // Unified diffs (file-edit tool output) render as a reviewable diff block
    // (Penpot "Diff review") rather than plain monospace lines.
    if (!params.isError && UnifiedDiff.looksLikeDiff(params.text)) {
        DiffBlock(params.text)
        return
    }
    PlainToolOutputBlock(params)
}

@Composable
private fun PlainToolOutputBlock(params: ToolOutputBlockParams) {
    // The "Tool error + retry" board renders failed output on a dark-red inset
    // instead of the neutral surface, so the failure reads at a glance.
    val blockColor = if (params.isError) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val outputLines = remember(params.text) { params.text.trim().lines() }
    val visibleLines = remember(outputLines) { outputLines.take(TOOL_OUTPUT_VISIBLE_LINE_LIMIT) }
    val horizontalScrollState = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = blockColor,
    ) {
        ToolOutputContents(visibleLines, outputLines.size, params.isError, horizontalScrollState)
    }
}

@Composable
private fun ToolOutputContents(
    visibleLines: List<String>,
    totalLineCount: Int,
    isError: Boolean,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
) {
    Column {
        ToolOutputViewport(visibleLines, isError, horizontalScrollState)
        if (totalLineCount > visibleLines.size) ToolOutputTruncationLabel(visibleLines.size, totalLineCount)
    }
}

@Composable
private fun ToolOutputViewport(
    visibleLines: List<String>,
    isError: Boolean,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
) {
    val scrollScope = rememberCoroutineScope()
    Box {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .toolOutputKeyboardScroll(horizontalScrollState) { destination ->
                        scrollScope.launch { horizontalScrollState.scrollTo(destination) }
                    }
                    .focusable()
                    .horizontalScroll(horizontalScrollState)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                visibleLines.forEach { line -> ToolOutputLine(line, isError) }
            }
        }
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(horizontalScrollState),
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(10.dp).testTag("tool-output-scrollbar"),
        )
    }
}

private fun Modifier.toolOutputKeyboardScroll(
    state: androidx.compose.foundation.ScrollState,
    scrollTo: (Int) -> Unit,
): Modifier = semantics {
    contentDescription = "Tool output. Use left and right arrow keys to scroll horizontally."
}.onPreviewKeyEvent { event ->
    val delta = event.toolOutputScrollDelta() ?: return@onPreviewKeyEvent false
    scrollTo((state.value + delta).coerceIn(0, state.maxValue))
    true
}

private fun androidx.compose.ui.input.key.KeyEvent.toolOutputScrollDelta(): Int? {
    if (type != KeyEventType.KeyDown) return null
    return when (key) {
        Key.DirectionLeft -> -TOOL_OUTPUT_KEYBOARD_SCROLL_PX
        Key.DirectionRight -> TOOL_OUTPUT_KEYBOARD_SCROLL_PX
        else -> null
    }
}

@Composable
private fun ToolOutputLine(line: String, isError: Boolean) {
    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = if (isError) MaterialTheme.colorScheme.error else outputLineColor(OutputLine(line)),
        maxLines = 1,
    )
}

@Composable
private fun ToolOutputTruncationLabel(visibleLineCount: Int, totalLineCount: Int) {
    Text(
        text = "Showing $visibleLineCount of $totalLineCount lines · Copy includes all output",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private const val TOOL_OUTPUT_VISIBLE_LINE_LIMIT = 40
private const val TOOL_OUTPUT_KEYBOARD_SCROLL_PX = 64

/**
 * Renders a unified diff (Penpot "Diff review"): a line-numbered gutter (old |
 * new) with red removed rows, green added rows, and muted hunk headers. Parsing
 * is shared via [UnifiedDiff]; git metadata (diff/index/--- /+++) is dropped.
 */
@Composable
internal fun DiffBlock(text: String) {
    val lines = remember(text) {
        UnifiedDiff.parse(text).filterNot { it.kind == DiffLineKind.FileHeader }
    }
    val added = Color(0xFF2EA043)
    val removed = Color(0xFFE5484D)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                lines.take(200).forEach { line ->
                    DiffBlockRow(line = line, added = added, removed = removed)
                }
            }
        }
    }
}

@Composable
private fun DiffBlockRow(
    line: DiffLine,
    added: Color,
    removed: Color,
) {
    val (rowColor, marker, textColor) = when (line.kind) {
        DiffLineKind.Added -> Triple(added.copy(alpha = 0.12f), "+", added)
        DiffLineKind.Removed -> Triple(removed.copy(alpha = 0.12f), "-", removed)
        DiffLineKind.Hunk -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
            "",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Triple(Color.Transparent, "", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowColor)
            .padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiffGutter(line.oldLine)
        DiffGutter(line.newLine)
        Text(
            text = marker,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = textColor,
            modifier = Modifier.width(12.dp),
        )
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun DiffGutter(lineNumber: Int?) {
    Text(
        text = lineNumber?.toString().orEmpty(),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        maxLines = 1,
        modifier = Modifier.width(34.dp).padding(end = 6.dp),
    )
}

@Composable
internal fun outputLineColor(line: OutputLine): Color {
    val success = MaterialTheme.customColors.successColor.takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return when {
        isUnifiedDiffAddedLine(line) -> success
        isUnifiedDiffRemovedLine(line) -> error
        isSuccessOutputLine(line) -> success
        isFailureOutputLine(line) -> error
        else -> muted
    }
}
