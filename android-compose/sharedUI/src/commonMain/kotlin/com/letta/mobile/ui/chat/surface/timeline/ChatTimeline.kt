package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-bglj6.1: the shared chat timeline: status/empty/welcome states, the message
 * list (paged when [pagedTimeline] is non-null, else `state.messages`), and every row.
 *
 * Owns the page-level overlays the rows ask for: the image viewer, the A2UI surface stack, the
 * goal card, the snackbars and the pinch read-out. Rows are drawn only through the row seam.
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
) {
    var viewer by remember { mutableStateOf<ImageViewerRequest?>(null) }
    val callbacks = rememberRowCallbacks(actions, host) { images, index -> viewer = ImageViewerRequest(images, index) }
    val (pinch, pinchModifier) = rememberTimelinePinch(
        enabled = capabilities.fontScale,
        committedScale = appearance.fontScale,
        onCommit = actions::setFontScale,
    )
    val fontScale = pinch.effectiveScale(appearance.fontScale)
    // A host that already scales text (desktop, via density) leaves rows only the live pinch delta.
    val rowFontScale = if (appearance.fontScaleAppliedByHost) fontScale / appearance.fontScale else fontScale
    val contexts = rememberRowContexts(state, capabilities, appearance, rowFontScale)
    val bindings = remember(contexts, callbacks) { TimelineRowBindings(contexts, callbacks) }
    val snackbars = remember { SnackbarHostState() }
    A2uiSnackbarEffect(state.a2uiActionSnackbar, snackbars, actions)
    ErrorSnackbarEffect(state.error, snackbars, actions)
    var a2uiHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val bottomReserve = if (state.a2uiSurfaces.isNotEmpty()) a2uiHeight else 0.dp

    Box(modifier = modifier.then(pinchModifier)) {
        Column(Modifier.fillMaxSize()) {
            state.goalStatus?.let { goal ->
                GoalStatusCard(goal, state.isGoalStatusLoading, actions::sendText, Modifier.align(Alignment.CenterHorizontally))
            }
            TimelineBody(
                TimelineBodyParams(state, pagedTimeline, actions, capabilities, appearance, bindings, bottomReserve),
                Modifier.weight(1f).fillMaxWidth(),
            )
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
        if (pinch.isPinching) {
            PinchScaleIndicator(fontScale, Modifier.align(Alignment.TopCenter).padding(top = LettaDimens.Space.lg))
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomReserve))
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
)

/** The one body the current [ChatTimelinePhase] calls for. */
@Composable
private fun TimelineBody(params: TimelineBodyParams, modifier: Modifier) {
    val state = params.state
    when (val phase = chatTimelinePhaseOf(state, paged = params.pagedTimeline != null)) {
        ChatTimelinePhase.Loading -> TimelineLoadingSkeleton(modifier)
        is ChatTimelinePhase.Failed -> TimelineStatusPanel(phase.message, params.actions::retryLoad, modifier)
        is ChatTimelinePhase.Welcome ->
            TimelineWelcome(state.agentName, phase.hasConversation, params.actions::sendText, modifier)
        ChatTimelinePhase.Ready -> TimelineList(params, modifier)
    }
}

@Composable
private fun TimelineList(params: TimelineBodyParams, modifier: Modifier) {
    val paged = params.pagedTimeline
    if (paged == null) {
        LegacyTimelineList(
            LegacyTimelineParams(
                state = params.state,
                actions = params.actions,
                capabilities = params.capabilities,
                appearance = params.appearance,
                bindings = params.bindings,
                bottomReserve = params.bottomReserve,
            ),
            modifier,
        )
        return
    }
    PagedTimelineList(
        PagedTimelineParams(
            presentation = paged,
            agentId = params.state.agentId,
            thinking = params.state.isAgentTyping,
            bindings = params.bindings,
            bottomReserve = params.bottomReserve,
            emptyContent = {
                TimelineWelcome(params.state.agentName, hasConversation = true, onStarterPrompt = params.actions::sendText)
            },
        ),
        modifier,
    )
}
