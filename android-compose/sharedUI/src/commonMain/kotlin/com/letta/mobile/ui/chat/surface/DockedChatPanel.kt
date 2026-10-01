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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.GripHorizontal
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_dock_collapse
import com.letta.mobile.sharedui.resources.chat_surface_dock_move
import com.letta.mobile.sharedui.resources.chat_surface_dock_resize
import com.letta.mobile.sharedui.resources.chat_surface_dock_restore
import com.letta.mobile.sharedui.resources.chat_surface_docked_reply_expand
import com.letta.mobile.ui.chat.session.ChatDockEdge
import com.letta.mobile.ui.chat.session.ChatDockFrame
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatDockGeometryMath
import com.letta.mobile.ui.chat.session.ChatDockLimits
import com.letta.mobile.ui.chat.session.ChatDockRect
import com.letta.mobile.ui.components.ResizeDirection
import com.letta.mobile.ui.components.movePointerIcon
import com.letta.mobile.ui.components.resizePointerIcon
import com.letta.mobile.ui.theme.ChatSurfaceDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.stringResource

/*
 * letta-mobile-bglj6.1: the docked chat as a floating panel over the canvas. The person drags it
 * by its header, resizes it from its edges and corners (or the bottom-right grip on touch),
 * minimises it to the composer bar and double-clicks / double-taps the header to put it back
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
    val agentName: String,
    val streaming: Boolean,
    val onOpenFullScreen: () -> Unit,
    val conversation: @Composable (Modifier) -> Unit,
    val composer: @Composable () -> Unit,
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
                .testTag(DOCK_PANEL_TAG),
        ) {
            PanelSurface(state, content, Modifier.fillMaxWidth().then(if (geometry.collapsed) Modifier else Modifier.fillMaxHeight()))
            Box(Modifier.matchParentSize()) { ResizeHandles(state, collapsed = geometry.collapsed) }
        }
    }
}

@Composable
private fun PanelSurface(state: ChatDockState, content: DockedPanelContent, modifier: Modifier) {
    val collapsed = state.geometry.collapsed
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
        Column {
            if (collapsed) {
                CollapsedHeader(state)
            } else {
                PanelHeader(state, content)
                if (content.streaming) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.md))
                }
                content.conversation(Modifier.weight(1f).fillMaxWidth())
            }
            content.composer()
        }
    }
}

/** Drag to move, double-click / double-tap to reset; the panel's own controls sit on it. */
private fun Modifier.moveHandle(state: ChatDockState): Modifier = this
    .pointerHoverIcon(movePointerIcon())
    .pointerInput(state) {
        detectDragGestures { change, amount ->
            change.consume()
            state.drag(amount.x.toDp().value, amount.y.toDp().value)
        }
    }
    .pointerInput(state) { detectTapGestures(onDoubleTap = { state.reset() }) }

@Composable
private fun PanelHeader(state: ChatDockState, content: DockedPanelContent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .moveHandle(state)
            .testTag(DOCK_HEADER_TAG)
            .padding(start = LettaDimens.Space.md, end = LettaDimens.Space.xs, top = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        val moveLabel = stringResource(Res.string.chat_surface_dock_move)
        Icon(
            Lucide.GripHorizontal,
            contentDescription = moveLabel,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LettaDimens.Control.icon),
        )
        Text(
            text = content.agentName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HeaderButton(Lucide.ChevronDown, stringResource(Res.string.chat_surface_dock_collapse), DOCK_COLLAPSE_TAG) {
            state.toggleCollapsed()
        }
        HeaderButton(Lucide.Maximize2, stringResource(Res.string.chat_surface_docked_reply_expand), DOCK_FULL_SCREEN_TAG) {
            content.onOpenFullScreen()
        }
    }
}

/** Minimised: a slim grip strip over the composer bar, still a move handle. */
@Composable
private fun CollapsedHeader(state: ChatDockState) {
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
            HeaderButton(Lucide.ChevronUp, stringResource(Res.string.chat_surface_dock_restore), DOCK_RESTORE_TAG) {
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

/** Edge strips and corner squares for mouse and pen, plus a visible bottom-right grip for touch. */
@Composable
private fun BoxScope.ResizeHandles(state: ChatDockState, collapsed: Boolean) {
    val edge = ChatSurfaceDimens.dockResizeEdge
    val corner = ChatSurfaceDimens.dockResizeCorner
    ResizeHandle(state, ChatDockEdge.Left, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(edge))
    ResizeHandle(state, ChatDockEdge.Right, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(edge))
    if (collapsed) return
    ResizeHandle(state, ChatDockEdge.Top, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(edge).padding(horizontal = corner))
    ResizeHandle(state, ChatDockEdge.Bottom, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(edge).padding(horizontal = corner))
    ResizeHandle(state, ChatDockEdge.TopLeft, Modifier.align(Alignment.TopStart).size(corner))
    ResizeHandle(state, ChatDockEdge.TopRight, Modifier.align(Alignment.TopEnd).size(corner))
    ResizeHandle(state, ChatDockEdge.BottomLeft, Modifier.align(Alignment.BottomStart).size(corner))
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
            .pointerHoverIcon(resizePointerIcon(ResizeDirection.DiagonalDown))
            .resizeDrag(state, ChatDockEdge.BottomRight)
            .semantics { contentDescription = label }
            .testTag(DOCK_RESIZE_GRIP_TAG),
    ) {
        // Three short diagonals tucked into the panel's rounded corner.
        val gap = LettaDimens.Space.hair.toPx() * 1.5f
        val inset = LettaDimens.Space.xs.toPx()
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
internal const val DOCK_COLLAPSE_TAG = "chat-dock-collapse"
internal const val DOCK_RESTORE_TAG = "chat-dock-restore"
internal const val DOCK_FULL_SCREEN_TAG = "chat-dock-full-screen"
internal const val DOCK_RESIZE_GRIP_TAG = "chat-dock-resize-grip"
