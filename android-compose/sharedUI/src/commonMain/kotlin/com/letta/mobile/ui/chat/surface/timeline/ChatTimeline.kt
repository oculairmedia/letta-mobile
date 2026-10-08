package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
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
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatImageViewer
import com.letta.mobile.ui.mascot.mascotAvailable
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-bglj6.1: the shared chat timeline: status/empty/welcome states, the message
 * list (paged when [pagedTimeline] is non-null, else `state.messages`), and every row.
 *
 * Owns the page-level overlays the rows ask for: the image viewer, the A2UI surface stack, the
 * goal card and the pinch read-out. Snackbars are the page's (ChatSurface), so they show in
 * every mode. Rows are drawn only through the row seam.
 */
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
    val density = LocalDensity.current
    val bottomReserve = if (state.a2uiSurfaces.isNotEmpty()) a2uiHeight else 0.dp

    Box(modifier = modifier.then(pinchModifier)) {
        Column(Modifier.fillMaxSize()) {
            val goal = state.goalStatus
            val goalActions = remember(actions, capabilities.goals) { GoalCardActions.of(actions, capabilities) }
            // letta-mobile-bglj6.1.22: on Touch the goal card sits at thumb reach over the composer, as
            // Android's legacy composer column put it; a pointer host keeps it at the top.
            val goalAtBottom = touchStyle()
            if (goal != null && !goalAtBottom) {
                GoalStatusCard(
                    goal,
                    state.isGoalStatusLoading,
                    goalActions,
                    Modifier.align(Alignment.CenterHorizontally).padding(top = topInset),
                )
            }
            // The goal card, when there is one on top, already rests below the chrome; the list starts under it.
            val bodyTopInset = if (goal != null && !goalAtBottom) 0.dp else topInset
            TimelineBody(
                TimelineBodyParams(
                    state, pagedTimeline, actions, capabilities, appearance, bindings, bottomReserve, listState, host.editAgent,
                    topReserve = bodyTopInset,
                ),
                Modifier.weight(1f).fillMaxWidth(),
            )
            if (goal != null && goalAtBottom) {
                GoalStatusCard(goal, state.isGoalStatusLoading, goalActions, Modifier.align(Alignment.CenterHorizontally))
            }
        }
        A2uiSurfaceStack(
            surfaces = state.a2uiSurfaces,
            resolvedActionCounters = state.a2uiResolvedActionCounters,
            actions = actions,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)
                .onSizeChanged { a2uiHeight = with(density) { it.height.toDp() } },
        )
        PinchScaleReadout(
            pinch,
            TextScale(appearance.fontScale),
            Modifier.align(Alignment.TopCenter).padding(top = topInset + LettaDimens.Space.lg),
        )
        viewer?.let { request ->
            ChatImageViewer(images = request.images, initialIndex = request.initialIndex, onDismiss = { viewer = null })
        }
    }
}

/** An open image viewer: which images, starting where. */
@Immutable
private class ImageViewerRequest(val images: List<UiImageAttachment>, val initialIndex: Int)

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
    /** The mascot's pencil on the welcome hero. */
    val editAgent: (() -> Unit)? = null,
    /** Host chrome floating over the body's top: the list scrolls under it, the other phases rest below it. */
    val topReserve: Dp = 0.dp,
)

/** The one body the current [ChatTimelinePhase] calls for. */
@Composable
private fun TimelineBody(params: TimelineBodyParams, modifier: Modifier) {
    val state = params.state
    // As Android's legacy page pads its loading, failure and starter phases (ChatScreenLayout).
    val resting = modifier.padding(top = params.topReserve)
    when (val phase = chatTimelinePhaseOf(state, paged = params.pagedTimeline != null)) {
        ChatTimelinePhase.Loading -> TimelineLoading(state.agentId, resting)
        is ChatTimelinePhase.Failed -> TimelineStatusPanel(phase.message, params.actions::retryLoad, resting)
        is ChatTimelinePhase.Welcome ->
            TimelineWelcome(state.agentName, phase.hasConversation, params.actions::sendText, resting, state.agentId, params.editAgent)
        ChatTimelinePhase.Ready -> TimelineList(params, modifier)
    }
}

@Composable
private fun TimelineList(params: TimelineBodyParams, modifier: Modifier) {
    val paged = params.pagedTimeline
    // The agent's mascot beside the composer shows its thinking; the row would say it twice.
    val showThinkingRow = !mascotAvailable(params.state.agentId)
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
                TimelineWelcome(
                    params.state.agentName,
                    hasConversation = true,
                    onStarterPrompt = params.actions::sendText,
                    agentId = params.state.agentId,
                    onEditAgent = params.editAgent,
                )
            },
            topReserve = params.topReserve,
        ),
        modifier,
    )
}
