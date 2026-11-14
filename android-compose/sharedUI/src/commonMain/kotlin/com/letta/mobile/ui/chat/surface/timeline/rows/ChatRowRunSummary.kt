package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.RunActivityProjection
import com.letta.mobile.data.chat.projection.RunActivityState
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_run_failed
import com.letta.mobile.sharedui.resources.rows_run_failed_of
import com.letta.mobile.sharedui.resources.rows_run_for_duration
import com.letta.mobile.sharedui.resources.rows_run_state_collapsed
import com.letta.mobile.sharedui.resources.rows_run_state_expanded
import com.letta.mobile.sharedui.resources.rows_run_state_working
import com.letta.mobile.sharedui.resources.rows_run_summary_join
import com.letta.mobile.sharedui.resources.rows_run_thought
import com.letta.mobile.sharedui.resources.rows_run_step_count
import com.letta.mobile.sharedui.resources.rows_run_worked
import com.letta.mobile.sharedui.resources.rows_run_working
import com.letta.mobile.sharedui.resources.run_status_approval
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowMotion
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.ChatRowType
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A run's summary as ONE line, the way ChatGPT ("Thought for 8s ›"),
 * Perplexity ("1 step completed ›") and v0 (glyph + "Explored project structure ›") draw an agent's
 * work: a small status glyph, one muted label ("Worked for 10.8s · 1 step", "Working…", "Failed · 1 of 3",
 * "Waiting for your approval") and, when the run folds, a single trailing chevron. The whole line is
 * the hit target. The run's steps (its reasoning and its tool step rows) list under it only
 * once it is opened, so a folded run never shows a second stray line with a chevron of its own.
 * Its glyph is the shared [RunStatusGlyph], the same component and size every step row leads with;
 * the line itself is the shared [SummaryLine] a completed background report draws with too.
 */
@Composable
internal fun RunSummaryLine(state: RunHeaderState) {
    val fold = state.fold
    val tone = runSummaryTone(state)
    val description = runSummaryStateDescription(state.activity, fold)
    SummaryLine(
        spec = RunStepRowSpec(
            title = runSummaryTitle(state.activity, tone),
            subtitle = null,
            tone = tone,
            glyphLabel = null,
            tags = RunStepRowTags(row = ChatRowTestTags.RUN_HEADER, glyph = ChatRowTestTags.RUN_GLYPH),
        ),
        disclosure = fold?.let { RunStepDisclosure(expanded = !it.collapsed, labels = RunStepDisclosureLabels.Run, onToggle = it.toggle) },
        modifier = Modifier
            .heightIn(min = if (state.tall) ChatRowSpacing.runHeaderToggleMinHeight else ChatRowSpacing.runHeaderMinHeight)
            .semantics(mergeDescendants = true) { description?.let { stateDescription = it } },
    )
}

/**
 * The one shape every summary line in the timeline draws with: a run's header and a finished
 * background task's row. The shared [RunStatusGlyph] at the run header's inset, the title in the
 * header's type and tone colour, [trailing] after it (a badge), and, when [disclosure] is set, the one
 * trailing chevron with the whole line as its toggle. Sharing it keeps such rows in one column: the
 * same glyph slot, label start and chevron edge. [modifier] carries the caller's height floor and
 * spoken state; [RunStepRowSpec.tags] tag the line and its glyph. Inside a parent that already insets
 * it ([LocalSummaryLineInsetByParent]) the line drops its own side inset.
 */
@Composable
internal fun SummaryLine(
    spec: RunStepRowSpec,
    disclosure: RunStepDisclosure?,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val reducedMotion = LocalReducedMotion.current
    val haptics = LocalHaptics.current
    val toggleLabel = disclosure?.let { stringResource(if (it.expanded) it.labels.collapse else it.labels.expand) }
    val sides = !LocalSummaryLineInsetByParent.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(spec.tags.row)
            .then(
                if (disclosure != null) {
                    Modifier.clickable(onClickLabel = toggleLabel) {
                        haptics.play(LettaHapticCue.SegmentTick)
                        disclosure.onToggle()
                    }
                } else {
                    Modifier
                },
            )
            .padding(
                start = if (sides) ChatRowSpacing.runHeaderHorizontal else 0.dp,
                end = if (sides) LettaDimens.Space.sm else 0.dp,
                top = ChatRowSpacing.runHeaderVertical,
                bottom = ChatRowSpacing.runHeaderVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        RunStatusGlyph(spec.tone, Modifier.testTag(spec.tags.glyph), spec.glyphLabel)
        RunSummaryTitleText(spec.title, runSummaryColor(spec.tone), reducedMotion, Modifier.weight(1f))
        trailing?.invoke(this)
        if (disclosure != null) DisclosureChevron(expanded = disclosure.expanded, contentDescription = toggleLabel)
    }
}

/**
 * True where a parent already insets a [SummaryLine] on both sides: an open tool step's details sit
 * flush with the step's title and keep its chevron's edge, so a report drawn there takes no inset of its own.
 */
internal val LocalSummaryLineInsetByParent = staticCompositionLocalOf { false }

/** What the summary line's glyph and tint say about the run. */
internal enum class RunSummaryTone {
    /** The run is still working: a spinner. */
    Working,

    /** A request of the run waits on the person: a warning in the secondary tint. */
    Attention,

    /** Settled with failed tool calls: a warning in the error tint. */
    Failed,

    /** Settled cleanly: a muted check. */
    Done,
}

internal fun runSummaryTone(state: RunHeaderState): RunSummaryTone = when {
    state.awaitingApproval -> RunSummaryTone.Attention
    state.activity.isActive -> RunSummaryTone.Working
    state.activity.failureCount > 0 -> RunSummaryTone.Failed
    else -> RunSummaryTone.Done
}

@Composable
private fun runSummaryStateDescription(activity: RunActivityProjection, fold: RunFold?): String? = when {
    activity.isActive -> stringResource(Res.string.rows_run_state_working)
    fold == null -> null
    fold.collapsed -> stringResource(Res.string.rows_run_state_collapsed)
    else -> stringResource(Res.string.rows_run_state_expanded)
}

/** The line's words: its state, then (settled) how long it took and how many tools it ran. */
@Composable
private fun runSummaryTitle(activity: RunActivityProjection, tone: RunSummaryTone): String {
    // Formatting the join with two empty parts yields the bare separator (" · ").
    val separator = stringResource(Res.string.rows_run_summary_join, "", "")
    val parts = when (tone) {
        RunSummaryTone.Attention -> listOf(stringResource(Res.string.run_status_approval))
        RunSummaryTone.Working -> listOf(stringResource(Res.string.rows_run_working))
        RunSummaryTone.Failed -> failedParts(activity)
        RunSummaryTone.Done -> doneParts(activity)
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

@Composable
private fun runSummaryColor(tone: RunSummaryTone): Color = when (tone) {
    RunSummaryTone.Attention -> MaterialTheme.colorScheme.secondary
    RunSummaryTone.Failed -> MaterialTheme.colorScheme.error
    RunSummaryTone.Working -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ChatRowAlpha.workingTitle)
    RunSummaryTone.Done -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * "Working…" cross-fades to "Worked · 2s" as the run settles (legacy
 * RunBlockMotion RunHeaderLabel: the terminal swap) instead of swapping the text in one frame.
 * Keyed on the copy, so only a change of words plays it.
 */
@Composable
private fun RunSummaryTitleText(title: String, color: Color, reducedMotion: Boolean, modifier: Modifier) {
    AnimatedContent(
        targetState = title,
        transitionSpec = { ChatRowMotion(reducedMotion).terminalSwap() },
        contentAlignment = Alignment.CenterStart,
        label = "runSummaryTitle",
        modifier = modifier,
    ) { shown ->
        Text(
            text = shown,
            style = ChatRowType.runHeaderTitle,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
