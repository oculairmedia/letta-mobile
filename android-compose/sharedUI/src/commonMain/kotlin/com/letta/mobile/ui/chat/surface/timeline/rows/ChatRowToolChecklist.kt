package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_checklist_current
import com.letta.mobile.sharedui.resources.rows_checklist_done
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * A checklist inset (a todo list), one row per item in order: a done item struck through and muted
 * after a `✓`, the current one after a small spinner, the rest after a `○`. Every mark sits in the
 * same fixed slot, so an item's text never moves when its state changes.
 */
@Composable
internal fun ChecklistInset(inset: ToolInset.Checklist) {
    inset.items.forEachIndexed { index, item -> ChecklistRow(item, current = index == inset.current) }
}

@Composable
private fun ChecklistRow(item: ToolInset.Checklist.Item, current: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val label = when {
        item.done -> stringResource(Res.string.rows_checklist_done, item.text)
        current -> stringResource(Res.string.rows_checklist_current, item.text)
        else -> item.text
    }
    Row(
        modifier = Modifier.testTag(ChatRowTestTags.TOOL_CHECKLIST_ITEM).semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Box(Modifier.size(ChatRowSpacing.runGlyphSize), contentAlignment = Alignment.Center) {
            when {
                item.done -> Text(DONE_MARK, style = MaterialTheme.typography.bodySmall, color = muted)
                current -> RunStatusGlyph(RunSummaryTone.Working, Modifier.testTag(ChatRowTestTags.TOOL_CHECKLIST_CURRENT))
                else -> Text(PENDING_MARK, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodySmall.copy(textDecoration = if (item.done) TextDecoration.LineThrough else null),
            color = if (item.done) muted else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val DONE_MARK = "✓"
private const val PENDING_MARK = "○"
