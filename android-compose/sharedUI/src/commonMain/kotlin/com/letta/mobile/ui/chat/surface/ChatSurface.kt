package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import com.letta.mobile.ui.chat.session.ChatDockRect
import androidx.compose.foundation.lazy.LazyListState
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.TouchComposerDimens
import com.letta.mobile.ui.canvas.LocalCanvasChromeBottomInset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.letta.mobile.ui.chat.surface.ambient.ChatAmbient
import com.letta.mobile.ui.chat.surface.ambient.LocalAmbientGlowShaders
import com.letta.mobile.ui.chat.surface.ambient.LocalChatWorkingCueAnimated
import com.letta.mobile.ui.chat.surface.ambient.rememberAmbientGlowShaders
import com.letta.mobile.ui.chat.surface.ambient.rememberChatAmbient
import com.letta.mobile.ui.chat.surface.composer.CompanionLayer
import com.letta.mobile.ui.chat.surface.composer.CompanionSeatAnchors
import com.letta.mobile.ui.chat.surface.composer.CompanionSeatOverlay
import com.letta.mobile.ui.chat.surface.composer.LocalCompanionLayer
import com.letta.mobile.ui.chat.surface.composer.LocalCompanionSeatAnchors
import com.letta.mobile.ui.chat.surface.composer.LocalComposerPrimary
import com.letta.mobile.ui.chat.surface.composer.ComposerFocusHandoff
import com.letta.mobile.ui.chat.surface.composer.LocalComposerFocusHandoff
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
 *   Null while the host is still reading the person's saved placement: the dock is not drawn
 *   at all until it is known, so it never appears at the default spot and then jumps.
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
    dockGeometry: ChatDockGeometry? = ChatDockGeometry.Default,
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
    val dockReady = dockGeometry != null
    val dock = rememberChatDockState(dockGeometry ?: ChatDockGeometry.Default, onDockGeometryChange)
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
    // The page's one composer-companion seat; the composers only say where it should stand.
    val companionAnchors = remember { CompanionSeatAnchors() }
    val focusHandoff = remember { ComposerFocusHandoff() }
    // The thinking glow's shader, compiled once for the page rather than at every run's start.
    val glowShaders = rememberAmbientGlowShaders()
    // letta-mobile-cc25e: a sent prompt flies from the composer into its row over the whole page.
    CompositionLocalProvider(
        LocalComposerImageAttacher provides imageAttacher,
        LocalCompanionSeatAnchors provides companionAnchors,
        LocalComposerFocusHandoff provides focusHandoff,
        LocalChatPlatformStyle provides appearance.platformStyle,
        LocalAmbientGlowShaders provides glowShaders,
    ) {
        SendFlightLayer(rememberSendFlightState(), modifier) {
            if (appearance.platformStyle == ChatPlatformStyle.Touch) {
                // Phones: the canvas over a flush chat bar with a floating chat head, no panel.
                TouchCanvasWithChat(frame, dock.takeIf { dockReady }, companionAnchors, canvas?.let { { it(canvasActions) } })
            } else if (canvas == null) {
                val fullScreen = presentation.mode == ChatSurfaceMode.FullScreen
                Box(Modifier.fillMaxSize()) {
                    if (fullScreen) {
                        PageLayerLocals(primary = true) { FullScreenPage(frame, Modifier.fillMaxSize()) }
                    } else if (dockReady) {
                        DockedOverlay(frame, dock, Modifier.fillMaxSize(), SurfaceMorph.Docked, primary = true)
                    }
                    if (fullScreen || dockReady) CompanionSeat(frame, companionAnchors, dock = dock.takeIf { !fullScreen }) { if (fullScreen) 1f else 0f }
                }
            } else {
                CanvasWithChat(frame, dock.takeIf { dockReady }, companionAnchors, { canvas(canvasActions) }, Modifier)
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
 * grows into the page (and back): see ChatSurfaceMorph.
 *
 * Each layer is composed at one place for as long as it is on screen: the panel from rest
 * through the whole morph until the page covers it, the page from the first frame it starts
 * to fade in. Nothing the person is looking at is disposed and composed again at either end.
 */
@Composable
private fun CanvasWithChat(
    frame: ChatSurfaceFrame,
    /** Null until the host knows where the person put the dock: then only the canvas shows. */
    dock: ChatDockState?,
    companionAnchors: CompanionSeatAnchors,
    canvas: @Composable () -> Unit,
    modifier: Modifier,
) {
    val fullScreen = frame.mode == ChatSurfaceMode.FullScreen
    val progress = rememberSurfaceMorphProgress(fullScreen)
    val phase = surfaceMorphPhase(progress, fullScreen)
    val fraction: () -> Float = remember(progress) { { progress.value } }
    val morph = remember(fraction, phase) { SurfaceMorph(fraction, morphing = phase == SurfaceMorphPhase.Morphing) }
    // Read every frame, but each flips only once per trip.
    val showPanel by remember(progress, fullScreen) { derivedStateOf { !fullScreen || progress.value < 1f } }
    val showPage by remember(progress, fullScreen) { derivedStateOf { fullScreen || progress.value > 0f } }
    val panelPrimary by remember(progress) { derivedStateOf { progress.value < PRIMARY_HANDOFF } }
    Box(modifier.fillMaxSize()) {
        // Hidden from accessibility while the page covers it; it stays composed for its state.
        val canvasModifier = Modifier.fillMaxSize()
        Box(if (fullScreen) canvasModifier.clearAndSetSemantics { } else canvasModifier) { canvas() }
        MorphBackdrop(fraction)
        if (dock == null) {
            // The saved placement is still loading: no panel yet (and no page unless full screen).
            if (fullScreen) {
                PageLayerLocals(primary = true) { FullScreenPage(frame, Modifier.fillMaxSize()) }
                CompanionSeat(frame, companionAnchors) { 1f }
            }
            return@Box
        }
        if (showPanel) DockedOverlay(frame, dock, Modifier.fillMaxSize(), morph, primary = panelPrimary)
        if (showPage) {
            // The panel rises above the keyboard (DockedChatPanel pads for it); the page does not.
            val density = LocalDensity.current
            val imeDp = with(density) { WindowInsets.ime.getBottom(density).toDp().value }
            PageLayerLocals(primary = !panelPrimary) {
                MorphPageLayer({ w, h -> dock.rectIn(w, (h - imeDp).coerceAtLeast(0f)) }, morph, Modifier.fillMaxSize()) {
                    FullPageBody(frame, ChatSurfaceMode.FullScreen)
                }
            }
        }
        // The character moves the dock only while it sits on the resting dock, never mid-morph.
        CompanionSeat(frame, companionAnchors, dock = dock.takeIf { !fullScreen && !morph.morphing }, pageWeight = fraction)
    }
}

/**
 * letta-mobile-bglj6.1.9: the Touch page. The canvas fills the area above a flush chat bar pinned
 * to the bottom (the same draft and send as the full page); a chat head with the agent's mascot
 * floats over the canvas, snapped to a side, and the reply pops out of it. Swiping the bar up (or
 * its chevron) grows the bar into the full page; Back or swipe up on the page's bar comes back.
 * Without a canvas there is only the bar and the head.
 *
 * As on desktop each layer stays composed for as long as it is on screen: the bar layer until the
 * page covers it, the page from the first frame it fades in.
 */
@Composable
private fun TouchCanvasWithChat(
    frame: ChatSurfaceFrame,
    /** Null until the host knows where the person put the head; the bar shows regardless. */
    dock: ChatDockState?,
    companionAnchors: CompanionSeatAnchors,
    canvas: (@Composable () -> Unit)?,
) {
    val fullScreen = frame.mode == ChatSurfaceMode.FullScreen
    val progress = rememberSurfaceMorphProgress(fullScreen)
    val phase = surfaceMorphPhase(progress, fullScreen)
    val fraction: () -> Float = remember(progress) { { progress.value } }
    val morph = remember(fraction, phase) { SurfaceMorph(fraction, morphing = phase == SurfaceMorphPhase.Morphing) }
    val showBar by remember(progress, fullScreen) { derivedStateOf { !fullScreen || progress.value < 1f } }
    val showPage by remember(progress, fullScreen) { derivedStateOf { fullScreen || progress.value > 0f } }
    val barPrimary by remember(progress) { derivedStateOf { progress.value < PRIMARY_HANDOFF } }
    val bar = remember { TouchBarMetrics() }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        if (canvas != null) {
            // Laid out above the bar but for its rounded top, which the board runs on under so the
            // bar's corners show the board, as on the full page they show the page. The bar is the
            // canvas's bottom chrome, so its own foot (its tool bar) keeps above the whole bar; the
            // part the canvas is laid out above is consumed from its insets so it does not pad twice.
            val barDp = with(density) { bar.heightPx.toDp() }
            val reach = minOf(TouchComposerDimens.cornerReach, barDp)
            val clear = PaddingValues(bottom = barDp - reach)
            val canvasModifier = Modifier.fillMaxSize().padding(clear).consumeWindowInsets(clear).testTag(TOUCH_CANVAS_TAG)
            Box(if (fullScreen) canvasModifier.clearAndSetSemantics { } else canvasModifier) {
                CompositionLocalProvider(LocalCanvasChromeBottomInset provides barDp) { canvas() }
            }
        }
        MorphBackdrop(fraction)
        if (showBar) {
            CompositionLocalProvider(
                LocalCompanionLayer provides CompanionLayer.Docked,
                LocalComposerPrimary provides barPrimary,
                LocalChatWorkingCueAnimated provides false,
            ) {
                TouchDockLayer(
                    bar = bar,
                    morph = morph,
                    topChromeInset = frame.platform.topChromeInset,
                    head = dock?.let { touchHeadContent(frame, it) },
                    composer = { DockComposer(frame, ChatSurfaceMode.Docked, collapsed = true) },
                )
            }
        }
        if (showPage) {
            PageLayerLocals(primary = !barPrimary) {
                MorphPageLayer(
                    from = { w, h ->
                        val barHeight = with(density) { bar.heightPx.toDp().value }
                        ChatDockRect(0f, (h - barHeight).coerceAtLeast(0f), w, barHeight)
                    },
                    morph = morph,
                    modifier = Modifier.fillMaxSize(),
                    rounded = false,
                ) {
                    FullPageBody(frame, ChatSurfaceMode.FullScreen)
                }
            }
        }
        CompanionSeat(frame, companionAnchors, interactive = fullScreen, pageWeight = fraction)
    }
}

/** What the chat head shows and does, from the page's frame. */
private fun touchHeadContent(frame: ChatSurfaceFrame, dock: ChatDockState): TouchHeadContent = TouchHeadContent(
    dock = dock,
    agentId = frame.uiState.agentId,
    agentName = frame.uiState.agentName,
    openChat = { frame.onIntent(ChatSurfaceIntent.Expand) },
    openAgent = frame.host.openAgentPane,
    turn = { rememberCollapsedTurn(dockedReplyParams(frame)) },
    ambient = { rememberChatAmbient(frame.uiState) },
)

/** The page's one composer-companion seat, over both layers. */
@Composable
private fun CompanionSeat(
    frame: ChatSurfaceFrame,
    anchors: CompanionSeatAnchors,
    interactive: Boolean = true,
    /** The movable dock: grabbing the character where it sits on the dock moves the dock. */
    dock: ChatDockState? = null,
    pageWeight: () -> Float,
) {
    CompanionSeatOverlay(
        anchors = anchors,
        agentId = frame.uiState.agentId,
        pageWeight = pageWeight,
        // Over the Touch chat head the character is the head's: its own gestures (drag, tap, long press) win.
        onClick = frame.host.openAgentPane.takeIf { interactive },
        onEdit = frame.host.editAgent.takeIf { interactive },
        onDockDrag = dock?.let { it::drag },
    )
}

/** Marks [content] as the page layer; [primary] while it is the one the person is arriving at. */
@Composable
private fun PageLayerLocals(primary: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalCompanionLayer provides CompanionLayer.Page,
        LocalComposerPrimary provides primary,
        content = content,
    )
}

/**
 * The movable, resizable chat panel: header, conversation, composer bar; or, minimised, the
 * mascot over its bar. [morph] grows it into the page.
 */
@Composable
private fun DockedOverlay(frame: ChatSurfaceFrame, dock: ChatDockState, modifier: Modifier, morph: SurfaceMorph, primary: Boolean) {
    // While it grows into the page it keeps its own docked composer.
    val composerMode = if (frame.mode == ChatSurfaceMode.FullScreen) ChatSurfaceMode.Docked else frame.mode
    // The canvas chat window's thinking cue is the ambient glow, so its rows keep still.
    val ambient = rememberChatAmbient(frame.uiState)
    CompositionLocalProvider(
        LocalCompanionLayer provides CompanionLayer.Docked,
        LocalComposerPrimary provides primary,
        LocalChatWorkingCueAnimated provides false,
    ) {
        DockedChatPanel(dock, dockedPanelContent(frame, composerMode, ambient), modifier, morph)
    }
}

/** The panel's content, with its composer drawn for [composerMode]. */
private fun dockedPanelContent(frame: ChatSurfaceFrame, composerMode: ChatSurfaceMode, ambient: ChatAmbient): DockedPanelContent =
    DockedPanelContent(
        ambient = ambient,
        conversation = { conversationModifier -> DockedReplyCard(dockedReplyParams(frame), conversationModifier) },
        composer = { collapsed -> DockComposer(frame, composerMode, collapsed) },
        collapsed = CollapsedDockContent(
            agentId = frame.uiState.agentId,
            agentName = frame.uiState.agentName,
            openAgent = frame.host.openAgentPane,
            editAgent = frame.host.editAgent,
            turn = { rememberCollapsedTurn(dockedReplyParams(frame)) },
            ambient = ambient,
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
 * The dock's composer bar, open or minimised: one composition either way, so folding the dock
 * leaves the prompt (its text, focus and caret) alone. It never has a companion beside it: open,
 * the mascot sits in the panel's top-centre badge; minimised, it stands above the bar. So the bar
 * keeps the panel's whole width either way. Minimised it has no A2UI stack (the bubble's "needs
 * your input" chip opens the panel for it).
 */
@Composable
private fun DockComposer(frame: ChatSurfaceFrame, mode: ChatSurfaceMode, collapsed: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SnackbarHost(frame.snackbars, Modifier.fillMaxWidth())
        if (!collapsed && mode == ChatSurfaceMode.Docked) DockedA2uiStack(frame)
        CompositionLocalProvider(LocalComposerCompanion provides false) {
            ComposerPanel(frame, mode, Modifier.fillMaxWidth())
        }
    }
}

/** The full-screen page over the whole area, where there is no canvas to morph from. */
@Composable
private fun FullScreenPage(frame: ChatSurfaceFrame, modifier: Modifier) {
    Box(modifier) { FullPageBody(frame, frame.mode) }
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
            topInset = frame.platform.topChromeInset,
        )
        frame.platform.timelineOverlay?.let { overlay ->
            Box(
                Modifier.fillMaxSize().padding(top = frame.platform.topChromeInset).testTag(ChatSurfaceTags.TIMELINE_OVERLAY),
                contentAlignment = Alignment.TopCenter,
            ) {
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
        if (mode == ChatSurfaceMode.Docked) DockedA2uiStack(frame)
        ComposerPanel(frame, mode, Modifier.fillMaxWidth())
    }
}

/** The A2UI surfaces above the docked composer, where no timeline shows them. */
@Composable
private fun DockedA2uiStack(frame: ChatSurfaceFrame) {
    A2uiSurfaceStack(
        surfaces = frame.uiState.a2uiSurfaces,
        resolvedActionCounters = frame.uiState.a2uiResolvedActionCounters,
        actions = frame.port.actions,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
    )
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

/**
 * Past this share of the morph the page's prompt field is the one the person is arriving at: it,
 * not the panel's, tells the send flight and the mascot's gaze where the prompt is.
 */
private const val PRIMARY_HANDOFF = 0.5f
