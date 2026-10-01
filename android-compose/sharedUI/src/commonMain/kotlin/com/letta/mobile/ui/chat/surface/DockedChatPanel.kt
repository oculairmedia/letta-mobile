package com.letta.mobile.ui.chat.surface

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
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
import com.letta.mobile.ui.components.ResizeDirection
import com.letta.mobile.ui.components.movePointerIcon
import com.letta.mobile.ui.components.resizePointerIcon
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

    /** The composer bar's measured height in dp, for placing the collapsed panel. */
    var collapsedHeightDp: Float by mutableFloatStateOf(ChatSurfaceDimens.dockCollapsedHeightEstimate.value)

    /** A reset is animating towards its target. */
    internal var snapping: Boolean by mutableStateOf(false)

    internal var onChange: (ChatDockGeometry) -> Unit = {}

    /** The last laid-out frame; gestures resolve against it. */
    internal var frame: ChatDockFrame? = null

    /** A value from the host (initial load, or a change it made itself). */
    internal fun sync(fromHost: ChatDockGeometry) {
        geometry = ChatDockGeometryMath.sanitize(fromHost)
    }

    fun drag(dxDp: Float, dyDp: Float) {
        val frame = frame ?: return
        update(ChatDockGeometryMath.drag(geometry, dxDp, dyDp, frame))
    }

    fun resize(edge: ChatDockEdge, dxDp: Float, dyDp: Float) {
        val frame = frame ?: return
        update(ChatDockGeometryMath.resize(geometry, edge, dxDp, dyDp, frame))
    }

    fun toggleCollapsed() {
        update(if (geometry.collapsed) ChatDockGeometryMath.expand(geometry) else ChatDockGeometryMath.collapse(geometry))
    }

    /** Opens the panel again at its last expanded size; nothing when it is already open. */
    fun restore() {
        if (geometry.collapsed) update(ChatDockGeometryMath.expand(geometry))
    }

    fun reset() {
        val next = ChatDockGeometryMath.reset()
        if (next == geometry) return
        snapping = true
        update(next)
    }

    private fun update(next: ChatDockGeometry) {
        if (next == geometry) return
        geometry = next
        onChange(next)
    }
}

/** The dock state for [geometry]; [onChange] hears every move, resize, collapse and reset. */
@Composable
internal fun rememberChatDockState(geometry: ChatDockGeometry, onChange: (ChatDockGeometry) -> Unit): ChatDockState {
    val state = remember { ChatDockState(geometry) }
    SideEffect { state.onChange = onChange }
    LaunchedEffect(geometry) { if (ChatDockGeometryMath.sanitize(geometry) != state.geometry) state.sync(geometry) }
    return state
}

/** What the panel shows; the panel itself owns only its frame and gestures. */
@Immutable
internal class DockedPanelContent(
    val streaming: Boolean,
    val conversation: @Composable (Modifier) -> Unit,
    val composer: @Composable () -> Unit,
    /** What the dock shows minimised: the agent's mascot over its bar, see [CollapsedDock]. */
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
)

/**
 * Lays the panel out over the whole [modifier] area (the canvas). On Android the keyboard
 * shrinks that area, so the panel rises above it and the composer inside adds no padding of
 * its own.
 */
@Composable
internal fun DockedChatPanel(state: ChatDockState, content: DockedPanelContent, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().imePadding()) {
        val frame = ChatDockFrame(maxWidth.value, maxHeight.value, DockLimits, state.collapsedHeightDp)
        SideEffect { state.frame = frame }
        val geometry = state.geometry
        val shown = animatedDockRect(ChatDockGeometryMath.rect(geometry, frame), state)
        val density = LocalDensity.current
        val sized = if (geometry.collapsed) {
            Modifier.onSizeChanged { size -> state.collapsedHeightDp = with(density) { size.height.toDp().value } }
        } else {
            Modifier.height(shown.height.dp)
        }
        Box(
            Modifier
                .offset { IntOffset(shown.left.dp.roundToPx(), shown.top.dp.roundToPx()) }
                .width(shown.width.dp)
                .then(sized)
                .dockSemantics(state, dockSemanticsLabels())
                .testTag(DOCK_PANEL_TAG),
        ) {
            val fold = rememberDockFold(geometry.collapsed)
            val arriving = Modifier.graphicsLayer {
                val progress = fold.value
                alpha = progress
                val scale = ChatMotionTokens.DockCollapse.START_SCALE +
                    (1f - ChatMotionTokens.DockCollapse.START_SCALE) * progress
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            if (geometry.collapsed) {
                // No panel and no handles: the mascot and the bar float on the canvas.
                CollapsedDock(state, content.collapsed, Modifier.fillMaxWidth().then(arriving))
            } else {
                PanelSurface(state, content, Modifier.fillMaxSize().then(arriving))
                Box(Modifier.matchParentSize()) { ResizeHandles(state) }
            }
        }
    }
}

/**
 * 1 at rest; after the dock folds down to its mascot (or opens back into the panel) it runs from
 * 0 to 1 for the arriving layout's fade and scale. Reduced motion snaps.
 */
@Composable
private fun rememberDockFold(collapsed: Boolean): State<Float> {
    val reducedMotion = LocalReducedMotion.current
    var settled by remember { mutableStateOf(collapsed) }
    val progress = remember(collapsed) { Animatable(if (collapsed == settled || reducedMotion) 1f else 0f) }
    LaunchedEffect(collapsed) {
        progress.animateTo(1f, tween(ChatMotionTokens.DockCollapse.MILLIS))
        settled = collapsed
    }
    return progress.asState()
}

@Composable
private fun PanelSurface(state: ChatDockState, content: DockedPanelContent, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(LettaDimens.Radius.lg),
        // The chat's own neutral surface. No tonal elevation: Material tints elevated surfaces
        // with the primary colour, which turned the panel teal; the shadow alone lifts it.
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = ChatSurfaceDimens.dockedReplyElevation,
        border = BorderStroke(
            LettaDimens.Stroke.hairline,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline),
        ),
    ) {
        DockedPanelBody(state, content, Modifier.testTag(DOCK_SURFACE_TAG))
    }
}

/** The open panel's inside: its header, the conversation and the composer bar. */
@Composable
internal fun DockedPanelBody(state: ChatDockState, content: DockedPanelContent, modifier: Modifier = Modifier) {
    Column(modifier) {
        PanelHeader(state)
        if (content.streaming) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.md))
        }
        content.conversation(Modifier.weight(1f).fillMaxWidth())
        content.composer()
    }
}

/**
 * Where the panel sits in a [widthDp] x [heightDp] area: the same rect [DockedChatPanel] lays
 * it out at, so a morph that starts or ends here lines up with the panel at rest.
 */
internal fun ChatDockState.rectIn(widthDp: Float, heightDp: Float): ChatDockRect =
    ChatDockGeometryMath.rect(geometry, ChatDockFrame(widthDp, heightDp, DockLimits, collapsedHeightDp))

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
 * The panel's top edge: a slim drag strip with a centred grip pill (drag to move, double-click
 * or double-tap to reset) and the minimise control at its end. No title: the agent's mascot
 * beside the composer already says who this is, and the composer's own expand control opens the
 * full chat. Minimised there is no header at all; [CollapsedDock] has its own restore control.
 */
@Composable
private fun PanelHeader(state: ChatDockState) {
    val moveLabel = stringResource(Res.string.chat_surface_dock_move)
    Box(
        Modifier
            .fillMaxWidth()
            .height(LettaDimens.Control.iconButton)
            .moveHandle(state)
            .semantics { contentDescription = moveLabel }
            .testTag(DOCK_HEADER_TAG),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(ChatSurfaceDimens.dockGripWidth, ChatSurfaceDimens.dockGripHeight)
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(LettaDimens.Radius.sm)),
        )
        Box(Modifier.align(Alignment.CenterEnd).padding(end = LettaDimens.Space.sm)) {
            HeaderButton(Lucide.ChevronDown, stringResource(Res.string.chat_surface_dock_collapse), DOCK_COLLAPSE_TAG) {
                state.toggleCollapsed()
            }
        }
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
        if (state.snapping && !reducedMotion) {
            animated.animateTo(target, tween(ChatSurfaceDimens.dockSnapMillis))
        } else {
            animated.snapTo(target)
        }
        state.snapping = false
    }
    return if (state.snapping) animated.value else target
}

internal const val DOCK_PANEL_TAG = "chat-dock-panel"
internal const val DOCK_HEADER_TAG = "chat-dock-header"
internal const val DOCK_SURFACE_TAG = "chat-dock-surface"
internal const val DOCK_COLLAPSE_TAG = "chat-dock-collapse"
internal const val DOCK_RESTORE_TAG = "chat-dock-restore"
internal const val DOCK_RESIZE_GRIP_TAG = "chat-dock-resize-grip"
