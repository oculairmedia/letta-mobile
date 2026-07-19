package com.letta.mobile.desktop.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.diff.DiffLine
import com.letta.mobile.data.diff.DiffLineKind
import com.letta.mobile.data.diff.UnifiedDiff
import com.letta.mobile.ui.theme.customColors

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
    val text = params.text
    val isError = params.isError
    // Unified diffs (file-edit tool output) render as a reviewable diff block
    // (Penpot "Diff review") rather than plain monospace lines.
    if (!isError && UnifiedDiff.looksLikeDiff(text)) {
        DiffBlock(text)
        return
    }
    // The "Tool error + retry" board renders failed output on a dark-red inset
    // instead of the neutral surface, so the failure reads at a glance.
    val blockColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = blockColor,
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                text.trim().lineSequence().take(40).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (isError) MaterialTheme.colorScheme.error else outputLineColor(OutputLine(line)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

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
