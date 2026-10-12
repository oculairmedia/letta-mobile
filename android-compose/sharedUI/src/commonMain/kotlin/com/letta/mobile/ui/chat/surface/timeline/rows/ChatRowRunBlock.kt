package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.RunActivityProjection
import com.letta.mobile.data.chat.projection.projectRunActivity
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatRunId
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.ChatRowMotion
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * letta-mobile-bglj6.1: a run of assistant messages sharing a run id, drawn as the Android
 * timeline draws it (feature-chat RunBlock): its steps in order: each reasoning as a one-line
 * "Thought" disclosure, every tool call of the run folded into one "Ran 2 commands" summary, and
 * the narration as plain text.
 *
 * letta-mobile-ckmub.1 (hard cut S4): the geometry is the legacy RunBlock's. The run's header sits on
 * its own line; under it the body lifts up into its floor, working and settled alike, so settling only
 * rewrites the label.
 *
 * letta-mobile-zxk2n.37: the header is ONE summary line ([RunSummaryLine]: glyph, "Worked · 10.8s ·
 * 1 tool", one chevron), and a run with work to show (its reasoning, its tool line) is folded by
 * default, working or settled alike: only its prose shows under the line. Opening the line lists the
 * work steps indented under it, each with its own disclosure. A request waiting on the person holds
 * the run open (letta-mobile-sieej).
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
    /** Working, settled on the conversation's newest row, or waiting on the person. */
    val headerPinned: Boolean,
    /** The run's messages the chat options leave on screen (zxk2n.5: hidden reasoning leaves no gap). */
    val visible: List<UiMessage>,
    val runId: String?,
    val messageCount: Int,
    /**
     * letta-mobile-zxk2n.37: the person flipped this run's fold from its default (the page's
     * collapsedRunIds, a plain toggle set on every owner): a foldable run is folded by default, so a
     * run in the set is the one the person opened.
     */
    val toggledByOwner: Boolean,
    /**
     * letta-mobile-sieej: a request of the run waits on the person. The run then never folds and its
     * header shows ("Waiting for your approval"), so the card under its tool line cannot be missed;
     * only once it is answered does the run fold back into its summary line.
     */
    val awaitingApproval: Boolean = false,
) {
    /** One message that is not a bare tool call draws as its own row under the header. */
    val loneMessage: UiMessage? = loneRunMessage(visible, messageCount)

    /** A run folds when it has work to tuck under its summary line and nothing holds it open. */
    val foldable: Boolean = runFolds(RunFoldInputs(runId, hasWorkSteps(), awaitingApproval))

    /** Folded by default; the person's toggle opens it. */
    val collapsed: Boolean = isCollapsed()

    /** A run that folds always shows its summary line: the line is the only way to its steps. */
    val headerVisible: Boolean = isHeaderVisible()

    /**
     * Lifted whenever the header shows, working or settled: legacy's settled geometry, held from the
     * run's first frame so settling never moves it (no jump, bglj6.1.12).
     */
    val body: RunBodyState get() = RunBodyState(visible, collapsed, lifted = headerVisible)

    /** The header; a run that folds keeps the fold's floor, working and settled alike. */
    fun header(actions: ChatActions): RunHeaderState =
        RunHeaderState(activity, headerVisible, tall = isTall(), fold = foldOf(actions), awaitingApproval = awaitingApproval)

    private fun hasWorkSteps(): Boolean {
        if (loneMessage != null) return false
        return visible.any { it.isRunWorkMessage() }
    }

    private fun isCollapsed(): Boolean {
        if (!foldable) return false
        return !toggledByOwner
    }

    private fun isHeaderVisible(): Boolean {
        if (headerPinned) return true
        return foldable
    }

    private fun isTall(): Boolean {
        if (messageCount > 1) return true
        return foldable
    }

    private fun foldOf(actions: ChatActions): RunFold? {
        val id = runId ?: return null
        if (!foldable) return null
        return RunFold(collapsed) { actions.toggleRunCollapsed(ChatRunId(id)) }
    }
}

/** One message that is not a bare tool call: it draws as its own row under the header. */
private fun loneRunMessage(visible: List<UiMessage>, messageCount: Int): UiMessage? {
    if (messageCount != 1) return null
    val only = visible.singleOrNull() ?: return null
    if (only.isRunToolCallMessage()) return null
    return only
}

/** What decides whether a run may fold. */
@Immutable
internal class RunFoldInputs(val runId: String?, val hasWorkSteps: Boolean, val awaitingApproval: Boolean)

/**
 * Only a run with an id and work steps (reasoning, tool calls) folds, and never while one of its
 * requests waits on the person (letta-mobile-sieej). Working does not hold it open: the summary line
 * says "Working…", and a run folded the same way working and settled keeps its shape as it settles.
 */
internal fun runFolds(inputs: RunFoldInputs): Boolean {
    if (inputs.runId == null || !inputs.hasWorkSteps) return false
    return !inputs.awaitingApproval
}

/** The run's plan, or null when it has nothing to draw. */
@Composable
private fun rememberRunBlockPlan(messages: ImmutableList<UiMessage>, context: ChatRowContext): RunBlockPlan? {
    if (messages.isEmpty()) return null
    val active = isRunStreaming(messages, context)
    val activity = remember(messages, active) { projectRunActivity(messages, active) } ?: return null
    val visible = remember(messages, context.displayOptions) { context.displayOptions.visible(messages) }
    val runId = messages.first().runId
    val awaitingApproval = remember(messages) { messages.hasWaitingApproval() }
    return RunBlockPlan(
        activity = activity,
        headerPinned = activity.isActive || context.isNewest || awaitingApproval,
        visible = visible,
        runId = runId,
        messageCount = messages.size,
        toggledByOwner = runId != null && runId in context.itemState.collapsedRunIds,
        awaitingApproval = awaitingApproval,
    )
}

/** What a run's body shows: its steps, folded or not, and whether it lifts under the header. */
@Immutable
internal class RunBodyState(val messages: List<UiMessage>, val collapsed: Boolean, val lifted: Boolean)

/** The run's header and whether it shows; [fold] when the run can fold. */
@Immutable
internal data class RunHeaderState(
    val activity: RunActivityProjection,
    val visible: Boolean,
    val tall: Boolean = false,
    val fold: RunFold? = null,
    /** letta-mobile-sieej: the run waits on the person's approval; the line says so. */
    val awaitingApproval: Boolean = false,
)

/** A run's fold: whether it is folded, and the summary line's toggle. */
@Immutable
internal class RunFold(val collapsed: Boolean, val toggle: () -> Unit)

/** The run is the one streaming: the page streams and its streaming message is one of the run's. */
private fun isRunStreaming(messages: List<UiMessage>, context: ChatRowContext): Boolean {
    if (!context.itemState.isStreaming) return false
    val id = context.streamingMessageId ?: return false
    return messages.any { it.id == id || it.clientMessageId == id }
}

/** The run's summary line. Expands in and folds out as it shows. */
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
        RunSummaryLine(state)
    }
}

/**
 * The run's steps under its header. A settled run's body lifts [ChatRowSpacing.settledRunBodyLift]
 * up under the header's floor, easing (legacy jqiu3: by layout, so the lifted distance leaves no
 * band below).
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
        RunSteps(remember(body.messages) { compactRunSteps(body.messages) }, body, row)
    }
}

/** Draws the content [distance] higher and reports it [distance] shorter (legacy pullUp). */
private fun Modifier.pullUp(distance: () -> Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = distance().roundToPx().coerceIn(0, placeable.height)
    layout(placeable.width, placeable.height - shift) { placeable.place(0, -shift) }
}

/**
 * The run's steps, each on the legacy row beat above it (RenderChatMessage's item padding). Prose
 * always shows; a work step shows only while the run is open, indented under its summary line, and
 * grows in and folds away (snapping under reduced motion) as the line toggles, so the prose around
 * it never jumps.
 */
@Composable
private fun RunSteps(steps: List<RunStep>, body: RunBodyState, row: RunRowScope) {
    Column(modifier = Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            key(step.key) {
                val positioned = PositionedRunStep(step, stepPosition(index, steps.size))
                if (step.isWork()) {
                    RunWorkStep(positioned, body, row)
                } else {
                    RunStepRow(positioned, row, Modifier.padding(top = positioned.top()))
                }
            }
        }
    }
}

@Composable
private fun RunWorkStep(positioned: PositionedRunStep, body: RunBodyState, row: RunRowScope) {
    val motion = ChatRowMotion(LocalReducedMotion.current)
    AnimatedVisibility(
        visible = !body.collapsed,
        enter = motion.expansionEnter(),
        exit = motion.expansionExit(),
        label = "runWorkStep",
    ) {
        val indent = if (body.lifted) ChatRowSpacing.runStepIndent else 0.dp
        RunStepRow(
            positioned,
            row,
            Modifier.testTag(ChatRowTestTags.RUN_WORK_STEP).padding(start = indent, top = positioned.top()),
        )
    }
}

private fun stepPosition(index: Int, count: Int): GroupPosition {
    if (count == 1) return GroupPosition.None
    if (index == 0) return GroupPosition.First
    if (index == count - 1) return GroupPosition.Last
    return GroupPosition.Middle
}

/**
 * The beat above one message step (legacy chatMessageRowVerticalPadding): reasoning and tool steps
 * take the grouped beat, as do the middle and last of a run's steps; the first takes the section break.
 */
internal fun runStepTop(message: UiMessage, position: GroupPosition): Dp {
    if (message.isReasoning || !message.toolCalls.isNullOrEmpty()) return ChatRowSpacing.grouped
    if (position == GroupPosition.Middle || position == GroupPosition.Last) return ChatRowSpacing.grouped
    return ChatRowSpacing.ungrouped
}

/** A run step and its place among the run's visible steps (the legacy row's group position). */
@Immutable
private class PositionedRunStep(val step: RunStep, val position: GroupPosition) {
    /** The legacy row beat above it: a tool summary always takes the grouped beat. */
    fun top(): Dp {
        val message = (step as? RunStep.Message)?.message ?: return ChatRowSpacing.grouped
        return runStepTop(message, position)
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

/** Work the summary line stands for (a reasoning, the tool line), as opposed to the run's prose. */
internal fun RunStep.isWork(): Boolean {
    val message = (this as? RunStep.Message)?.message ?: return true
    return message.isReasoning
}

/** A message the summary line folds away: a reasoning, or one carrying the run's tool calls. */
private fun UiMessage.isRunWorkMessage(): Boolean {
    if (isReasoning) return true
    return isPlainAssistantStep()
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
