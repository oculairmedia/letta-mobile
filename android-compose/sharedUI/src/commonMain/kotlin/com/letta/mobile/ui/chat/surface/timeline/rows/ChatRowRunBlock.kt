package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.RunActivityProjection
import com.letta.mobile.data.chat.projection.RunActivityState
import com.letta.mobile.data.chat.projection.projectRunActivity
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_run_failure_count
import com.letta.mobile.sharedui.resources.rows_run_for_duration
import com.letta.mobile.sharedui.resources.rows_run_state_collapsed
import com.letta.mobile.sharedui.resources.rows_run_state_expanded
import com.letta.mobile.sharedui.resources.rows_run_state_working
import com.letta.mobile.sharedui.resources.rows_run_thought
import com.letta.mobile.sharedui.resources.rows_run_tools
import com.letta.mobile.sharedui.resources.rows_run_worked
import com.letta.mobile.sharedui.resources.rows_run_working
import com.letta.mobile.sharedui.resources.rows_work_collapse
import com.letta.mobile.sharedui.resources.rows_work_expand
import com.letta.mobile.ui.chat.session.ChatRunId
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.chat.surface.ambient.LocalChatWorkingCueAnimated
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: a run of assistant messages sharing a run id, drawn as the Android
 * timeline draws it (feature-chat RunBlock + RunActivityDisclosure): a "Working · 1 tool" /
 * "Thought for 4.2s · 2 tools" header, then the run's steps in order: each reasoning as a
 * one-line "Thought" disclosure, every tool call of the run folded into one "Ran 2 commands"
 * summary, and the narration as plain text.
 *
 * The header shows while the run works and, once it settles, only on the conversation's newest
 * row (older runs read as their steps alone). Collapse is owner state (ChatActions.toggleRunCollapsed
 * over collapsedRunIds): a collapsed run shows only its newest non-reasoning step; active work never
 * collapses.
 */
@Composable
internal fun RunBlockRow(
    runId: String,
    runMessages: List<UiMessage>,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    val messages = remember(runMessages) { runMessages.toImmutableList() }
    if (messages.isEmpty()) return
    val active = isRunStreaming(messages, context)
    val activity = remember(messages, active) { projectRunActivity(messages, active) } ?: return
    val run = runBlockOf(runId, messages, activity, context.itemState.collapsedRunIds)
    Column(modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.RUN_BLOCK)) {
        RunHeaderSlot(run, visible = activity.isActive || context.isNewest, callbacks)
        // One plain message keeps its own geometry under the header; a lone tool call still
        // reaches the tool summary.
        if (messages.size == 1 && !messages.single().isRunToolCallMessage()) {
            RunMessageStep(messages.single(), GroupPosition.None, context, callbacks)
        } else {
            RunBody(run, context, callbacks)
        }
    }
}

/** One run as its row draws it: its messages, their projected activity, and its collapse state. */
@Immutable
private data class RunBlock(
    val runId: String,
    val messages: ImmutableList<UiMessage>,
    val activity: RunActivityProjection,
    /** More than one message and settled: the header offers collapse. */
    val canCollapse: Boolean,
    val collapsed: Boolean,
)

private fun runBlockOf(
    runId: String,
    messages: ImmutableList<UiMessage>,
    activity: RunActivityProjection,
    collapsedRunIds: Set<String>,
): RunBlock {
    val canCollapse = messages.size > 1 && !activity.isActive
    return RunBlock(runId, messages, activity, canCollapse, collapsed = canCollapse && runId in collapsedRunIds)
}

/** The run is the one streaming: the page streams and its streaming message is one of the run's. */
private fun isRunStreaming(messages: List<UiMessage>, context: ChatRowContext): Boolean {
    if (!context.itemState.isStreaming) return false
    val id = context.streamingMessageId ?: return false
    return messages.any { it.id == id || it.clientMessageId == id }
}

@Composable
private fun ColumnScope.RunHeaderSlot(run: RunBlock, visible: Boolean, callbacks: ChatRowCallbacks) {
    val reducedMotion = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
        exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
    ) {
        RunActivityHeader(
            activity = run.activity,
            collapsed = run.collapsed,
            onToggle = if (run.canCollapse) ({ callbacks.actions.toggleRunCollapsed(ChatRunId(run.runId)) }) else null,
        )
    }
}

/** The run's steps under the header, lifted under it once settled, cross-fading on collapse. */
@Composable
private fun RunBody(run: RunBlock, context: ChatRowContext, callbacks: ChatRowCallbacks) {
    val reducedMotion = LocalReducedMotion.current
    val lift by animateDpAsState(
        targetValue = if (!run.activity.isActive && context.isNewest) ChatRowSpacing.completedRunBodyLift else 0.dp,
        animationSpec = if (reducedMotion) snap() else tween(LettaMotionTokens.CONTENT_SIZE_MILLIS),
        label = "RunBodyLift",
    )
    Box(modifier = Modifier.fillMaxWidth().pullUp { lift }) {
        AnimatedContent(
            targetState = run.collapsed,
            transitionSpec = { runBodyTransform(reducedMotion) },
            label = "RunBlockExpandCollapse",
        ) { isCollapsed ->
            RunSteps(run.messages, isCollapsed, context, callbacks)
        }
    }
}

private fun AnimatedContentTransitionScope<Boolean>.runBodyTransform(reducedMotion: Boolean): ContentTransform {
    val millis = if (reducedMotion) 0 else LettaMotionTokens.CONTENT_SIZE_MILLIS
    return (fadeIn(tween(millis)) togetherWith fadeOut(tween(millis)))
        .using(SizeTransform(clip = true) { _, _ -> tween(millis) })
}

@Composable
private fun RunSteps(
    messages: ImmutableList<UiMessage>,
    isCollapsed: Boolean,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    val visible = if (isCollapsed) listOf(collapsedPreview(messages)) else messages
    val steps = remember(visible) { compactRunSteps(visible) }
    Column(modifier = Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            key(step.key) {
                RunStepRow(step, stepPosition(index, steps.size, isCollapsed), context, callbacks)
            }
        }
    }
}

@Composable
private fun RunStepRow(
    step: RunStep,
    position: GroupPosition,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    when (step) {
        is RunStep.Message -> RunMessageStep(
            message = step.message,
            position = position,
            context = context,
            callbacks = callbacks,
        )
        is RunStep.ToolCalls -> ToolRunGroup(
            calls = step.calls,
            context = context,
            callbacks = callbacks,
            modifier = Modifier.padding(top = ChatRowSpacing.grouped),
        )
    }
}

private fun stepPosition(index: Int, count: Int, collapsed: Boolean): GroupPosition {
    return when {
        collapsed || count == 1 -> GroupPosition.None
        index == 0 -> GroupPosition.First
        index == count - 1 -> GroupPosition.Last
        else -> GroupPosition.Middle
    }
}

/**
 * One message step: the Android row rhythm (chatMessageRowVerticalPadding): reasoning and
 * tool rows take the tight grouped beat, as do the middle and last steps; the first step of a
 * new speaker takes the section break.
 */
@Composable
private fun RunMessageStep(
    message: UiMessage,
    position: GroupPosition,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    val top = if (takesGroupedBeat(message, position)) ChatRowSpacing.grouped else ChatRowSpacing.ungrouped
    Box(modifier = Modifier.fillMaxWidth().padding(top = top)) {
        ChatMessageRow(message, context, callbacks)
    }
}

/** Reasoning and tool rows, and every step after a run's first, sit on the tight grouped beat. */
private fun takesGroupedBeat(message: UiMessage, position: GroupPosition): Boolean {
    val reasoningOrTools = message.isReasoning || !message.toolCalls.isNullOrEmpty()
    val continuing = position == GroupPosition.Middle || position == GroupPosition.Last
    return reasoningOrTools || continuing
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

/** A collapsed run previews its newest step that is not reasoning. */
private fun collapsedPreview(messages: List<UiMessage>): UiMessage =
    messages.lastOrNull { !it.isReasoning } ?: messages.last()

/**
 * Draws the content [distance] higher AND reports it [distance] shorter (feature-chat pullUp), so
 * a settled run's body tucks under its header without leaving a band at the bottom.
 */
private fun Modifier.pullUp(distance: () -> Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = distance().roundToPx().coerceIn(0, placeable.height)
    layout(placeable.width, placeable.height - shift) {
        placeable.place(0, -shift)
    }
}

/**
 * "Working · 1 tool · 1 failure" / "Thought for 4.2s · 2 tools" (feature-chat RunActivityDisclosure):
 * a pulsing orb while working, the muted title, the counts, and a trailing chevron when the run
 * can collapse.
 */
@Composable
private fun RunActivityHeader(
    activity: RunActivityProjection,
    collapsed: Boolean,
    onToggle: (() -> Unit)?,
) {
    val stateText = stringResource(runStateLabel(activity, collapsed))
    val clickLabel = stringResource(if (collapsed) Res.string.rows_work_expand else Res.string.rows_work_collapse)
    val click = rememberQuietClick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatRowTestTags.RUN_HEADER)
            .heightIn(min = if (onToggle != null) LettaDimens.Orb.railSlotHeight else LettaDimens.Space.xxl)
            .semantics(mergeDescendants = true) { stateDescription = stateText }
            // No hover block: the title lifts instead (the Android disclosure has no inset).
            .runHeaderToggle(click, clickLabel, onToggle)
            // Flush with the agent's prose and the tool summary at the timeline's gutter; only the
            // trailing chevron keeps its inset from the end.
            .padding(end = LettaDimens.Space.xs, top = LettaDimens.Space.hair, bottom = LettaDimens.Space.hair),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        // Docked over the canvas the panel's ambient glow is the working cue, not the orb.
        if (activity.isActive && LocalChatWorkingCueAnimated.current) WorkingOrb()
        Text(
            text = runActivityTitle(activity),
            style = MaterialTheme.typography.labelMedium,
            color = runTitleColor(activity, lifted = onToggle != null && click.lifted),
        )
        RunActivityCounts(activity)
        if (onToggle != null) {
            Spacer(Modifier.weight(1f))
            DisclosureChevron(expanded = !collapsed)
        }
    }
}

private fun runStateLabel(activity: RunActivityProjection, collapsed: Boolean): StringResource {
    return when {
        activity.isActive -> Res.string.rows_run_state_working
        collapsed -> Res.string.rows_run_state_collapsed
        else -> Res.string.rows_run_state_expanded
    }
}

/** A collapsible header toggles on a quiet click (no hover block: the title lifts instead). */
private fun Modifier.runHeaderToggle(click: QuietClick, clickLabel: String, onToggle: (() -> Unit)?): Modifier {
    if (onToggle == null) return this
    return then(Modifier.quietClickable(click, onClickLabel = clickLabel, onClick = onToggle))
}

@Composable
private fun runTitleColor(activity: RunActivityProjection, lifted: Boolean): Color {
    return when {
        activity.isActive -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ChatRowAlpha.workingTitle)
        lifted -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** "· 2 tools · 1 failure": each count only when nonzero. */
@Composable
private fun RunActivityCounts(activity: RunActivityProjection) {
    if (activity.toolCount > 0) {
        Text(
            text = pluralStringResource(Res.plurals.rows_run_tools, activity.toolCount, activity.toolCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ChatRowAlpha.activityCount),
        )
    }
    if (activity.failureCount > 0) {
        Text(
            text = pluralStringResource(Res.plurals.rows_run_failure_count, activity.failureCount, activity.failureCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
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
