package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.diff.UnifiedDiff
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.runtime.ApprovalDiffPreview
import com.letta.mobile.runtime.PermissionSuggestion
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_approval_always_allow
import com.letta.mobile.sharedui.resources.rows_approval_blocked_path
import com.letta.mobile.sharedui.resources.rows_approval_diff_show_all
import com.letta.mobile.sharedui.resources.rows_approval_diff_stats
import com.letta.mobile.sharedui.resources.rows_approval_diff_unavailable
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

// letta-mobile-bzvro.11 / .12: what the server's can_use_tool control request offered, drawn inside
// the approval card: the path that was blocked, the change the call would make, and the reusable
// "always allow" rules.

/** Diff lines shown before "Show all". */
private const val DIFF_COLLAPSED_LINES = 40

/** The blocked path and the previewed diffs of [details]. */
@Composable
internal fun ApprovalOfferedDetails(details: PendingApprovalDetails) {
    details.blockedPath?.let { path ->
        Text(
            text = stringResource(Res.string.rows_approval_blocked_path, path),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(ChatRowTestTags.APPROVAL_BLOCKED_PATH),
        )
    }
    details.diffs.forEach { ApprovalDiffPreviewBlock(it) }
}

/** One file's change: its path with +/- counts over the shared [DiffBlock], long diffs collapsed. */
@Composable
internal fun ApprovalDiffPreviewBlock(preview: ApprovalDiffPreview) {
    val diff = preview.unifiedDiff
    val stats = remember(diff) { diff?.let { UnifiedDiff.stats(UnifiedDiff.parse(it)) } }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.APPROVAL_DIFF),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        val title = preview.path
        if (title != null && stats != null) {
            Text(
                text = stringResource(Res.string.rows_approval_diff_stats, title, stats.first, stats.second),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (title != null) {
            Text(text = title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
        if (diff != null) {
            CollapsibleDiff(diff)
        } else {
            Text(
                text = preview.note ?: stringResource(Res.string.rows_approval_diff_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CollapsibleDiff(diff: String) {
    var expanded by remember(diff) { mutableStateOf(false) }
    val lineCount = remember(diff) { diff.count { it == '\n' } + 1 }
    val shown = remember(diff, expanded) {
        if (expanded || lineCount <= DIFF_COLLAPSED_LINES) diff else diff.lineSequence().take(DIFF_COLLAPSED_LINES).joinToString("\n")
    }
    DiffBlock(shown)
    if (!expanded && lineCount > DIFF_COLLAPSED_LINES) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(ChatRowTestTags.APPROVAL_DIFF_SHOW_ALL),
        ) {
            Text(stringResource(Res.string.rows_approval_diff_show_all, lineCount))
        }
    }
}

/**
 * One button per server-offered rule: approves the call and persists the rule. Absent while the
 * owner cannot take approvals.
 */
@Composable
internal fun AlwaysAllowButtons(
    suggestions: List<PermissionSuggestion>,
    decider: ApprovalDecider,
    toolCallIds: List<String>,
) {
    val submit = decider.submitAlwaysAllow ?: return
    if (suggestions.isEmpty()) return
    val haptics = LocalHaptics.current
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        suggestions.forEach { suggestion ->
            OutlinedButton(
                onClick = {
                    haptics.play(LettaHapticCue.Confirm)
                    submit(toolCallIds, suggestion.id)
                },
                enabled = decider.enabled,
                modifier = Modifier.fillMaxWidth().testTag("${ChatRowTestTags.APPROVAL_ALWAYS_ALLOW}-${suggestion.id}"),
            ) {
                Text(
                    text = stringResource(Res.string.rows_approval_always_allow, suggestion.text),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
