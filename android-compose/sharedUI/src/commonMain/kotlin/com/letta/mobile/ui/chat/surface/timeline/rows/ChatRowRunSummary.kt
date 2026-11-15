package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.runtime.Composable
import com.letta.mobile.data.chat.projection.RunActivityProjection
import com.letta.mobile.data.chat.projection.RunActivityState
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_run_failed
import com.letta.mobile.sharedui.resources.rows_run_failed_of
import com.letta.mobile.sharedui.resources.rows_run_for_duration
import com.letta.mobile.sharedui.resources.rows_run_state_working
import com.letta.mobile.sharedui.resources.rows_run_summary_join
import com.letta.mobile.sharedui.resources.rows_run_thought
import com.letta.mobile.sharedui.resources.rows_run_step_count
import com.letta.mobile.sharedui.resources.rows_run_worked
import com.letta.mobile.sharedui.resources.rows_run_working
import com.letta.mobile.sharedui.resources.run_status_approval
import com.letta.mobile.ui.components.SummaryLine
import com.letta.mobile.ui.components.line.LineDisclosure
import com.letta.mobile.ui.components.line.LineRail
import com.letta.mobile.ui.components.line.LineSpec
import com.letta.mobile.ui.components.line.LineTags
import com.letta.mobile.ui.components.line.StatusTone
import com.letta.mobile.ui.theme.ChatRowSpacing
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A run's summary as ONE line, the way ChatGPT ("Thought for 8s ›"),
 * Perplexity ("1 step completed ›") and v0 (glyph + "Explored project structure ›") draw an agent's
 * work: a small status glyph, one muted label ("Worked for 10.8s · 1 step", "Working…", "Failed · 1 of 3",
 * "Waiting for your approval") and, when the run folds, a single trailing chevron. The whole line is
 * the hit target. The run's steps (its reasoning and its tool step rows) list under it only
 * once it is opened, so a folded run never shows a second stray line with a chevron of its own.
 * It is the shared [SummaryLine] on the header rail: every notice between runs draws the same line.
 */
@Composable
internal fun RunSummaryLine(state: RunHeaderState) {
    val fold = state.fold
    val tone = runSummaryTone(state)
    SummaryLine(
        spec = LineSpec(
            title = runSummaryTitle(state.activity, tone),
            tone = tone,
            tags = LineTags(row = ChatRowTestTags.RUN_HEADER, glyph = ChatRowTestTags.RUN_GLYPH),
        ),
        disclosure = fold?.let { LineDisclosure(expanded = !it.collapsed, labels = RunDisclosureLabels, onToggle = it.toggle) },
        rail = LineRail.Header,
        stateDescription = if (state.activity.isActive) stringResource(Res.string.rows_run_state_working) else null,
        // The run's body lifts by this floor (runHeaderFloor), so the header keeps its own.
        minHeight = if (state.tall) ChatRowSpacing.runHeaderToggleMinHeight else ChatRowSpacing.runHeaderMinHeight,
    )
}

internal fun runSummaryTone(state: RunHeaderState): StatusTone = when {
    state.awaitingApproval -> StatusTone.Attention
    state.activity.isActive -> StatusTone.Working
    state.activity.failureCount > 0 -> StatusTone.Failed
    else -> StatusTone.Done
}

/** The line's words: its state, then (settled) how long it took and how many tools it ran. */
@Composable
private fun runSummaryTitle(activity: RunActivityProjection, tone: StatusTone): String {
    // Formatting the join with two empty parts yields the bare separator (" · ").
    val separator = stringResource(Res.string.rows_run_summary_join, "", "")
    val parts = when (tone) {
        StatusTone.Attention -> listOf(stringResource(Res.string.run_status_approval))
        StatusTone.Working -> listOf(stringResource(Res.string.rows_run_working))
        StatusTone.Failed -> failedParts(activity)
        else -> doneParts(activity)
    }
    return parts.joinToString(separator)
}

/** "Failed · 1 of 3": how many of the run's tool calls failed. */
@Composable
private fun failedParts(activity: RunActivityProjection): List<String> {
    val failed = stringResource(Res.string.rows_run_failed)
    if (activity.toolCount == 0) return listOf(failed)
    return listOf(failed, stringResource(Res.string.rows_run_failed_of, activity.failureCount, activity.toolCount))
}

/** "Worked for 2m · 3 steps" (or "Thought for 2.1s" for a run that only reasoned). */
@Composable
private fun doneParts(activity: RunActivityProjection): List<String> {
    val verb = stringResource(if (onlyThought(activity)) Res.string.rows_run_thought else Res.string.rows_run_worked)
    val worked = activity.durationMs?.let { stringResource(Res.string.rows_run_for_duration, verb, formatRunDuration(it)) } ?: verb
    val steps = activity.toolCount.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.rows_run_step_count, it, it) }
    return listOfNotNull(worked, steps)
}

/** A run that reasoned and ran no tools "Thought"; one that ran tools "Worked", reasoning or not. */
private fun onlyThought(activity: RunActivityProjection): Boolean {
    if (activity.toolCount > 0) return false
    return activity.state == RunActivityState.Thought
}
