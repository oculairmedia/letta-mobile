package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_run_collapse
import com.letta.mobile.sharedui.resources.rows_run_expand
import com.letta.mobile.sharedui.resources.rows_run_state_collapsed
import com.letta.mobile.sharedui.resources.rows_run_state_expanded
import com.letta.mobile.data.chat.tools.steps.activity
import com.letta.mobile.ui.chat.tools.LocalToolPresenters
import com.letta.mobile.ui.chat.tools.groupLabel
import com.letta.mobile.ui.components.line.LineDisclosureLabels
import com.letta.mobile.ui.components.line.StatusTone
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaSizingTokens
import kotlinx.collections.immutable.ImmutableList

/** One tool step's state in the status glyph's tones (success, attention, failure, working). */
internal fun ToolTimelineState.glyphTone(): StatusTone = when (this) {
    ToolTimelineState.Running -> StatusTone.Working
    ToolTimelineState.AwaitingApproval, ToolTimelineState.Warning -> StatusTone.Attention
    ToolTimelineState.Failed, ToolTimelineState.Rejected -> StatusTone.Failed
    ToolTimelineState.Succeeded -> StatusTone.Done
}

/** A run's steps, under its summary line: "Show steps" / "Hide steps", spoken open / closed. */
internal val RunDisclosureLabels = LineDisclosureLabels(
    Res.string.rows_run_expand,
    Res.string.rows_run_collapse,
    Res.string.rows_run_state_expanded,
    Res.string.rows_run_state_collapsed,
)

/**
 * An expanded run's tool calls, listed straight under its summary line: one [ToolStep] line per
 * call. Several steps read under a plain, non-disclosing label of what they add up to ("Explored
 * files, ran commands") and share a bounded viewport that scrolls past
 * [ChatRowDimens.toolGroupMaxHeight], following its newest step while the run works. A request
 * waiting on the person keeps its approval card under the steps, unchanged.
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
            if (toolCalls.size > 1) StepGroupLabel(toolCalls)
            ToolStepList(calls, RunRowScope(context, callbacks))
        }
        // Each request keeps its own slot, so answering one folds its card away (animated) in place.
        calls.approvals.forEach { approval ->
            key(approval.requestId) {
                ApprovalRequestSlot(approval.takeIf { it.isWaitingOn(toolCalls) }, context, callbacks)
            }
        }
    }
}

/**
 * The plain label over several steps: what their presenters' verbs add up to, never a disclosure.
 * It starts at the steps' own inset (flush with the step rows, under the summary line's label),
 * with no inset of its own.
 */
@Composable
private fun StepGroupLabel(toolCalls: List<UiToolCall>) {
    val presenters = LocalToolPresenters.current
    val activities = remember(toolCalls, presenters) { toolCalls.map { presenters.line(it.facts()).verb.activity }.distinct() }
    Text(
        text = groupLabel(activities),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .testTag(ChatRowTestTags.RUN_STEP_GROUP_LABEL)
            .padding(end = LettaDimens.Space.sm, top = LettaDimens.Space.xs, bottom = LettaDimens.Space.xs),
    )
}

/**
 * Each call as one step row ([ToolStep]), its state read with the run's approvals, so a call a
 * parked permission gate holds reads "awaiting approval", not "running". Shared by the run's steps
 * and the lone tool line's sheet and disclosure.
 */
@Composable
internal fun ToolStepList(calls: ToolRunCalls, row: RunRowScope, modifier: Modifier = Modifier) {
    val toolCalls: ImmutableList<UiToolCall> = calls.toolCalls
    val states = remember(toolCalls, calls.approvals) { toolCalls.map { call -> call.runState(calls.approvals.requestFor(call), toolCalls) } }
    val live = states.any { it == ToolTimelineState.Running }
    val scroll = rememberTailFollowingScroll(live)
    val bounded = if (toolCalls.size > 1) Modifier.heightIn(max = ChatRowDimens.toolGroupMaxHeight).verticalScroll(scroll) else Modifier
    Column(modifier = modifier.fillMaxWidth().then(bounded)) {
        toolCalls.forEachIndexed { index, call ->
            val disclosureKey = call.disclosureKey().ifBlank { "step:$index" }
            key(index, disclosureKey) {
                ToolStep(call, disclosureKey, row.callbacks, presentation = states[index] to row.context.runWidthPx)
            }
        }
    }
}

/**
 * Indents work and details only at wide widths, unless an approval needs its existing inset.
 * Standalone steps use their own constraints; run steps use the width before their parent's inset,
 * so a wide run at the breakpoint stays wide at every indentation level.
 */
internal fun Modifier.runWidthPadding(
    indent: Dp,
    runWidthPx: State<Int>?,
    top: Dp = 0.dp,
    preserveIndent: Boolean = false,
): Modifier = layout { measurable, constraints ->
    val compact = !preserveIndent &&
        (runWidthPx?.value ?: constraints.maxWidth).toDp() < LettaSizingTokens.COMPACT_WIDTH_BREAKPOINT.dp
    val startPx = if (compact) 0 else indent.roundToPx()
    val topPx = top.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = -startPx, vertical = -topPx))
    layout(
        constraints.constrainWidth(placeable.width + startPx),
        constraints.constrainHeight(placeable.height + topPx),
    ) { placeable.placeRelative(startPx, topPx) }
}

