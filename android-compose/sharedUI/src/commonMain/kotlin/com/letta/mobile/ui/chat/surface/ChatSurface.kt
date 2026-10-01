package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.ChatSurfaceDimens
import com.letta.mobile.ui.theme.LettaDimens
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.composer.ChatComposerPanel
import com.letta.mobile.ui.chat.surface.sendflight.SendFlightLayer
import com.letta.mobile.ui.chat.surface.sendflight.rememberSendFlightActions
import com.letta.mobile.ui.chat.surface.sendflight.rememberSendFlightState
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_canvas_share_failed
import com.letta.mobile.ui.chat.surface.timeline.ChatTimeline
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: the ONE chat page, shared by Android and desktop.
 *
 * It renders [port] in the presentation's [ChatSurfaceMode]: a bar docked under the canvas
 * (the default view), the full-screen page, or (reserved) a floating panel. Every mode binds
 * to the same [port], so the draft, queued follow-ups and the run are the same object in each.
 *
 * With a [canvas], the canvas stays composed in every mode and the full-screen page is an
 * opaque layer over it, so collapsing back to the canvas finds the same board, camera and tool
 * (the canvas keeps much of that in composition). Swipe up on the full-screen prompt raises
 * [ChatSurfaceIntent.OpenCanvas]; the expand control on the dock raises
 * [ChatSurfaceIntent.Expand]. Neither navigates: the host reduces them with
 * `ChatSurfaceModeReducer`.
 *
 * Without a [canvas], [ChatSurfaceHost.openCanvas] (if any) is how the host leaves for its own
 * canvas route.
 *
 * @param presentation where the page is drawn; owned by the host, changed through [onIntent].
 * @param onIntent raises a mode transition.
 * @param pagedTimeline the canonical paged timeline when the owner has one; otherwise the
 *   timeline renders `ChatUiState.messages`.
 * @param canvas the host's canvas for this conversation, or null when the host has none here.
 */
@Composable
fun ChatSurface(
    port: ChatSessionPort,
    presentation: ChatSurfacePresentation,
    onIntent: (ChatSurfaceIntent) -> Unit,
    host: ChatSurfaceHost,
    modifier: Modifier = Modifier,
    appearance: ChatSurfaceAppearance = ChatSurfaceAppearance(),
    platform: ChatSurfacePlatform = ChatSurfacePlatform.Default,
    pagedTimeline: CanonicalTimelinePresentation? = null,
    canvas: (@Composable (ChatCanvasActions) -> Unit)? = null,
) {
    val uiState by port.uiState.collectAsState()
    val composer by port.composer.collectAsState()
    val shareFailed = stringResource(Res.string.chat_surface_canvas_share_failed)
    val canvasActions = remember(port, onIntent, shareFailed) { ChatCanvasActions(port.actions, onIntent, shareFailed) }
    // With a canvas in hand, "open canvas" is a mode change, not the host's navigation.
    val effectiveHost = remember(host, canvas != null, onIntent) {
        if (canvas == null) host else host.copy(openCanvas = { onIntent(ChatSurfaceIntent.OpenCanvas) })
    }
    val frame = ChatSurfaceFrame(
        port = port,
        uiState = uiState,
        composer = composer,
        presentation = presentation,
        onIntent = onIntent,
        host = effectiveHost,
        appearance = appearance,
        platform = platform,
        pagedTimeline = pagedTimeline,
    )
    // letta-mobile-cc25e: a sent prompt flies from the composer into its row over the whole page.
    SendFlightLayer(rememberSendFlightState(), modifier) {
        if (canvas == null) {
            if (presentation.mode == ChatSurfaceMode.FullScreen) {
                FullScreenPage(frame, Modifier.fillMaxSize(), opaque = false)
            } else {
                BoxWithConstraints {
                    DockedOverlay(frame, maxHeight * ChatSurfaceDimens.dockedReplyMaxHeightFraction, Modifier.align(Alignment.BottomCenter))
                }
            }
        } else {
            CanvasWithChat(frame, { canvas(canvasActions) }, Modifier)
        }
    }
}

/** One composition's worth of what every part of the page reads. */
@Immutable
private class ChatSurfaceFrame(
    val port: ChatSessionPort,
    val uiState: ChatUiState,
    val composer: ChatComposerUiState,
    val presentation: ChatSurfacePresentation,
    val onIntent: (ChatSurfaceIntent) -> Unit,
    val host: ChatSurfaceHost,
    val appearance: ChatSurfaceAppearance,
    val platform: ChatSurfacePlatform,
    val pagedTimeline: CanonicalTimelinePresentation?,
) {
    val mode: ChatSurfaceMode get() = presentation.mode
}

/**
 * The canvas fills the whole area and is always composed. Docked, the chat bar and the
 * current reply float over its bottom edge; full screen, the page covers it.
 */
@Composable
private fun CanvasWithChat(frame: ChatSurfaceFrame, canvas: @Composable () -> Unit, modifier: Modifier) {
    val fullScreen = frame.mode == ChatSurfaceMode.FullScreen
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Hidden from accessibility while the page covers it; it stays composed for its state.
        val canvasModifier = Modifier.fillMaxSize()
        Box(if (fullScreen) canvasModifier.clearAndSetSemantics { } else canvasModifier) { canvas() }
        if (fullScreen) {
            FullScreenPage(frame, Modifier.fillMaxSize(), opaque = true)
        } else {
            DockedOverlay(frame, maxHeight * ChatSurfaceDimens.dockedReplyMaxHeightFraction, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** The floating reply card over the floating chat bar, bottom-centred over the canvas. */
@Composable
private fun DockedOverlay(frame: ChatSurfaceFrame, replyMaxHeight: Dp, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        DockedReplyCard(
            DockedReplyParams(
                state = frame.uiState,
                pagedTimeline = frame.pagedTimeline,
                actions = frame.port.actions,
                capabilities = frame.port.capabilities,
                host = frame.host,
                appearance = frame.appearance,
                onIntent = frame.onIntent,
                maxHeight = replyMaxHeight,
            ),
            Modifier.padding(horizontal = LettaDimens.Space.lg),
        )
        DockedComposer(frame, Modifier.fillMaxWidth())
    }
}

@Composable
private fun FullScreenPage(frame: ChatSurfaceFrame, modifier: Modifier, opaque: Boolean) {
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            ChatTimeline(
                state = frame.uiState,
                pagedTimeline = frame.pagedTimeline,
                actions = frame.port.actions,
                capabilities = frame.port.capabilities,
                host = frame.host,
                appearance = frame.appearance,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Composer(frame, Modifier.fillMaxWidth())
        }
    }
    val background = frame.platform.pageBackground
    when {
        // A Surface also stops touches reaching the canvas underneath.
        opaque -> Surface(modifier, color = MaterialTheme.colorScheme.background) {
            if (background != null) background(content) else content()
        }
        background != null -> Box(modifier) { background(content) }
        else -> Box(modifier) { content() }
    }
}

@Composable
private fun DockedComposer(frame: ChatSurfaceFrame, modifier: Modifier) = Composer(frame, modifier)

@Composable
private fun Composer(frame: ChatSurfaceFrame, modifier: Modifier) {
    ChatComposerPanel(
        composer = frame.composer,
        uiState = frame.uiState,
        actions = rememberSendFlightActions(frame.port.actions, frame.composer.text),
        capabilities = frame.port.capabilities,
        host = frame.host,
        platform = frame.platform,
        mode = frame.mode,
        onIntent = frame.onIntent,
        modifier = modifier,
    )
}
