package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.RunActivityProjection
import com.letta.mobile.data.chat.projection.RunActivityState
import com.letta.mobile.data.chat.projection.projectRunActivity
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_list_separator
import com.letta.mobile.sharedui.resources.rows_run_for_duration
import com.letta.mobile.sharedui.resources.rows_run_state_working
import com.letta.mobile.sharedui.resources.rows_run_thought
import com.letta.mobile.sharedui.resources.rows_run_worked
import com.letta.mobile.sharedui.resources.rows_run_working
import com.letta.mobile.ui.chat.surface.ambient.LocalChatWorkingCueAnimated
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.ChatRowType
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: a run of assistant messages sharing a run id, drawn as the Android
 * timeline draws it (feature-chat RunBlock): its steps in order: each reasoning as a one-line
 * "Thought" disclosure, every tool call of the run folded into one "Ran 2 commands" summary, and
 * the narration as plain text.
 *
 * letta-mobile-bglj6.1.11: a run has ONE disclosure, its tool summary. The run's own summary
 * ("Working", "Worked for 1m 7s") is a plain label that never toggles: it shows while the run
 * works and, once it settles, only on the conversation's newest row. When the run opens with its
 * tool summary the label leads that same line ("Worked for 1m 7s · Ran 4 commands"), so the run
 * reads as one line with one chevron; otherwise the label sits on its own line above the steps.
 */
@Composable
internal fun RunBlockRow(
    runMessages: List<UiMessage>,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    val messages = remember(runMessages) { runMessages.toImmutableList() }
    if (messages.isEmpty()) return
    val active = isRunStreaming(messages, context)
    val activity = remember(messages, active) { projectRunActivity(messages, active) } ?: return
    val title = RunSummaryTitle(activity, visible = activity.isActive || context.isNewest)
    val steps = remember(messages) { compactRunSteps(messages) }
    val lead = steps.firstOrNull() as? RunStep.ToolCalls
    Column(modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.RUN_BLOCK)) {
        if (lead != null) {
            // The label leads the tool summary's own line: one line, one chevron.
            ToolRunGroup(lead.calls.copy(title = title), context, callbacks)
            RunSteps(steps.drop(1), context, callbacks, leadingGap = true)
        } else {
            RunSummaryLine(title)
            RunSteps(steps, context, callbacks, leadingGap = false)
        }
    }
}

/** The run's summary label and whether it shows (working, or settled on the newest row). */
@Immutable
internal data class RunSummaryTitle(val activity: RunActivityProjection, val visible: Boolean)

/** The run is the one streaming: the page streams and its streaming message is one of the run's. */
private fun isRunStreaming(messages: List<UiMessage>, context: ChatRowContext): Boolean {
    if (!context.itemState.isStreaming) return false
    val id = context.streamingMessageId ?: return false
    return messages.any { it.id == id || it.clientMessageId == id }
}

/**
 * The label on its own line, for a run that does not open with its tool summary. It carries the
 * grouped beat under it, so the beat folds away with it when the run stops being the newest.
 */
@Composable
private fun RunSummaryLine(title: RunSummaryTitle) {
    val reducedMotion = LocalReducedMotion.current
    AnimatedVisibility(
        visible = title.visible,
        enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
        exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = ChatRowSpacing.grouped)
                .heightIn(min = ChatRowSpacing.summaryLineMinHeight)
                .padding(vertical = ChatRowSpacing.summaryLineVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RunSummaryLabel(title.activity)
        }
    }
}

/**
 * The run's label leading its tool summary's line ("Worked for 1m 7s · "): fades and folds
 * sideways as the run stops being the newest, so the tool summary slides home instead of jumping.
 */
@Composable
internal fun RunSummaryLead(title: RunSummaryTitle) {
    val reducedMotion = LocalReducedMotion.current
    AnimatedVisibility(
        visible = title.visible,
        enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
        exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RunSummaryLabel(title.activity)
            Text(
                text = stringResource(Res.string.rows_list_separator),
                style = ChatRowType.summaryLine,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * "Working" (with its orb) / "Worked for 1m 7s": the run's plain summary label, in the one muted
 * summary style. Never clickable: the run's only disclosure is its tool summary.
 */
@Composable
private fun RunSummaryLabel(activity: RunActivityProjection) {
    val working = stringResource(Res.string.rows_run_state_working)
    Row(
        modifier = Modifier
            .testTag(ChatRowTestTags.RUN_HEADER)
            .semantics(mergeDescendants = true) { if (activity.isActive) stateDescription = working },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        // Docked over the canvas the panel's ambient glow is the working cue, not the orb.
        if (activity.isActive && LocalChatWorkingCueAnimated.current) WorkingOrb()
        Text(
            text = runActivityTitle(activity),
            style = ChatRowType.summaryLine,
            color = runTitleColor(activity),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The run's steps after its summary, each on the grouped beat below the one before. */
@Composable
private fun RunSteps(
    steps: List<RunStep>,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
    leadingGap: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            key(step.key) {
                val top = if (index > 0 || leadingGap) ChatRowSpacing.grouped else 0.dp
                RunStepRow(step, context, callbacks, Modifier.padding(top = top))
            }
        }
    }
}

@Composable
private fun RunStepRow(
    step: RunStep,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
    modifier: Modifier,
) {
    when (step) {
        is RunStep.Message -> Box(modifier = modifier.fillMaxWidth()) {
            ChatMessageRow(step.message, context, callbacks)
        }
        is RunStep.ToolCalls -> ToolRunGroup(
            calls = step.calls,
            context = context,
            callbacks = callbacks,
            modifier = modifier,
        )
    }
}

/** A step of a run, in chat order. */
@Immutable
internal sealed interface RunStep {
    val key: String

    @Immutable
    data class Message(val message: UiMessage) : RunStep {
        override val key: String = message.id
    }

    /** Every tool call of the run, in one summary at the first tool call's position. */
    @Immutable
    data class ToolCalls(
        val firstMessageId: String,
        val calls: ToolRunCalls,
    ) : RunStep {
        override val key: String = "tool-group-$firstMessageId"
    }
}

/**
 * Compacts a run into steps (feature-chat compactRunToolCallSteps): all of the run's tool calls
 * fold into ONE [RunStep.ToolCalls] at the first tool call's position; a message carrying both
 * prose and tool calls contributes its calls there and keeps its prose as its own step.
 */
internal fun compactRunSteps(messages: List<UiMessage>): List<RunStep> {
    if (messages.isEmpty()) return emptyList()
    // The group is emitted once, at the first tool-bearing message.
    var pendingGroup = runToolCallGroup(messages)
    val steps = ArrayList<RunStep>(messages.size)
    for (message in messages) {
        val group = pendingGroup
        if (group != null && message.isPlainAssistantStep()) {
            steps += group
            pendingGroup = null
        }
        message.ownRunStep()?.let { steps += it }
    }
    return steps
}

/** Every tool call of the run's tool-bearing messages, folded into one step; null when none. */
private fun runToolCallGroup(messages: List<UiMessage>): RunStep.ToolCalls? {
    val calls = messages.filter { it.isPlainAssistantStep() }
    if (calls.isEmpty()) return null
    return RunStep.ToolCalls(
        firstMessageId = calls.first().id,
        calls = ToolRunCalls(
            toolCalls = calls.flatMap { it.toolCalls.orEmpty() }.toImmutableList(),
            approvals = calls.mapNotNull { it.approvalRequest }.distinctBy { it.requestId }.toImmutableList(),
            startedAtTimestamp = calls.first().timestamp.takeIf { it.isNotBlank() },
        ),
    )
}

/** The message's own step: none for a tool-call-only message, the prose alone for prose with calls. */
private fun UiMessage.ownRunStep(): RunStep.Message? {
    return when {
        isRunToolCallMessage() -> null
        hasProseAndToolCalls() -> RunStep.Message(copy(toolCalls = null, approvalRequest = null))
        else -> RunStep.Message(this)
    }
}

/**
 * A plain assistant message carrying tool calls: tool-bearing, so its calls fold into the run's
 * tool summary ([isRunToolCallMessage] or [hasProseAndToolCalls]).
 */
private fun UiMessage.isPlainAssistantStep(): Boolean {
    if (role != "assistant" || isReasoning) return false
    if (isError || generatedUi != null) return false
    if (approvalResponse != null || attachments.isNotEmpty()) return false
    return !toolCalls.isNullOrEmpty()
}

/**
 * A tool-call-only assistant message: folds entirely into the run's tool summary. One carrying a
 * canvas card (letta-mobile-bglj6.13: a compose call with no narration of its own) folds only its
 * calls; the card stays a step.
 */
internal fun UiMessage.isRunToolCallMessage(): Boolean = isPlainAssistantStep() && !hasStepOfItsOwn()

private fun UiMessage.hasProseAndToolCalls(): Boolean = isPlainAssistantStep() && hasStepOfItsOwn()

/** Prose, or a canvas card: what keeps a tool-calling message a step of its own. */
private fun UiMessage.hasStepOfItsOwn(): Boolean = content.isNotBlank() || artifacts.isNotEmpty()

@Composable
private fun runTitleColor(activity: RunActivityProjection): Color {
    return if (activity.isActive) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ChatRowAlpha.workingTitle)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun runActivityTitle(activity: RunActivityProjection): String {
    val duration = activity.durationMs?.let { formatRunDuration(it) }
    return when (activity.state) {
        RunActivityState.Working -> stringResource(Res.string.rows_run_working)
        RunActivityState.Thought -> duration?.let { stringResource(Res.string.rows_run_for_duration, stringResource(Res.string.rows_run_thought), it) }
            ?: stringResource(Res.string.rows_run_thought)
        RunActivityState.Worked -> duration?.let { stringResource(Res.string.rows_run_for_duration, stringResource(Res.string.rows_run_worked), it) }
            ?: stringResource(Res.string.rows_run_worked)
    }
}

/**
 * The working cue: one small tertiary orb pulsing its alpha in a fixed box, so a streaming run
 * never moves the header. Decorative: the header's state description carries "working".
 */
@Composable
private fun WorkingOrb() {
    val reducedMotion = LocalReducedMotion.current
    val alpha: State<Float> = if (reducedMotion) {
        remember { mutableFloatStateOf(ChatRowAlpha.workingOrbResting) }
    } else {
        rememberInfiniteTransition(label = "runWorkingOrb").animateFloat(
            initialValue = ChatRowAlpha.workingOrbDim,
            targetValue = ChatRowAlpha.workingOrbBright,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = WORKING_ORB_PULSE_MILLIS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "runWorkingOrbAlpha",
        )
    }
    Box(
        modifier = Modifier
            .size(LettaDimens.Space.sm)
            .graphicsLayer { this.alpha = alpha.value }
            .background(MaterialTheme.colorScheme.tertiary, CircleShape),
    )
}

private const val WORKING_ORB_PULSE_MILLIS = 1_400
