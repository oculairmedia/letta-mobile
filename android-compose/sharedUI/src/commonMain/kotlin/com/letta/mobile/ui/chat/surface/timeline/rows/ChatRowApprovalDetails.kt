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
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_approval_always_allow
import com.letta.mobile.sharedui.resources.rows_approval_blocked_path
import com.letta.mobile.sharedui.resources.rows_approval_diff_show_more
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

/** Further diff lines revealed per "Show more" press. */
private const val DIFF_PAGE_LINES = 200

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
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (diff != null) {
            CollapsibleDiff(diff)
        } else {
            Text(
                text = preview.note ?: stringResource(Res.string.rows_approval_diff_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The diff a person approves must be reachable in full, so it is paged: [DIFF_COLLAPSED_LINES] first,
 * then [DIFF_PAGE_LINES] more per press, until every line (up to the parse-time cap) has been shown.
 * Rows are composed only for what has been revealed, and what is still hidden is always counted.
 */
@Composable
private fun CollapsibleDiff(diff: String) {
    var revealed by remember(diff) { mutableStateOf(DIFF_COLLAPSED_LINES) }
    val lines = remember(diff) { diff.lines() }
    val shown = remember(diff, revealed) { if (revealed >= lines.size) diff else lines.take(revealed).joinToString("\n") }
    DiffBlock(shown, maxRows = Int.MAX_VALUE)
    val hidden = lines.size - revealed
    if (hidden > 0) {
        TextButton(
            onClick = { revealed += DIFF_PAGE_LINES },
            modifier = Modifier.testTag(ChatRowTestTags.APPROVAL_DIFF_SHOW_ALL),
        ) {
            Text(stringResource(Res.string.rows_approval_diff_show_more, hidden))
        }
    }
}

/**
 * One button per server-offered rule: approves the call and persists the rule. Absent while the
 * owner cannot take approvals.
 */
@Composable
internal fun AlwaysAllowButtons(
    details: PendingApprovalDetails,
    decider: ApprovalDecider,
) {
    val submit = decider.submitAlwaysAllow ?: return
    val suggestions = details.suggestions
    if (suggestions.isEmpty()) return
    val haptics = LocalHaptics.current
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        suggestions.forEach { suggestion ->
            OutlinedButton(
                onClick = {
                    haptics.play(LettaHapticCue.Confirm)
                    submit(details, suggestion.id)
                },
                enabled = decider.enabled,
                modifier = Modifier.fillMaxWidth().testTag("${ChatRowTestTags.APPROVAL_ALWAYS_ALLOW}-${suggestion.id}"),
            ) {
                // The whole rule is shown: a persisted permission must be readable in full before it is granted.
                Text(text = stringResource(Res.string.rows_approval_always_allow, suggestion.text))
            }
        }
    }
}
