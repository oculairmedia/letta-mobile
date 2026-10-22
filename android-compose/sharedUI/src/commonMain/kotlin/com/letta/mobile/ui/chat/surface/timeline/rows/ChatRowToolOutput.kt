package com.letta.mobile.ui.chat.surface.timeline.rows

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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import com.letta.mobile.data.diff.DiffLine
import com.letta.mobile.data.diff.DiffLineKind
import com.letta.mobile.data.diff.UnifiedDiff
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_diff_not_shown
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

// letta-mobile-bglj6.1: lifted from desktop's DesktopChatDiffBlocks.kt, the diff's hard-coded
// greens/reds as theme roles. Rows wrap: a tool step's inset never scrolls sideways.

/**
 * A unified diff: an old|new line-number gutter, tinted added/removed rows and muted hunk
 * headers. Git metadata (diff/index/---/+++) is dropped.
 */
@Composable
internal fun DiffBlock(text: String, maxRows: Int = ChatRowDimens.diffVisibleLines) {
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
                lines.take(maxRows).forEach { DiffBlockRow(it) }
                if (lines.size > maxRows) {
                    Text(
                        text = stringResource(Res.string.rows_diff_not_shown, lines.size - maxRows),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
                    )
                }
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
        verticalAlignment = Alignment.Top,
    ) {
        DiffGutter(line.oldLine)
        DiffGutter(line.newLine)
        Text(text = style.marker, style = mono, color = style.text, modifier = Modifier.width(LettaDimens.Space.md))
        Text(
            text = line.text,
            style = mono,
            color = style.text,
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
