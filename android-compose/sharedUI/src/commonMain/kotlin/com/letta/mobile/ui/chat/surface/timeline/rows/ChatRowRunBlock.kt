package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatRunId
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_run_collapse
import com.letta.mobile.sharedui.resources.rows_run_expand
import com.letta.mobile.sharedui.resources.rows_run_failure_count
import com.letta.mobile.sharedui.resources.rows_run_for_duration
import com.letta.mobile.sharedui.resources.rows_run_state_collapsed
import com.letta.mobile.sharedui.resources.rows_run_state_expanded
import com.letta.mobile.sharedui.resources.rows_run_state_working
import com.letta.mobile.sharedui.resources.rows_run_thought
import com.letta.mobile.sharedui.resources.rows_run_tool_count
import com.letta.mobile.sharedui.resources.rows_run_worked
import com.letta.mobile.sharedui.resources.rows_run_working
import com.letta.mobile.ui.chat.surface.ambient.LocalChatWorkingCueAnimated
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowMotion
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.ChatRowType
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: a run of assistant messages sharing a run id, drawn as the Android
 * timeline draws it (feature-chat RunBlock): its steps in order: each reasoning as a one-line
 * "Thought" disclosure, every tool call of the run folded into one "Ran 2 commands" summary, and
 * the narration as plain text.
 *
 * letta-mobile-ckmub.1 (hard cut S4): the geometry is the legacy RunBlock's, so the two read the
 * same side by side (RowParityScreenshotTest). The run's header (legacy RunActivityDisclosure:
 * "Worked for 1.2s  2 tools  1 failure") sits on its own line; it shows while the run works and,
 * once it settles, only on the conversation's newest row. Under the header the body lifts up into
 * its floor, working and settled alike, so settling only rewrites the label; a settled run of several
 * steps folds to its last step from the header.
 */
@Composable
internal fun RunBlockRow(
    runMessages: List<UiMessage>,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
    /** A lone message's place in its speaker's group (the legacy list's), for its beat and role label. */
    position: GroupPosition = GroupPosition.None,
) {
    val messages = remember(runMessages) { runMessages.toImmutableList() }
    val plan = rememberRunBlockPlan(messages, context) ?: return
    val row = RunRowScope(context, callbacks)
    Column(modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.RUN_BLOCK)) {
        RunHeader(plan.header(callbacks.actions))
        val lone = plan.loneMessage
        if (lone != null) {
            // One message keeps its own row's geometry under the header (no body lift).
            RunStepRow(PositionedRunStep(RunStep.Message(lone), position), row, Modifier.padding(top = runStepTop(lone, position)))
        } else {
            RunBody(plan.body, row)
        }
    }
}

/** What every part of a run block draws with: the row's inputs and where its intents leave. */
@Immutable
private class RunRowScope(val context: ChatRowContext, val callbacks: ChatRowCallbacks)

/**
 * How one run block draws, decided from its messages and the row's context: whether its header
 * shows, what its body shows, and whether (and how) it folds.
 */
@Immutable
internal class RunBlockPlan(
    val activity: RunActivityProjection,
    /** Working, or settled on the conversation's newest row. */
    val headerVisible: Boolean,
    /** The run's messages the chat options leave on screen (zxk2n.5: hidden reasoning leaves no gap). */
    val visible: List<UiMessage>,
    val runId: String?,
    val messageCount: Int,
    /** The page's fold for this run (its collapsed run ids), honoured only while [foldable]. */
    val collapsedByOwner: Boolean,
) {
    /** The legacy fold: only a settled run of several messages folds; a stale fold never hides active work. */
    val foldable: Boolean get() = runId != null && isMultiStep() && !activity.isActive

    val collapsed: Boolean get() = foldable && collapsedByOwner

    /** One message that is not a bare tool call draws as its own row under the header. */
    val loneMessage: UiMessage?
        get() = visible.singleOrNull()?.takeIf { messageCount == 1 && !it.isRunToolCallMessage() }

    /**
     * Lifted whenever the header shows, working or settled: legacy's settled geometry, held from the
     * run's first frame so settling never moves it (no jump, bglj6.1.12).
     */
    val body: RunBodyState get() = RunBodyState(visible, collapsed, lifted = headerVisible)

    /** The header; a run that folds once settled keeps the fold's floor while it works. */
    fun header(actions: ChatActions): RunHeaderState =
        RunHeaderState(activity, headerVisible, tall = isMultiStep(), fold = foldOf(actions))

    private fun isMultiStep(): Boolean = messageCount > 1

    private fun foldOf(actions: ChatActions): RunFold? {
        val id = runId ?: return null
        if (!foldable) return null
        return RunFold(collapsed) { actions.toggleRunCollapsed(ChatRunId(id)) }
    }
}

/** The run's plan, or null when it has nothing to draw. */
@Composable
private fun rememberRunBlockPlan(messages: ImmutableList<UiMessage>, context: ChatRowContext): RunBlockPlan? {
    if (messages.isEmpty()) return null
    val active = isRunStreaming(messages, context)
    val activity = remember(messages, active) { projectRunActivity(messages, active) } ?: return null
    val visible = remember(messages, context.displayOptions) { context.displayOptions.visible(messages) }
    val runId = messages.first().runId
    return RunBlockPlan(
        activity = activity,
        headerVisible = activity.isActive || context.isNewest,
        visible = visible,
        runId = runId,
        messageCount = messages.size,
        collapsedByOwner = runId != null && runId in context.itemState.collapsedRunIds,
    )
}

/** What a run's body shows: its steps, folded or not, and whether it lifts under the header. */
@Immutable
internal class RunBodyState(val messages: List<UiMessage>, val collapsed: Boolean, val lifted: Boolean)
/** The run's header and whether it shows; [fold] when a settled run of several steps can fold. */
@Immutable
internal data class RunHeaderState(
    val activity: RunActivityProjection,
    val visible: Boolean,
    val tall: Boolean = false,
    val fold: RunFold? = null,
)

/** A settled run's fold: whether it is folded, and the header's toggle. */
@Immutable
internal class RunFold(val collapsed: Boolean, val toggle: () -> Unit)

/** The run is the one streaming: the page streams and its streaming message is one of the run's. */
private fun isRunStreaming(messages: List<UiMessage>, context: ChatRowContext): Boolean {
    if (!context.itemState.isStreaming) return false
    val id = context.streamingMessageId ?: return false
    return messages.any { it.id == id || it.clientMessageId == id }
}

/**
 * The run's header (legacy RunActivityDisclosure): the working orb, the title, the tool and
 * failure counts and, when the run folds, its chevron. Expands in and folds out as it shows.
 */
@Composable
private fun RunHeader(state: RunHeaderState) {
    val reducedMotion = LocalReducedMotion.current
    AnimatedVisibility(
        visible = state.visible,
        enter = ChatRowMotion(reducedMotion).expansionEnter(),
        // The steps below ease up into the header's place (legacy settled-run body lift).
        exit = ChatRowMotion(reducedMotion).runLabelFoldExit(),
        label = "runHeader",
    ) {
        RunHeaderRow(state.activity, state.fold, state.tall)
    }
}

@Composable
private fun RunHeaderRow(activity: RunActivityProjection, fold: RunFold?, tall: Boolean) {
    val description = runHeaderStateDescription(activity, fold)
    val toggleLabel = fold?.let { stringResource(if (it.collapsed) Res.string.rows_run_expand else Res.string.rows_run_collapse) }
    val haptics = LocalHaptics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatRowTestTags.RUN_HEADER)
            .heightIn(min = if (tall) ChatRowSpacing.runHeaderToggleMinHeight else ChatRowSpacing.runHeaderMinHeight)
            .semantics(mergeDescendants = true) { description?.let { stateDescription = it } }
            .then(
                if (fold != null) {
                    Modifier.clickable(onClickLabel = toggleLabel) {
                        haptics.play(LettaHapticCue.SegmentTick)
                        fold.toggle()
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = ChatRowSpacing.runHeaderHorizontal, vertical = ChatRowSpacing.runHeaderVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        // Docked over the canvas the panel's ambient glow is the working cue, not the orb.
        val reducedMotion = LocalReducedMotion.current
        AnimatedVisibility(
            visible = activity.isActive && LocalChatWorkingCueAnimated.current,
            enter = ChatRowMotion(reducedMotion).horizontalEnter(),
            exit = ChatRowMotion(reducedMotion).horizontalExit(),
            label = "runWorkingOrb",
        ) {
            WorkingOrb()
        }
        RunSummaryTitleText(runActivityTitle(activity), runTitleColor(activity), reducedMotion)
        RunHeaderCounts(activity)
        if (fold != null) {
            Spacer(Modifier.weight(1f))
            DisclosureChevron(expanded = !fold.collapsed)
        }
    }
}

@Composable
private fun runHeaderStateDescription(activity: RunActivityProjection, fold: RunFold?): String? = when {
    activity.isActive -> stringResource(Res.string.rows_run_state_working)
    fold == null -> null
    fold.collapsed -> stringResource(Res.string.rows_run_state_collapsed)
    else -> stringResource(Res.string.rows_run_state_expanded)
}

/** "2 tools" (muted) and "1 failure" (error tint) beside the title, as the legacy header counts them. */
@Composable
private fun RunHeaderCounts(activity: RunActivityProjection) {
    if (activity.toolCount > 0) {
        Text(
            text = pluralStringResource(Res.plurals.rows_run_tool_count, activity.toolCount, activity.toolCount),
            style = ChatRowType.runHeaderCount,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ChatRowAlpha.runCount),
            maxLines = 1,
        )
    }
    if (activity.failureCount > 0) {
        Text(
            text = pluralStringResource(Res.plurals.rows_run_failure_count, activity.failureCount, activity.failureCount),
            style = ChatRowType.runHeaderCount,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
        )
    }
}

/**
 * letta-mobile-bglj6.1.19: "Working" cross-fades to "Thought for 2s" as the run settles (legacy
 * RunBlockMotion RunHeaderLabel: the terminal swap, 120 ms in over 90 ms out) instead of swapping
 * the text in one frame. Keyed on the copy, so only a change of words plays it.
 */
@Composable
private fun RunSummaryTitleText(title: String, color: Color, reducedMotion: Boolean) {
    AnimatedContent(
        targetState = title,
        transitionSpec = { ChatRowMotion(reducedMotion).terminalSwap() },
        contentAlignment = Alignment.CenterStart,
        label = "runSummaryTitle",
    ) { shown ->
        Text(
            text = shown,
            style = ChatRowType.runHeaderTitle,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The run's steps under its header. A settled run's body lifts [ChatRowSpacing.settledRunBodyLift]
 * up under the header's floor, easing (legacy jqiu3: by layout, so the lifted distance leaves no
 * band below); folded, only the run's last non-reasoning step shows.
 */
@Composable
private fun RunBody(body: RunBodyState, row: RunRowScope) {
    val reducedMotion = LocalReducedMotion.current
    val lift by animateDpAsState(
        targetValue = if (body.lifted) ChatRowSpacing.settledRunBodyLift else 0.dp,
        animationSpec = if (reducedMotion) snap() else tween(RUN_BODY_LIFT_MILLIS),
        label = "runBodyLift",
    )
    Box(Modifier.fillMaxWidth().pullUp { lift }) {
        AnimatedContent(
            targetState = body.collapsed,
            transitionSpec = {
                val motion = ChatRowMotion(reducedMotion)
                (fadeIn(motion.contentSize()) togetherWith fadeOut(motion.contentSize()))
                    .using(SizeTransform(clip = true) { _, _ -> motion.contentSize() })
            },
            label = "runBodyFold",
        ) { folded ->
            val shown = if (folded) listOfNotNull(collapsedPreview(body.messages)) else body.messages
            RunSteps(remember(shown) { compactRunSteps(shown) }, folded, row)
        }
    }
}

/** The step a folded run keeps: its newest non-reasoning message (legacy selectCollapsedPreview). */
private fun collapsedPreview(messages: List<UiMessage>): UiMessage? =
    messages.lastOrNull { !it.isReasoning } ?: messages.lastOrNull()

/** Draws the content [distance] higher and reports it [distance] shorter (legacy pullUp). */
private fun Modifier.pullUp(distance: () -> Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = distance().roundToPx().coerceIn(0, placeable.height)
    layout(placeable.width, placeable.height - shift) { placeable.place(0, -shift) }
}

/** The run's steps, each on the legacy row beat above it (RenderChatMessage's item padding). */
@Composable
private fun RunSteps(steps: List<RunStep>, folded: Boolean, row: RunRowScope) {

    Column(modifier = Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            key(step.key) {
                val positioned = PositionedRunStep(step, stepPosition(index, steps.size, folded))
                RunStepRow(positioned, row, Modifier.padding(top = positioned.top()))
            }
        }
    }
}

private fun stepPosition(index: Int, count: Int, folded: Boolean): GroupPosition = when {
    folded || count == 1 -> GroupPosition.None
    index == 0 -> GroupPosition.First
    index == count - 1 -> GroupPosition.Last
    else -> GroupPosition.Middle
}

/**
 * The beat above one message step (legacy chatMessageRowVerticalPadding): reasoning and tool steps
 * take the grouped beat, as do the middle and last of a run's steps; the first takes the section break.
 */
internal fun runStepTop(message: UiMessage, position: GroupPosition): Dp = when {
    message.isReasoning || !message.toolCalls.isNullOrEmpty() -> ChatRowSpacing.grouped
    position == GroupPosition.Middle || position == GroupPosition.Last -> ChatRowSpacing.grouped
    else -> ChatRowSpacing.ungrouped
}

/** A run step and its place among the run's visible steps (the legacy row's group position). */
@Immutable
private class PositionedRunStep(val step: RunStep, val position: GroupPosition) {
    /** The legacy row beat above it: a tool summary always takes the grouped beat. */
    fun top(): Dp = when (step) {
        is RunStep.Message -> runStepTop(step.message, position)
        is RunStep.ToolCalls -> ChatRowSpacing.grouped
    }
}

@Composable
private fun RunStepRow(positioned: PositionedRunStep, row: RunRowScope, modifier: Modifier) {
    when (val step = positioned.step) {
        is RunStep.Message -> Box(modifier = modifier.fillMaxWidth()) {
            ChatMessageRow(step.message, row.context, row.callbacks, positioned.position)
        }
        is RunStep.ToolCalls -> ToolRunGroup(
            calls = step.calls,
            context = row.context,
            callbacks = row.callbacks,
            modifier = modifier,
        )
    }
}

private const val RUN_BODY_LIFT_MILLIS = 220

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
