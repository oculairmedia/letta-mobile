package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import com.letta.mobile.ui.theme.LettaDimens
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.composer.ChatComposerPanel
import com.letta.mobile.ui.chat.surface.composer.LocalComposerImageAttacher
import com.letta.mobile.ui.chat.surface.composer.rememberComposerImageAttacher
import com.letta.mobile.ui.chat.surface.composer.LocalComposerCompanion
import com.letta.mobile.ui.chat.surface.sendflight.SendFlightLayer
import com.letta.mobile.ui.chat.surface.sendflight.rememberSendFlightActions
import com.letta.mobile.ui.chat.surface.sendflight.rememberSendFlightState
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_canvas_share_failed
import com.letta.mobile.ui.chat.surface.timeline.A2uiSurfaceStack
import com.letta.mobile.ui.chat.surface.timeline.ChatTimeline
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: the ONE chat page, shared by Android and desktop.
 *
 * It renders [port] in the presentation's [ChatSurfaceMode]: a panel docked over the canvas
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
 * @param dockGeometry where the docked panel sits and how big it is; owned and persisted by the
 *   host, changed through [onDockGeometryChange] as the person drags, resizes, minimises or
 *   resets it. A host that does not hoist it gets a panel that still moves for the session.
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
    dockGeometry: ChatDockGeometry = ChatDockGeometry.Default,
    onDockGeometryChange: (ChatDockGeometry) -> Unit = {},
) {
    // Lifecycle-aware on Android: a backgrounded app stops collecting, so the owner's
    // WhileSubscribed flows (the composer projection) can stop with it.
    val uiState by port.uiState.collectForChatSurface()
    val composer by port.composer.collectForChatSurface()
    val capabilities by port.capabilities.collectForChatSurface()
    val shareFailed = stringResource(Res.string.chat_surface_canvas_share_failed)
    // Hosts pass fresh lambdas per recomposition; these keep one instance so rows stay skippable.
    val stableOnIntent = rememberLatestIntent(onIntent)
    val stableHost = rememberStableHost(host)
    val stablePlatform = rememberStablePlatform(platform)
    val canvasActions = remember(port, shareFailed) { ChatCanvasActions(port.actions, stableOnIntent, shareFailed) }
    // With a canvas in hand, "open canvas" is a mode change, not the host's navigation.
    val effectiveHost = remember(stableHost, canvas != null) {
        if (canvas == null) stableHost else stableHost.copy(openCanvas = { stableOnIntent(ChatSurfaceIntent.OpenCanvas) })
    }
    val snackbars = rememberChatSurfaceSnackbars(uiState, port.actions)
    // One scroll position per conversation (and paged presentation), kept across mode changes.
    val conversationId = (uiState.conversationState as? ConversationState.Ready)?.conversationId
    ReleaseImagesOnConversationChange(conversationId)
    val listState = remember(conversationId, pagedTimeline) { LazyListState() }
    val dock = rememberChatDockState(dockGeometry, onDockGeometryChange)
    val frame = ChatSurfaceFrame(
        port = port,
        snackbars = snackbars,
        listState = listState,
        uiState = uiState,
        composer = composer,
        capabilities = capabilities,
        presentation = presentation,
        onIntent = stableOnIntent,
        host = effectiveHost,
        appearance = appearance,
        platform = stablePlatform,
        pagedTimeline = pagedTimeline,
    )
    // Picked images encode in the page's scope: a mode switch composes a different composer
    // panel, and the encode must outlive the one that started it.
    val imageAttacher = rememberComposerImageAttacher()
    // letta-mobile-cc25e: a sent prompt flies from the composer into its row over the whole page.
    CompositionLocalProvider(LocalComposerImageAttacher provides imageAttacher) {
        SendFlightLayer(rememberSendFlightState(), modifier) {
            if (canvas == null) {
                if (presentation.mode == ChatSurfaceMode.FullScreen) {
                    FullScreenPage(frame, Modifier.fillMaxSize(), opaque = false)
                } else {
                    DockedOverlay(frame, dock, Modifier.fillMaxSize())
                }
            } else {
                CanvasWithChat(frame, dock, { canvas(canvasActions) }, Modifier)
            }
        }
    }
}

/** Test tags for the page's own layers. */
internal object ChatSurfaceTags {
    const val TIMELINE_OVERLAY = "chat_surface_timeline_overlay"
}

/** One composition's worth of what every part of the page reads. */
@Immutable
private class ChatSurfaceFrame(
    val port: ChatSessionPort,
    val snackbars: SnackbarHostState,
    val listState: LazyListState,
    val uiState: ChatUiState,
    val composer: ChatComposerUiState,
    val capabilities: ChatSurfaceCapabilities,
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
 * The canvas fills the whole area and is always composed. Docked, the chat panel floats over
 * it wherever the person put it; full screen, the page covers it. Between the two the panel
 * grows into the page (and back) through [SurfaceMorphLayer].
 */
@Composable
private fun CanvasWithChat(frame: ChatSurfaceFrame, dock: ChatDockState, canvas: @Composable () -> Unit, modifier: Modifier) {
    val fullScreen = frame.mode == ChatSurfaceMode.FullScreen
    val progress = rememberSurfaceMorphProgress(fullScreen)
    val phase = surfaceMorphPhase(progress, fullScreen)
    Box(modifier.fillMaxSize()) {
        // Hidden from accessibility while the page covers it; it stays composed for its state.
        val canvasModifier = Modifier.fillMaxSize()
        Box(if (fullScreen) canvasModifier.clearAndSetSemantics { } else canvasModifier) { canvas() }
        when (phase) {
            SurfaceMorphPhase.FullScreen -> FullScreenPage(frame, Modifier.fillMaxSize(), opaque = true)
            SurfaceMorphPhase.Docked -> DockedOverlay(frame, dock, Modifier.fillMaxSize())
            SurfaceMorphPhase.Morphing -> SurfaceMorphLayer(
                progress = progress,
                dock = dock,
                content = morphContent(frame, dock),
                modifier = Modifier.fillMaxSize(),
                // Minimised there is no panel: the page grows from the mascot and its bar.
                fromPanel = !dock.geometry.collapsed,
            )
        }
    }
}

/**
 * Both ends of the morph, each drawn as it is at rest in its own mode. Minimised, the docked end
 * is just the bar at the bottom: the mascot is the page composer's companion by then.
 */
private fun morphContent(frame: ChatSurfaceFrame, dock: ChatDockState): SurfaceMorphContent = SurfaceMorphContent(
    docked = {
        if (dock.geometry.collapsed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { CollapsedComposer(frame) }
        } else {
            DockedPanelBody(dock, dockedPanelContent(frame, ChatSurfaceMode.Docked), Modifier.fillMaxSize())
        }
    },
    page = { FullPageBody(frame, ChatSurfaceMode.FullScreen) },
)

/** The movable, resizable chat panel: header, conversation, composer bar; or, minimised, the mascot over its bar. */
@Composable
private fun DockedOverlay(frame: ChatSurfaceFrame, dock: ChatDockState, modifier: Modifier) {
    DockedChatPanel(dock, dockedPanelContent(frame, frame.mode), modifier)
}

/** The panel's content, with its composer drawn for [composerMode]. */
private fun dockedPanelContent(frame: ChatSurfaceFrame, composerMode: ChatSurfaceMode): DockedPanelContent =
    DockedPanelContent(
        streaming = frame.uiState.isStreaming,
        conversation = { conversationModifier -> DockedReplyCard(dockedReplyParams(frame), conversationModifier) },
        composer = { Composer(frame, composerMode, Modifier.fillMaxWidth()) },
        collapsed = CollapsedDockContent(
            agentId = frame.uiState.agentId,
            agentName = frame.uiState.agentName,
            openAgent = frame.host.openAgentPane,
            editAgent = frame.host.editAgent,
            turn = { rememberCollapsedTurn(dockedReplyParams(frame)) },
            composer = { CollapsedComposer(frame) },
        ),
    )

private fun dockedReplyParams(frame: ChatSurfaceFrame): DockedReplyParams = DockedReplyParams(
    state = frame.uiState,
    pagedTimeline = frame.pagedTimeline,
    actions = frame.port.actions,
    capabilities = frame.capabilities,
    host = frame.host,
    appearance = frame.appearance,
    onIntent = frame.onIntent,
)

/**
 * The minimised dock's bar: the docked composer without its companion (the mascot stands above
 * it) and without the A2UI stack (the bubble's "needs your input" chip opens the panel for it).
 */
@Composable
private fun CollapsedComposer(frame: ChatSurfaceFrame) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SnackbarHost(frame.snackbars, Modifier.fillMaxWidth())
        CompositionLocalProvider(LocalComposerCompanion provides false) {
            ComposerPanel(frame, ChatSurfaceMode.Docked, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FullScreenPage(frame: ChatSurfaceFrame, modifier: Modifier, opaque: Boolean) {
    if (opaque) {
        // A Surface also stops touches reaching the canvas underneath.
        Surface(modifier, color = MaterialTheme.colorScheme.background) { FullPageBody(frame, frame.mode) }
    } else {
        Box(modifier) { FullPageBody(frame, frame.mode) }
    }
}

/** The timeline over the composer, inside the host's page background when it has one. */
@Composable
private fun FullPageBody(frame: ChatSurfaceFrame, composerMode: ChatSurfaceMode) {
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            TimelineWithOverlay(frame, Modifier.weight(1f).fillMaxWidth())
            Composer(frame, composerMode, Modifier.fillMaxWidth().reportComposerHeight(frame, composerMode))
        }
    }
    val background = frame.platform.pageBackground
    if (background != null) background(content) else content()
}

/** Reports the full-screen composer's height to [ChatSurfacePlatform.onComposerHeightChange]. */
@Composable
private fun Modifier.reportComposerHeight(frame: ChatSurfaceFrame, composerMode: ChatSurfaceMode): Modifier {
    val onHeight = frame.platform.onComposerHeightChange
    if (onHeight == null || composerMode != ChatSurfaceMode.FullScreen) return this
    val density = LocalDensity.current
    return onSizeChanged { size -> onHeight(with(density) { size.height.toDp() }) }
}

/** The timeline, with the host's [ChatSurfacePlatform.timelineOverlay] drawn over its top. */
@Composable
private fun TimelineWithOverlay(frame: ChatSurfaceFrame, modifier: Modifier) {
    Box(modifier) {
        ChatTimeline(
            state = frame.uiState,
            pagedTimeline = frame.pagedTimeline,
            actions = frame.port.actions,
            capabilities = frame.capabilities,
            host = frame.host,
            appearance = frame.appearance,
            modifier = Modifier.fillMaxSize(),
            listState = frame.listState,
        )
        frame.platform.timelineOverlay?.let { overlay ->
            Box(Modifier.fillMaxSize().testTag(ChatSurfaceTags.TIMELINE_OVERLAY), contentAlignment = Alignment.TopCenter) {
                overlay()
            }
        }
    }
}

/**
 * The composer with what must stay visible in every mode above it: the page's one snackbar host
 * and, while docked (no timeline on screen), the A2UI surfaces.
 */
@Composable
private fun Composer(frame: ChatSurfaceFrame, mode: ChatSurfaceMode, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        SnackbarHost(frame.snackbars, Modifier.fillMaxWidth())
        if (mode == ChatSurfaceMode.Docked) {
            A2uiSurfaceStack(
                surfaces = frame.uiState.a2uiSurfaces,
                resolvedActionCounters = frame.uiState.a2uiResolvedActionCounters,
                actions = frame.port.actions,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
            )
        }
        ComposerPanel(frame, mode, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ComposerPanel(frame: ChatSurfaceFrame, mode: ChatSurfaceMode, modifier: Modifier) {
    ChatComposerPanel(
        composer = frame.composer,
        uiState = frame.uiState,
        actions = rememberSendFlightActions(frame.port.actions, frame.composer.text),
        capabilities = frame.capabilities,
        host = frame.host,
        platform = frame.platform,
        mode = mode,
        onIntent = frame.onIntent,
        modifier = modifier,
    )
}
