package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.chat.projection.parseTimestampEpochMillis
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_collapse
import com.letta.mobile.sharedui.resources.rows_expand
import com.letta.mobile.sharedui.resources.rows_state_collapsed
import com.letta.mobile.sharedui.resources.rows_state_expanded
import com.letta.mobile.ui.chat.surface.ambient.LocalChatWorkingCueAnimated
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.components.LettaListRow
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

/*
 * letta-mobile-ckmub.1.2: a run's steps are built from the shared primitives, so they read like every
 * other list row in the app: one [LettaListRow] per step, led by the same [RunStatusGlyph] the run's
 * summary line carries, with the shared [DisclosureChevron] only when the step has details to show
 * (its details read in [ToolOutputBlock]). An expanded run lists these rows straight under its
 * summary line: there is no "Ran 2 commands" group row with a second chevron of its own.
 */

/**
 * The run's status glyph, one component at one size ([ChatRowSpacing.runGlyphSize]) for the summary
 * line and every step: a spinner while working, then a check, a warning or an error. In a fixed slot,
 * cross-fading as the tone changes, so a step or a run settling never moves its label. Decorative
 * unless [contentDescription] names the state.
 */
@Composable
internal fun RunStatusGlyph(tone: RunSummaryTone, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val reducedMotion = LocalReducedMotion.current
    val described = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Crossfade(
        targetState = tone,
        animationSpec = if (reducedMotion) snap() else tween(RUN_GLYPH_FADE_MILLIS),
        modifier = modifier.size(ChatRowSpacing.runGlyphSize).then(described),
        label = "runStatusGlyph",
    ) { shown ->
        val glyph = Modifier.size(ChatRowSpacing.runGlyphSize)
        when (shown) {
            RunSummaryTone.Working -> WorkingGlyph(reducedMotion, glyph)
            RunSummaryTone.Attention -> Icon(LettaIcons.Warning, contentDescription = null, modifier = glyph, tint = MaterialTheme.colorScheme.secondary)
            RunSummaryTone.Failed -> Icon(LettaIcons.Error, contentDescription = null, modifier = glyph, tint = MaterialTheme.colorScheme.error)
            RunSummaryTone.Done -> Icon(LettaIcons.Check, contentDescription = null, modifier = glyph, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A small spinner while the work runs; a still ring under reduced motion, or docked over the canvas
 * where the panel's ambient glow is the working cue.
 */
@Composable
private fun WorkingGlyph(reducedMotion: Boolean, modifier: Modifier) {
    val tint = MaterialTheme.colorScheme.tertiary
    if (reducedMotion || !LocalChatWorkingCueAnimated.current) {
        Box(modifier.border(RUN_GLYPH_STROKE, tint, CircleShape))
    } else {
        CircularProgressIndicator(modifier = modifier, color = tint, strokeWidth = RUN_GLYPH_STROKE)
    }
}

/** One tool step's state in the run glyph's four tones (success, attention, failure, working). */
internal fun ToolTimelineState.glyphTone(): RunSummaryTone = when (this) {
    ToolTimelineState.Running -> RunSummaryTone.Working
    ToolTimelineState.AwaitingApproval, ToolTimelineState.Warning -> RunSummaryTone.Attention
    ToolTimelineState.Failed, ToolTimelineState.Rejected -> RunSummaryTone.Failed
    ToolTimelineState.Succeeded -> RunSummaryTone.Done
}

/** What one step row shows: its label, its one-line preview, its glyph and the glyph's spoken state. */
@Immutable
internal class RunStepRowSpec(
    val title: String,
    val subtitle: String?,
    val tone: RunSummaryTone,
    val glyphLabel: String?,
)

/** A step's disclosure: whether its details are open, and how to flip them. */
@Immutable
internal class RunStepDisclosure(val expanded: Boolean, val onToggle: () -> Unit)

/**
 * One step of a run as a [LettaListRow]: the shared [RunStatusGlyph] leading, the label in the list
 * row's title style, a one-line preview under it, and the shared [DisclosureChevron] trailing only
 * when [disclosure] has details to open. Its height never depends on its state: open or closed,
 * working or settled, it is the list row's height.
 */
@Composable
internal fun RunStepListRow(spec: RunStepRowSpec, disclosure: RunStepDisclosure?, modifier: Modifier = Modifier) {
    val stateText = disclosure?.let { stringResource(if (it.expanded) Res.string.rows_state_expanded else Res.string.rows_state_collapsed) }
    LettaListRow(
        spec = LettaListRowSpec(title = spec.title, subtitle = spec.subtitle, enabled = disclosure != null),
        onClick = { disclosure?.onToggle?.invoke() },
        modifier = modifier
            .testTag(ChatRowTestTags.TOOL_CARD_TOGGLE)
            .semantics { stateText?.let { stateDescription = it } },
        leading = { RunStatusGlyph(spec.tone, Modifier.testTag(ChatRowTestTags.TOOL_STATUS_GLYPH), spec.glyphLabel) },
        end = if (disclosure != null) {
            { RunStepChevron(disclosure.expanded) }
        } else {
            null
        },
    )
}

@Composable
private fun RunStepChevron(expanded: Boolean) {
    DisclosureChevron(
        expanded = expanded,
        contentDescription = stringResource(if (expanded) Res.string.rows_collapse else Res.string.rows_expand),
    )
}

/**
 * An expanded run's tool calls, listed straight under its summary line: one [ToolCard] step row per
 * call (no group row, no second chevron). Calls of more than one kind get a plain, non-disclosing
 * [LettaSectionLabel] over them ("Ran 3 commands"). A request waiting on the person keeps its
 * approval card under the steps (letta-mobile-sieej), unchanged.
 */
@Composable
internal fun RunToolSteps(
    calls: ToolRunCalls,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
    modifier: Modifier = Modifier,
) {
    val toolCalls = calls.toolCalls
    if (toolCalls.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        // Tagged inside: the run's work-step wrapper tags the outer node.
        Column(modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.RUN_TOOL_STEPS)) {
            if (toolCalls.hasMixedKinds()) RunStepsSubheader(calls)
            ToolStepList(calls, callbacks)
        }
        // Each request keeps its own slot, so answering one folds its card away (animated) in place.
        calls.approvals.forEach { approval ->
            key(approval.requestId) {
                ApprovalRequestSlot(approval.takeIf { it.isWaitingOn(toolCalls) }, context, callbacks)
            }
        }
    }
}

/** More than one tool ran: the steps need a word over them saying what they add up to. */
private fun List<UiToolCall>.hasMixedKinds(): Boolean = distinctBy { it.name }.size > 1

/** The plain subheader over steps of several kinds: the tool summary's words, never a disclosure. */
@Composable
private fun RunStepsSubheader(calls: ToolRunCalls) {
    val summary = remember(calls) { summarizeToolRun(calls.toolCalls, calls.approvals) }
    val startedAt = remember(calls.startedAtTimestamp) { calls.startedAtTimestamp?.let(::parseTimestampEpochMillis) }
    val elapsed by rememberElapsedSeconds(summary.running, startedAt)
    LettaSectionLabel(toolRunLabel(summary, elapsed), Modifier.testTag(ChatRowTestTags.RUN_STEP_SUBHEADER))
}

/**
 * Each call as one step row ([ToolCard]), its state read with the run's approvals, so a call a
 * parked permission gate holds reads "awaiting approval", not "running". Shared by the run's steps
 * and the lone tool line's sheet and disclosure.
 */
@Composable
internal fun ToolStepList(calls: ToolRunCalls, callbacks: ChatRowCallbacks, modifier: Modifier = Modifier) {
    val toolCalls: ImmutableList<UiToolCall> = calls.toolCalls
    val states = remember(toolCalls, calls.approvals) { toolCalls.map { call -> call.runState(calls.approvals.requestFor(call), toolCalls) } }
    Column(modifier = modifier.fillMaxWidth()) {
        toolCalls.forEachIndexed { index, call ->
            val disclosureKey = call.disclosureKey().ifBlank { "step:$index" }
            key(index, disclosureKey) {
                ToolCard(call, disclosureKey, callbacks, state = states[index])
            }
        }
    }
}

private val RUN_GLYPH_STROKE = 1.5.dp
private const val RUN_GLYPH_FADE_MILLIS = 150
