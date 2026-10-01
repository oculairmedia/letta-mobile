package com.letta.mobile.ui.chat.surface

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_dock_collapse
import com.letta.mobile.sharedui.resources.chat_surface_dock_move
import com.letta.mobile.sharedui.resources.chat_surface_dock_resize
import com.letta.mobile.ui.chat.session.ChatDockEdge
import com.letta.mobile.ui.chat.session.ChatDockFrame
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatDockGeometryMath
import com.letta.mobile.ui.chat.session.ChatDockLimits
import com.letta.mobile.ui.chat.session.ChatDockRect
import com.letta.mobile.ui.chat.surface.ambient.AmbientGlowPlacement
import com.letta.mobile.ui.chat.surface.ambient.ChatAmbient
import com.letta.mobile.ui.chat.surface.ambient.ChatPanelAmbientGlow
import com.letta.mobile.ui.chat.surface.composer.CompanionSeatAnchor
import com.letta.mobile.ui.chat.surface.composer.LocalCompanionSeatAnchors
import com.letta.mobile.ui.components.ResizeDirection
import com.letta.mobile.ui.components.movePointerIcon
import com.letta.mobile.ui.components.resizePointerIcon
import com.letta.mobile.ui.mascot.LocalMascotTransport
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.mascotAvailable
import com.letta.mobile.ui.theme.ChatMascotDimens
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.ChatSurfaceDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.stringResource

/*
 * letta-mobile-bglj6.1: the docked chat as a floating panel over the canvas. The person drags it
 * by its header, resizes it from its edges and corners (or the bottom-right grip on touch),
 * minimises it to the agent's mascot over the composer bar (CollapsedDock: no panel, the reply
 * arrives as a speech bubble) and double-clicks / double-taps the header to put it back
 * bottom-centre. Its placement is a ChatDockGeometry the host owns and persists.
 *
 * Only the panel takes pointer input: the area around it is left to the canvas underneath.
 */

/** The panel's working copy of the host's geometry; gestures update it, then report it. */
@Stable
internal class ChatDockState(initial: ChatDockGeometry) {
    var geometry: ChatDockGeometry by mutableStateOf(ChatDockGeometryMath.sanitize(initial))
        private set

    /** The minimised dock's measured height in dp (mascot row and bar), for placing it. */
    var collapsedHeightDp: Float by mutableFloatStateOf(ChatSurfaceDimens.dockCollapsedHeightEstimate.value)

    /**
     * The mascot row and the bar, measured as they are drawn: folding down for the first time,
     * the dock knows how tall it will end up before it gets there, so the fold lands without a
     * jump.
     */
    internal var minimisedTopDp: Float = Float.NaN
        set(value) {
            field = value
            refreshCollapsedHeight()
        }
    internal var barDp: Float = Float.NaN
        set(value) {
            field = value
            refreshCollapsedHeight()
        }

    private fun refreshCollapsedHeight() {
        if (minimisedTopDp.isNaN() || barDp.isNaN()) return
        val measured = minimisedTopDp + barDp
        if (measured != collapsedHeightDp) collapsedHeightDp = measured
    }

    /** A reset is animating towards its target. */
    internal var snapping: Boolean by mutableStateOf(false)

    internal var onChange: (ChatDockGeometry) -> Unit = {}

    /** The last laid-out frame; gestures resolve against it. */
    internal var frame: ChatDockFrame? = null

    /** The host's value as last seen. */
    private var lastFromHost: ChatDockGeometry = geometry

    /** What this state reported that the host has not handed back yet, oldest first. */
    private val unacknowledged = ArrayDeque<ChatDockGeometry>()

    /**
     * A value from the host (the saved placement arriving, or a change it made itself). Called
     * as the page composes, so a placement that arrives late is drawn where it belongs on its
     * very first frame. The host handing back what this state reported is not news: a gesture
     * already past it must not be pulled back.
     */
    internal fun sync(fromHost: ChatDockGeometry) {
        val next = ChatDockGeometryMath.sanitize(fromHost)
        if (next == lastFromHost) return
        lastFromHost = next
        val echo = unacknowledged.indexOf(next)
        if (echo >= 0) {
            repeat(echo + 1) { unacknowledged.removeFirst() }
            return
        }
        if (next != geometry) geometry = next
    }

    fun drag(dxDp: Float, dyDp: Float) {
        val frame = frame ?: return
        // The pointer takes over from a reset's glide: the dock follows it 1:1 from here.
        snapping = false
        update(ChatDockGeometryMath.drag(geometry, dxDp, dyDp, frame))
    }

    fun resize(edge: ChatDockEdge, dxDp: Float, dyDp: Float) {
        val frame = frame ?: return
        snapping = false
        update(ChatDockGeometryMath.resize(geometry, edge, dxDp, dyDp, frame))
    }

    fun toggleCollapsed() {
        update(if (geometry.collapsed) ChatDockGeometryMath.expand(geometry) else ChatDockGeometryMath.collapse(geometry))
    }

    /** Opens the panel again at its last expanded size; nothing when it is already open. */
    fun restore() {
        if (geometry.collapsed) update(ChatDockGeometryMath.expand(geometry))
    }

    /**
     * letta-mobile-bglj6.1.9: where the Touch chat head rests. It reuses the dock's anchors: [side]
     * 0 is the left edge and 1 the right, [lane] 0..1 is its height in the free band.
     */
    fun placeHead(side: Float, lane: Float) {
        update(geometry.copy(anchorX = side.coerceIn(0f, 1f), anchorY = lane.coerceIn(0f, 1f)))
    }

    fun reset() {
        val next = ChatDockGeometryMath.reset()
        if (next == geometry) return
        // Only a reset that moves the open panel glides; one that leaves it where it is (a
        // minimised dock at home opening) would start no glide to end the snapping.
        val frame = frame
        snapping = frame == null || openRect(next, frame) != openRect(geometry, frame)
        update(next)
    }

    private fun update(next: ChatDockGeometry) {
        if (next == geometry) return
        geometry = next
        unacknowledged.addLast(next)
        if (unacknowledged.size > MAX_UNACKNOWLEDGED) unacknowledged.removeFirst()
        onChange(next)
    }

    private companion object {
        /** A drag reports every move; a host this far behind has dropped some, not queued them. */
        const val MAX_UNACKNOWLEDGED = 64
    }
}

/** The dock state for [geometry]; [onChange] hears every move, resize, collapse and reset. */
@Composable
internal fun rememberChatDockState(geometry: ChatDockGeometry, onChange: (ChatDockGeometry) -> Unit): ChatDockState {
    val state = remember { ChatDockState(geometry) }
    SideEffect { state.onChange = onChange }
    // In composition, not in an effect: an effect runs after the frame, which would draw the
    // default placement once before the saved one.
    state.sync(geometry)
    return state
}

/** What the panel shows; the panel itself owns only its frame and gestures. */
@Immutable
internal class DockedPanelContent(
    /** What the agent is doing: the panel glows while it works (see ChatPanelAmbientGlow). */
    val ambient: ChatAmbient,
    val conversation: @Composable (Modifier) -> Unit,
    /**
     * The composer bar, open (`false`) or minimised (`true`). It is the same bar in both, at the
     * same place, so it is composed once and stays put while the rest of the dock folds.
     */
    val composer: @Composable (collapsed: Boolean) -> Unit,
    /** What the dock shows above the bar minimised: the agent's mascot, see [CollapsedDock]. */
    val collapsed: CollapsedDockContent,
)

private val DockLimits = ChatDockLimits(
    minWidthDp = ChatSurfaceDimens.dockMinWidth.value,
    minHeightDp = ChatSurfaceDimens.dockMinHeight.value,
    maxWidthDp = ChatSurfaceDimens.dockMaxWidth.value,
    maxHeightDp = ChatSurfaceDimens.dockMaxHeight.value,
    marginDp = ChatSurfaceDimens.dockMargin.value,
    defaultWidthDp = ChatSurfaceDimens.dockDefaultWidth.value,
    defaultHeightFraction = ChatSurfaceDimens.dockDefaultHeightFraction,
    // The mascot badge rises above the panel's top edge; it must not leave the canvas.
    topInsetDp = ChatSurfaceDimens.dockBadgeOverhang.value,
)

/**
 * Lays the panel out over the whole [modifier] area (the canvas). On Android the keyboard
 * shrinks that area, so the panel rises above it and the composer inside adds no padding of
 * its own.
 *
 * [morph] grows the panel into the full page and back (see ChatSurfaceMorph): its rect, corners,
 * shadow and fill follow the morph's progress and its content fades out under the page. At rest
 * it is the panel as it always was; it is the same composition either way, so nothing inside is
 * disposed when a morph starts or ends.
 *
 * Minimising and restoring fold the panel down onto its composer bar and back: the bar is the
 * same bar at the same place, so it stays composed and still while the panel's top edge slides
 * down to the mascot's height (or back up), its surface and conversation fading out as the
 * mascot and its bubble fade in (or the other way round).
 */
@Composable
internal fun DockedChatPanel(
    state: ChatDockState,
    content: DockedPanelContent,
    modifier: Modifier = Modifier,
    morph: SurfaceMorph = SurfaceMorph.Docked,
) {
    BoxWithConstraints(modifier.fillMaxSize().imePadding()) {
        val frame = ChatDockFrame(maxWidth.value, maxHeight.value, DockLimits, state.collapsedHeightDp)
        SideEffect { state.frame = frame }
        val geometry = state.geometry
        val collapsed = geometry.collapsed
        // The open panel's rect (gliding on a reset); minimised, the dock ends at its bottom edge.
        val open = animatedDockRect(openRect(geometry, frame), state)
        val openness = rememberDockOpenness(collapsed)
        val showPanel by remember(openness) { derivedStateOf { openness.value > 0f } }
        val showMinimised by remember(openness) { derivedStateOf { openness.value < 1f } }
        val folding = showPanel && showMinimised
        val density = LocalDensity.current
        // The page covers the keyboard's area too: the morph grows into it.
        val fullHeightDp = maxHeight.value + with(density) { WindowInsets.ime.getBottom(density).toDp().value }
        val fullWidthDp = maxWidth.value
        val fraction = morph.fraction
        // Minimised at rest the dock is as tall as what it shows; otherwise its rect sets its size.
        val wrapHeight = !showPanel && !morph.morphing
        val opennessValue: () -> Float = remember(openness) { { openness.value } }
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val placed = dockPlacement(
                        measurable = measurable,
                        constraints = constraints,
                        target = DockPlacementTarget(geometry, frame, open, wrapHeight, state.snapping),
                        openness = opennessValue(),
                        morphTo = Pair(fullWidthDp, fullHeightDp),
                        fraction = fraction(),
                    )
                    // Takes the whole area and places the panel inside it, like an offset: what
                    // follows (the tag, the actions, the content) sees only the panel's rect.
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placed.placeable.place(placed.left, placed.top)
                    }
                }
                .then(
                    if (wrapHeight) {
                        Modifier.onSizeChanged { size -> state.collapsedHeightDp = with(density) { size.height.toDp().value } }
                    } else {
                        Modifier
                    },
                )
                .dockSemantics(state, dockSemanticsLabels())
                .testTag(DOCK_PANEL_TAG),
            contentAlignment = Alignment.BottomCenter,
        ) {
            PanelChrome(
                openness = opennessValue,
                fraction = fraction,
                takesTouches = showPanel,
                glow = { glowModifier ->
                    // Minimised, the halo around the mascot glows instead.
                    if (showPanel) ChatPanelAmbientGlow(
                        ambient = content.ambient,
                        placement = AmbientGlowPlacement.AboveComposer { state.barDp.takeUnless { it.isNaN() }?.dp ?: Dp.Unspecified },
                        modifier = glowModifier,
                    )
                },
                modifier = Modifier.matchParentSize(),
            )
            Column(
                Modifier
                    .then(if (wrapHeight) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                    .graphicsLayer { alpha = morphDockedAlpha(fraction()) }
                    .then(if (morph.morphing) Modifier.clearAndSetSemantics { } else Modifier),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(if (wrapHeight) Modifier else Modifier.weight(1f))
                        // Mid-fold neither top is really there; the bar below stays reachable.
                        .then(if (folding) Modifier.clearAndSetSemantics { } else Modifier),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (showPanel) {
                        PanelTop(
                            state = state,
                            content = content,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                alpha = opennessValue()
                                clip = true
                            },
                        )
                    }
                    if (showMinimised) {
                        // No panel and no handles: the mascot and the bar float on the canvas.
                        CollapsedDock(
                            state = state,
                            content = content.collapsed,
                            // The companion stands here only once the dock is minimised.
                            seated = collapsed,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { state.minimisedTopDp = with(density) { it.height.toDp().value } }
                                .graphicsLayer { alpha = 1f - opennessValue() },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().onSizeChanged { state.barDp = with(density) { it.height.toDp().value } }) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        content.composer(collapsed)
                    }
                }
            }
            if (showPanel && !showMinimised && !morph.morphing) Box(Modifier.matchParentSize()) { ResizeHandles(state) }
            // Over the panel, its handles and its surface's glow: the agent's avatar on the top edge.
            if (showPanel) {
                PanelBadge(
                    state = state,
                    agentId = content.collapsed.agentId,
                    seated = !collapsed,
                    alpha = { opennessValue() * morphDockedAlpha(fraction()) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/** What the dock is placed by: its geometry and the open panel's (possibly gliding) rect. */
private class DockPlacementTarget(
    val geometry: ChatDockGeometry,
    val frame: ChatDockFrame,
    val open: ChatDockRect,
    val wrapHeight: Boolean,
    val snapping: Boolean,
)

private class DockPlaced(val placeable: Placeable, val left: Int, val top: Int)

/**
 * Measures and places the dock: between its minimised and open rects by [openness], then from
 * there towards the whole [morphTo] area by the morph's [fraction]. Minimised at rest it is as
 * tall as its content, placed by that measured height so the bar stays put on the frame the
 * dock folds.
 */
private fun MeasureScope.dockPlacement(
    measurable: Measurable,
    constraints: Constraints,
    target: DockPlacementTarget,
    openness: Float,
    morphTo: Pair<Float, Float>,
    fraction: Float,
): DockPlaced {
    val minimised = ChatDockGeometryMath.rect(target.geometry.copy(collapsed = true), target.frame)
    val docked = when {
        openness >= 1f -> target.open
        openness <= 0f -> minimised
        else -> lerpRect(minimised, target.open, openness)
    }
    val rect = morphRect(docked, morphTo.first, morphTo.second, fraction)
    val width = rect.width.dp.roundToPx().coerceAtLeast(0)
    if (!target.wrapHeight) {
        val placeable = measurable.measure(Constraints.fixed(width, rect.height.dp.roundToPx().coerceAtLeast(0)))
        return DockPlaced(placeable, rect.left.dp.roundToPx(), rect.top.dp.roundToPx())
    }
    val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight))
    val measured = if (target.snapping) {
        rect
    } else {
        // Minimised by geometry or still on its way to opening: either way placed as the minimised dock.
        ChatDockGeometryMath.rect(target.geometry.copy(collapsed = true), target.frame.copy(collapsedHeightDp = placeable.height.toDp().value))
    }
    return DockPlaced(placeable, measured.left.dp.roundToPx(), measured.top.dp.roundToPx())
}

/** 1 open, 0 minimised, easing between them as the dock folds. Reduced motion jumps. */
@Composable
private fun rememberDockOpenness(collapsed: Boolean): Animatable<Float, AnimationVector1D> {
    val reducedMotion = LocalReducedMotion.current
    val openness = remember { Animatable(if (collapsed) 0f else 1f) }
    LaunchedEffect(collapsed, reducedMotion) {
        val target = if (collapsed) 0f else 1f
        if (reducedMotion) {
            openness.snapTo(target)
        } else {
            openness.animateTo(target, tween(ChatMotionTokens.DockCollapse.MILLIS, easing = ChatMotionTokens.SurfaceMorph.easing))
        }
    }
    return openness
}

/**
 * The panel's surface: the chat's own neutral fill, lifted by its shadow, with a hairline. No
 * tonal elevation: Material tints elevated surfaces with the primary colour, which turned the
 * panel teal. It fades with [openness] (minimised there is no surface: the mascot and its bar
 * float on the canvas). As the morph runs ([fraction]) the corners square off, the shadow and
 * hairline go and the fill becomes the page background. While [takesTouches], a touch on it
 * never reaches the canvas underneath; minimised the canvas around the mascot stays live.
 */
@Composable
private fun PanelChrome(
    openness: () -> Float,
    fraction: () -> Float,
    takesTouches: Boolean,
    glow: @Composable (Modifier) -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val page = scheme.background
    val panel = scheme.surfaceContainer
    val outline = scheme.outlineVariant
    val corners = Modifier.graphicsLayer {
        shape = RoundedCornerShape(LettaDimens.Radius.lg.toPx() * (1f - fraction()))
        clip = true
    }
    Box(modifier) {
        // Minimised there is no panel: only the page filling in as a morph runs.
        Box(
            Modifier
                .matchParentSize()
                .then(corners)
                .drawBehind { drawRect(page, alpha = fraction() * (1f - openness())) },
        )
        // The panel itself, fading as a whole with its shadow (a shadow under a clear surface
        // would otherwise linger as a dark slab while the panel folds away).
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val rest = 1f - fraction()
                    val open = openness()
                    alpha = open
                    shadowElevation = ChatSurfaceDimens.dockedReplyElevation.toPx() * rest * open
                    shape = RoundedCornerShape(LettaDimens.Radius.lg.toPx() * rest)
                    clip = true
                }
                .drawBehind { drawRect(lerp(panel, page, fraction())) }
                .drawWithContent {
                    drawContent()
                    drawFadingHairline(outline, 1f - fraction())
                }
                .then(if (takesTouches) Modifier.pointerInput(Unit) {} else Modifier),
        ) {
            // The thinking glow, on the fill and under the conversation, clipped to the panel's
            // corners; it gives way to the page's own background as the morph runs.
            glow(Modifier.matchParentSize().graphicsLayer { alpha = 1f - fraction() })
        }
    }
}

/** The open panel above its composer bar: the header and the conversation. */
@Composable
private fun PanelTop(state: ChatDockState, content: DockedPanelContent, modifier: Modifier) {
    Column(modifier.testTag(DOCK_SURFACE_TAG)) {
        // No progress bar: the panel's ambient glow says the agent is working.
        PanelHeader(state, badged = dockBadgeShown(content.collapsed.agentId))
        content.conversation(Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * Where the panel sits in a [widthDp] x [heightDp] area: the same rect [DockedChatPanel] lays
 * it out at, so a morph that starts or ends here lines up with the panel at rest.
 */
internal fun ChatDockState.rectIn(widthDp: Float, heightDp: Float): ChatDockRect =
    ChatDockGeometryMath.rect(geometry, ChatDockFrame(widthDp, heightDp, DockLimits, collapsedHeightDp))

/** Where the panel stands open for [geometry]: itself, or where a minimised dock would open. */
private fun openRect(geometry: ChatDockGeometry, frame: ChatDockFrame): ChatDockRect =
    ChatDockGeometryMath.rect(geometry.copy(collapsed = false), frame)

/** Drag to move, double-click / double-tap to reset; the panel's own controls sit on it. */
private fun Modifier.moveHandle(state: ChatDockState): Modifier = this
    .pointerHoverIcon(movePointerIcon())
    .dockDrag(state)
    .pointerInput(state) { detectTapGestures(onDoubleTap = { state.reset() }) }

/** Dragging this moves the dock (the panel, or the collapsed mascot and its bubble). */
internal fun Modifier.dockDrag(state: ChatDockState): Modifier = pointerInput(state) {
    detectDragGestures { change, amount ->
        change.consume()
        state.drag(amount.x.toDp().value, amount.y.toDp().value)
    }
}

/**
 * The panel's top edge: a slim drag strip (drag to move, double-click or double-tap to reset)
 * with the minimise control at its end. Its centre is the agent's avatar ([PanelBadge], drawn
 * over the panel's top edge, so the strip is as tall as the badge's lower half); for an agent
 * without a mascot it is a grip pill. No title: the avatar already says who this is, and the
 * composer's own expand control opens the full chat. Minimised there is no header at all;
 * [CollapsedDock] has its own restore control.
 */
@Composable
private fun PanelHeader(state: ChatDockState, badged: Boolean) {
    val moveLabel = stringResource(Res.string.chat_surface_dock_move)
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (badged) ChatSurfaceDimens.dockBadgeHeader else LettaDimens.Control.iconButton)
            .moveHandle(state)
            .semantics { contentDescription = moveLabel }
            .testTag(DOCK_HEADER_TAG),
    ) {
        if (!badged) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(ChatSurfaceDimens.dockGripWidth, ChatSurfaceDimens.dockGripHeight)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(LettaDimens.Radius.sm)),
            )
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = LettaDimens.Space.sm)) {
            HeaderButton(Lucide.ChevronDown, stringResource(Res.string.chat_surface_dock_collapse), DOCK_COLLAPSE_TAG) {
                state.toggleCollapsed()
            }
        }
    }
}

/** The open panel wears the agent's avatar badge: on a chat page, for an agent with a mascot. */
@Composable
private fun dockBadgeShown(agentId: String?): Boolean =
    LocalCompanionSeatAnchors.current != null && mascotAvailable(agentId)

/**
 * The agent's avatar at the top centre of the open panel: a neutral disc with a hairline ring
 * and a soft shadow, half above the panel's top edge (the dock's top inset keeps that half on
 * the canvas). The page's one companion seat stands in it ([CompanionSeatAnchor], scaled to
 * [ChatMascotDimens.dockBadgeSeat]); a tap on the character opens the agent pane, its pencil
 * edits it, and the disc's rim drags the panel like the strip around it. [seated] is false once
 * the dock minimises: the seat glides on to the mascot over the bar while the disc fades.
 */
@Composable
private fun PanelBadge(state: ChatDockState, agentId: String?, seated: Boolean, alpha: () -> Float, modifier: Modifier) {
    if (!dockBadgeShown(agentId)) return
    val anchors = LocalCompanionSeatAnchors.current ?: return
    val scheme = MaterialTheme.colorScheme
    // While the character stands elsewhere (the agent pane, the editor) the disc fades away
    // rather than sitting empty; the anchor inside stays put so the character flies back here.
    val transport = LocalMascotTransport.current
    val present = agentId != null && transport.activeStage(agentId) == MascotStage.COMPOSER_COMPANION
    val presence by animateFloatAsState(if (present) 1f else 0f, label = "dockBadgePresence")
    Box(
        modifier
            .offset(y = -ChatSurfaceDimens.dockBadgeOverhang)
            .size(ChatMascotDimens.dockBadge)
            .graphicsLayer { this.alpha = alpha() * presence }
            // No clip: the seat's spot inside is larger than the disc and must report whole.
            .shadow(ChatSurfaceDimens.dockBadgeElevation, CircleShape, clip = false)
            .background(scheme.surfaceContainerHigh, CircleShape)
            .border(LettaDimens.Stroke.hairline, scheme.outlineVariant, CircleShape)
            .pointerHoverIcon(movePointerIcon())
            .dockDrag(state)
            .testTag(DOCK_BADGE_TAG)
            // Decoration: the character's own seat is what assistive technology reaches.
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (seated) CompanionSeatAnchor(anchors, size = ChatMascotDimens.dockBadgeSeat)
    }
}

@Composable
private fun HeaderButton(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(LettaDimens.Control.iconButton).testTag(tag)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(LettaDimens.Control.icon))
    }
}

/**
 * Edge strips and corner squares for mouse and pen, plus a visible grip for touch. All sit just
 * OUTSIDE the panel, so they never cover its own controls (the composer's expand and send
 * buttons sit at its edges); only the panel's surface takes input inside it.
 */
@Composable
private fun BoxScope.ResizeHandles(state: ChatDockState) {
    val edge = ChatSurfaceDimens.dockResizeEdge
    val corner = ChatSurfaceDimens.dockResizeCorner
    ResizeHandle(state, ChatDockEdge.Left, Modifier.align(Alignment.CenterStart).offset(x = -edge).fillMaxHeight().width(edge))
    ResizeHandle(state, ChatDockEdge.Right, Modifier.align(Alignment.CenterEnd).offset(x = edge).fillMaxHeight().width(edge))
    ResizeHandle(state, ChatDockEdge.Top, Modifier.align(Alignment.TopCenter).offset(y = -edge).fillMaxWidth().height(edge))
    ResizeHandle(state, ChatDockEdge.Bottom, Modifier.align(Alignment.BottomCenter).offset(y = edge).fillMaxWidth().height(edge))
    ResizeHandle(state, ChatDockEdge.TopLeft, Modifier.align(Alignment.TopStart).offset(-corner, -corner).size(corner))
    ResizeHandle(state, ChatDockEdge.TopRight, Modifier.align(Alignment.TopEnd).offset(corner, -corner).size(corner))
    ResizeHandle(state, ChatDockEdge.BottomLeft, Modifier.align(Alignment.BottomStart).offset(-corner, corner).size(corner))
    ResizeHandle(state, ChatDockEdge.BottomRight, Modifier.align(Alignment.BottomEnd).offset(corner, corner).size(corner))
    // Overlaid on the panel's bottom-right corner, its marks tucked into the rounded corner.
    ResizeGrip(state, Modifier.align(Alignment.BottomEnd))
}

@Composable
private fun ResizeHandle(state: ChatDockState, edge: ChatDockEdge, modifier: Modifier) {
    Box(modifier.pointerHoverIcon(resizePointerIcon(edge.direction)).resizeDrag(state, edge))
}

@Composable
private fun ResizeGrip(state: ChatDockState, modifier: Modifier) {
    val label = stringResource(Res.string.chat_surface_dock_resize)
    val color = MaterialTheme.colorScheme.outline
    Canvas(
        modifier
            .size(ChatSurfaceDimens.dockResizeGrip)
            .pointerHoverIcon(resizePointerIcon(ChatDockEdge.BottomRight.direction))
            .resizeDrag(state, ChatDockEdge.BottomRight)
            .semantics { contentDescription = label }
            .testTag(DOCK_RESIZE_GRIP_TAG),
    ) {
        // Three short diagonals in the corner, the familiar resize mark.
        val gap = LettaDimens.Space.hair.toPx() * 1.5f
        val inset = LettaDimens.Space.sm.toPx()
        val stroke = LettaDimens.Stroke.hairline.toPx() * 2f
        for (step in 1..3) {
            val reach = step * gap
            drawLine(
                color = color,
                start = Offset(size.width - inset - reach, size.height - inset),
                end = Offset(size.width - inset, size.height - inset - reach),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun Modifier.resizeDrag(state: ChatDockState, edge: ChatDockEdge): Modifier = pointerInput(state, edge) {
    detectDragGestures { change, amount ->
        change.consume()
        state.resize(edge, amount.x.toDp().value, amount.y.toDp().value)
    }
}

private val ChatDockEdge.direction: ResizeDirection
    get() = when (this) {
        ChatDockEdge.Left, ChatDockEdge.Right -> ResizeDirection.Horizontal
        ChatDockEdge.Top, ChatDockEdge.Bottom -> ResizeDirection.Vertical
        ChatDockEdge.TopLeft, ChatDockEdge.BottomRight -> ResizeDirection.DiagonalDown
        ChatDockEdge.TopRight, ChatDockEdge.BottomLeft -> ResizeDirection.DiagonalUp
    }

private val DockRectConverter = TwoWayConverter<ChatDockRect, AnimationVector4D>(
    convertToVector = { AnimationVector4D(it.left, it.top, it.width, it.height) },
    convertFromVector = { ChatDockRect(it.v1, it.v2, it.v3, it.v4) },
)

/** Follows [target] exactly, except a reset glides there (or jumps, under reduced motion). */
@Composable
private fun animatedDockRect(target: ChatDockRect, state: ChatDockState): ChatDockRect {
    val reducedMotion = LocalReducedMotion.current
    val animated = remember { Animatable(target, DockRectConverter) }
    LaunchedEffect(target) {
        try {
            if (state.snapping && !reducedMotion) {
                animated.animateTo(target, tween(ChatSurfaceDimens.dockSnapMillis))
            } else {
                animated.snapTo(target)
            }
        } finally {
            // Also when a gesture (or a new target) cuts the glide short.
            state.snapping = false
        }
    }
    return if (state.snapping) animated.value else target
}

internal const val DOCK_PANEL_TAG = "chat-dock-panel"
internal const val DOCK_HEADER_TAG = "chat-dock-header"
internal const val DOCK_SURFACE_TAG = "chat-dock-surface"
internal const val DOCK_COLLAPSE_TAG = "chat-dock-collapse"
internal const val DOCK_RESTORE_TAG = "chat-dock-restore"
internal const val DOCK_RESIZE_GRIP_TAG = "chat-dock-resize-grip"
internal const val DOCK_BADGE_TAG = "chat-dock-badge"
