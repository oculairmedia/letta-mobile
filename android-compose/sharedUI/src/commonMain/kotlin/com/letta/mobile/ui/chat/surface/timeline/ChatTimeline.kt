package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.letta.mobile.data.chat.tools.steps.ToolStepExpansions
import com.letta.mobile.ui.chat.tools.LocalToolStepExpansions
import com.letta.mobile.ui.chat.surface.timeline.rows.ToolPreviewPanes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatImageViewer
import com.letta.mobile.ui.chat.surface.timeline.rows.LocalApprovalStepper
import com.letta.mobile.ui.chat.surface.timeline.rows.rememberApprovalStepper
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.mascot.mascotAvailable
import com.letta.mobile.ui.a11y.AccessibilityTraversal
import com.letta.mobile.ui.a11y.accessibilityTraversal
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The shared chat timeline: status/empty/welcome states, the message
 * list (paged when [pagedTimeline] is non-null, else `state.messages`), and every row.
 *
 * Owns the page-level overlays the rows ask for: the image viewer, the A2UI surface stack, the
 * goal card and the pinch read-out. Snackbars are the page's (ChatSurface), so they show in
 * every mode. Rows are drawn only through the row seam.
 */
@Suppress("LongParameterList") // Keep the shared page's state, capabilities and host bindings explicit.
@Composable
internal fun ChatTimeline(
    state: ChatUiState,
    pagedTimeline: CanonicalTimelinePresentation?,
    actions: ChatActions,
    capabilities: ChatSurfaceCapabilities,
    host: ChatSurfaceHost,
    appearance: ChatSurfaceAppearance,
    modifier: Modifier = Modifier,
    /** The page's scroll position; hoisted so it survives docking and expanding the chat. */
    listState: LazyListState = rememberLazyListState(),
    /** The common session capability to accept text; starter prompts send immediately. */
    canSendStarterPrompts: Boolean = true,
    /**
     * Host chrome floating over the timeline's top edge (ChatSurfacePlatform.topChromeInset). The
     * list scrolls under it; everything that rests at the top (the oldest row, the goal card, the
     * loading, welcome and failure states) rests below it.
     */
    topInset: Dp = 0.dp,
) {
    var viewer by remember { mutableStateOf<ImageViewerRequest?>(null) }
    val latestState = rememberUpdatedState(state)
    val rowCallbacks = rememberRowCallbacks(actions, host) { images, index -> viewer = ImageViewerRequest(images, index) }
    // letta-mobile-bzvro.9: an error card's Retry reads the newest prompt when pressed.
    val callbacks = remember(rowCallbacks) {
        rowCallbacks.withLastPrompt { latestState.value.messages.lastOrNull { it.role == "user" } }
    }
    val (pinch, pinchModifier) = rememberTimelinePinch(
        enabled = capabilities.fontScale,
        committedScale = TextScale(appearance.fontScale),
        range = appearance.fontScaleRange,
        onCommit = actions::setFontScale,
    )
    // Rows lay out at the resting scale only: the live gesture is the list layer's (TimelineListFrame).
    val fontScale = pinch.restingScale(TextScale(appearance.fontScale))
    // A host that already scales text (desktop, via density) leaves rows only a pending pinch's delta.
    val rowFontScale = if (appearance.fontScaleAppliedByHost) fontScale / appearance.fontScale else fontScale
    val contexts = rememberRowContexts(state, capabilities, appearance, rowFontScale)
    val bindings = remember(contexts, callbacks, pinch) { TimelineRowBindings(contexts, callbacks, pinch) }
    var a2uiHeight by remember { mutableStateOf(0.dp) }
    val bottomReserve = if (state.a2uiSurfaces.isNotEmpty()) a2uiHeight else 0.dp
    val goalActions = remember(actions, capabilities.goals) { GoalCardActions.of(actions, capabilities) }

    Box(modifier = modifier.accessibilityTraversal(AccessibilityTraversal.TIMELINE).then(pinchModifier)) {
        Column(Modifier.fillMaxSize()) {
            val goal = state.goalStatus
            // letta-mobile-bglj6.1.22: on Touch the goal card sits at thumb reach over the composer, as
            // Android's legacy composer column put it; a pointer host keeps it at the top.
            val goalAtBottom = touchStyle()
            TimelineGoalCard(state, goalActions, topInset, bottom = false)
            // The goal card, when there is one on top, already rests below the chrome; the list starts under it.
            val bodyTopInset = if (goal != null && !goalAtBottom) 0.dp else topInset
            // letta-mobile-bzvro.14: several approvals waiting at once are answered one at a time.
            // A host that keeps no tool step expansions (no shell around it) still keeps them for the timeline's life.
            val expansions = LocalToolStepExpansions.current ?: remember { ToolStepExpansions() }
            CompositionLocalProvider(
                LocalApprovalStepper provides rememberApprovalStepper(state.messages, actions),
                LocalToolStepExpansions provides expansions,
            ) {
                TimelineBody(
                    TimelineBodyParams(
                        state, pagedTimeline, actions, capabilities, appearance, bindings, bottomReserve, listState,
                        canSendStarterPrompts = canSendStarterPrompts,
                        topReserve = bodyTopInset,
                    ),
                    Modifier.weight(1f).fillMaxWidth(),
                )
                // The file or memory entry a tool step's chip or a path in its output opens, in the window's detail pane.
                ToolPreviewPanes { latestState.value.messages.flatMap { it.toolCalls.orEmpty() } }
            }
            TimelineGoalCard(state, goalActions, topInset, bottom = true)
        }
        TimelineA2uiOverlay(state, actions) { a2uiHeight = it }
        TimelinePinchOverlay(pinch, appearance, topInset)
        TimelineImageViewerOverlay(viewer) { viewer = null }
    }
}

/** An open image viewer: which images, starting where. */
@Immutable
private class ImageViewerRequest(val images: List<UiImageAttachment>, val initialIndex: Int)

@Suppress("LongParameterList") // Private carrier for the distinct values the shared body renders.
@Immutable
private class TimelineBodyParams(
    val state: ChatUiState,
    val pagedTimeline: CanonicalTimelinePresentation?,
    val actions: ChatActions,
    val capabilities: ChatSurfaceCapabilities,
    val appearance: ChatSurfaceAppearance,
    val bindings: TimelineRowBindings,
    val bottomReserve: Dp,
    val listState: LazyListState,
    val canSendStarterPrompts: Boolean = true,
    /** Host chrome floating over the body's top: the list scrolls under it, the other phases rest below it. */
    val topReserve: Dp = 0.dp,
) {
    /** The mascot's pencil on the welcome hero, bound by the page host. */
    val editAgent: (() -> Unit)? get() = bindings.callbacks.host.editAgent
}

@Composable
private fun ColumnScope.TimelineGoalCard(
    state: ChatUiState,
    goalActions: GoalCardActions,
    topInset: Dp,
    bottom: Boolean,
) {
    val goal = state.goalStatus ?: return
    if (touchStyle() != bottom) return
    val modifier = Modifier.align(Alignment.CenterHorizontally)
    GoalStatusCard(
        goal,
        state.isGoalStatusLoading,
        goalActions,
        if (bottom) modifier else modifier.padding(top = topInset),
    )
}

@Composable
private fun BoxScope.TimelineA2uiOverlay(
    state: ChatUiState,
    actions: ChatActions,
    onA2uiHeight: (Dp) -> Unit,
) {
    val density = LocalDensity.current
    A2uiSurfaceStack(
        surfaces = state.a2uiSurfaces,
        resolvedActionCounters = state.a2uiResolvedActionCounters,
        actions = actions,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)
            .onSizeChanged { onA2uiHeight(with(density) { it.height.toDp() }) },
    )
}

@Composable
private fun BoxScope.TimelinePinchOverlay(
    pinch: TimelinePinchScale,
    appearance: ChatSurfaceAppearance,
    topInset: Dp,
) {
    PinchScaleReadout(
        pinch,
        TextScale(appearance.fontScale),
        Modifier.align(Alignment.TopCenter).padding(top = topInset + LettaDimens.Space.lg),
    )
}

@Composable
private fun BoxScope.TimelineImageViewerOverlay(
    viewer: ImageViewerRequest?,
    onViewerDismiss: () -> Unit,
) {
    viewer?.let { request ->
        ChatImageViewer(images = request.images, initialIndex = request.initialIndex, onDismiss = onViewerDismiss)
    }
}

/** The one body the current [ChatTimelinePhase] calls for. */
@Composable
private fun TimelineBody(params: TimelineBodyParams, modifier: Modifier) {
    val state = params.state
    // As Android's legacy page pads its loading, failure and starter phases (ChatScreenLayout).
    val resting = modifier.padding(top = params.topReserve)
    when (val phase = chatTimelinePhaseOf(
        state = state,
        paged = params.pagedTimeline != null,
        canSendStarterPrompts = params.canSendStarterPrompts,
    )) {
        ChatTimelinePhase.Loading -> TimelineLoading(state.agentId, resting)
        is ChatTimelinePhase.Failed -> TimelineStatusPanel(phase.message, params.actions::retryLoad, resting)
        is ChatTimelinePhase.Empty -> when {
            phase.waiting -> TimelineWaitingState(resting)
            phase.showWelcome -> TimelineWelcome(
                state.agentName,
                phase.hasConversation,
                params.actions::sendText,
                resting,
                state.agentId,
                params.editAgent,
            )
            else -> TimelineEmptyState(resting)
        }
        ChatTimelinePhase.Ready -> TimelineList(params, modifier)
    }
}

@Composable
private fun TimelineList(params: TimelineBodyParams, modifier: Modifier) {
    val paged = params.pagedTimeline
    // The agent's mascot beside the composer shows its thinking; the row would say it twice. On a
    // phone the composer carries the thinking token even without a mascot, as the legacy composer
    // did (letta-mobile-ckmub.1, S4), so the row is the pointer hosts' alone.
    val showThinkingRow = !mascotAvailable(params.state.agentId) && !touchStyle()
    if (paged == null) {
        LegacyTimelineList(
            LegacyTimelineParams(
                state = params.state,
                actions = params.actions,
                capabilities = params.capabilities,
                appearance = params.appearance,
                bindings = params.bindings,
                bottomReserve = params.bottomReserve,
                listState = params.listState,
                showThinkingRow = showThinkingRow,
                topReserve = params.topReserve,
            ),
            modifier,
        )
        return
    }
    PagedTimelineList(
        PagedTimelineParams(
            presentation = paged,
            agentId = params.state.agentId,
            thinking = params.state.isAgentTyping && showThinkingRow,
            thinkingMessages = params.state.messages,
            bindings = params.bindings,
            bottomReserve = params.bottomReserve,
            listState = params.listState,
            emptyContent = {
                TimelineEmptyContent(params)
            },
            topReserve = params.topReserve,
        ),
        modifier,
    )
}

@Composable
private fun TimelineEmptyContent(params: TimelineBodyParams) {
    when (val phase = chatTimelineEmptyPhaseOf(params.state, params.canSendStarterPrompts)) {
        ChatTimelinePhase.Loading -> TimelineLoading(params.state.agentId)
        is ChatTimelinePhase.Failed -> TimelineStatusPanel(phase.message, params.actions::retryLoad)
        is ChatTimelinePhase.Empty -> when {
            phase.waiting -> TimelineWaitingState()
            phase.showWelcome -> TimelineWelcome(
                params.state.agentName,
                phase.hasConversation,
                params.actions::sendText,
                agentId = params.state.agentId,
                onEditAgent = params.editAgent,
            )
            else -> TimelineEmptyState()
        }
        ChatTimelinePhase.Ready -> TimelineEmptyState()
    }
}
