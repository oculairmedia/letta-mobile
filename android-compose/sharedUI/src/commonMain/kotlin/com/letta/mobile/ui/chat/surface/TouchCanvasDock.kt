package com.letta.mobile.ui.chat.surface

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_collapsed_reply
import com.letta.mobile.sharedui.resources.chat_surface_collapsed_reply_unnamed
import com.letta.mobile.sharedui.resources.chat_surface_collapsed_working
import com.letta.mobile.sharedui.resources.chat_surface_docked_reply_dismiss
import com.letta.mobile.sharedui.resources.chat_surface_docked_reply_expand
import com.letta.mobile.sharedui.resources.chat_surface_head
import com.letta.mobile.sharedui.resources.chat_surface_head_agent
import com.letta.mobile.ui.chat.AgentSphere
import com.letta.mobile.ui.chat.surface.composer.CompanionSeatAnchor
import com.letta.mobile.ui.chat.surface.composer.LocalCompanionSeatAnchors
import com.letta.mobile.ui.mascot.mascotAvailable
import com.letta.mobile.ui.theme.ChatHeadDimens
import com.letta.mobile.ui.theme.ChatSurfaceDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.TouchComposerDimens
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/*
 * letta-mobile-bglj6.1.9: the Touch canvas mode. No panel: a flush chat bar pinned to the bottom of
 * the screen (the full page's bar, same draft, same send) and, over the canvas, a chat head in the
 * manner of Google Messages' bubbles: a disc with the agent's mascot, dragged anywhere and snapped
 * back to the nearer screen edge. No glow surrounds it: while the agent works the mascot's own
 * animation is the cue, as on the desktop's minimised dock. Its reply pops out of it in a speech
 * popup that opens towards the middle of the screen. Tapping the popup
 * opens the chat, its x hides it until the next prompt; tapping the head shows or hides the popup
 * (or, with nothing to show, opens the chat), a long press opens the agent's pane.
 */

/** The bar's measured height, which the canvas keeps clear of and the morph grows from. */
@Stable
internal class TouchBarMetrics {
    var heightPx: Int by mutableIntStateOf(0)
}

/** What the chat head shows and does. */
@Immutable
internal class TouchHeadContent(
    val dock: ChatDockState,
    val agentId: String?,
    val agentName: String,
    val openChat: () -> Unit,
    val openAgent: (() -> Unit)?,
    val turn: @Composable () -> CollapsedTurn,
)

/**
 * The bar at the bottom and the head over the canvas above it. As the morph to the full page runs,
 * the bar's surface grows up into the page while the bar and the head fade out under it.
 */
@Composable
internal fun TouchDockLayer(
    bar: TouchBarMetrics,
    morph: SurfaceMorph,
    head: TouchHeadContent?,
    /** Host chrome over the canvas's top edge (ChatSurfacePlatform.topChromeInset): the head stays below it. */
    topChromeInset: Dp = 0.dp,
    composer: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val fraction = morph.fraction
    val fade = if (morph.morphing) Modifier.clearAndSetSemantics { } else Modifier
    Box(Modifier.fillMaxSize().testTag(TOUCH_DOCK_TAG)) {
        if (morph.morphing) {
            val from = scheme.surfaceContainerLow
            val to = scheme.background
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val f = fraction()
                    if (f <= 0f) return@drawBehind
                    val top = (size.height - bar.heightPx) * (1f - f)
                    // The bar's rounded top, squaring off as it reaches the page's top edge.
                    val corner = CornerRadius(TouchComposerDimens.restingCorner.toPx() * (1f - f))
                    val surface = RoundRect(Rect(0f, top, size.width, size.height), topLeft = corner, topRight = corner)
                    drawPath(Path().apply { addRoundRect(surface) }, lerp(from, to, f))
                },
            )
        }
        if (head != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = topChromeInset, bottom = with(density) { bar.heightPx.toDp() })
                    // The head and its popup leave first: they float over the canvas the page covers.
                    .graphicsLayer { alpha = morphDockedAlpha(fraction() * HEAD_FADE_SPEED) }
                    .then(fade),
            ) { TouchChatHead(head) }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bar.heightPx = it.height }
                .graphicsLayer { alpha = morphDockedAlpha(fraction()) }
                .then(fade),
        ) { composer() }
    }
}

/**
 * The head and its popup, laid out in the band above the bar. The head's resting place is the
 * dock's geometry ([ChatDockState.placeHead]): a side and a height in the band between the canvas's
 * actions pill and its tool bar, so it survives rotation and process death like the panel's place.
 */
@Composable
private fun TouchChatHead(content: TouchHeadContent) {
    val turn = content.turn()
    // Per turn, as the minimised dock's bubble: a new prompt brings a new popup.
    var dismissedTurn by rememberSaveable { mutableStateOf<String?>(null) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(turn.turnKey) { hidden = false }
    val hasReply = turn.hasReply && turn.dismissKey != dismissedTurn
    val popupShown = hasReply && !hidden
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val lane = HeadLane(maxWidth.value, maxHeight.value)
        val geometry = content.dock.geometry
        val right = geometry.anchorX >= HALF
        val rest = lane.rest(right, geometry.anchorY)
        val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        val position: () -> Offset = { rest + drag.value }
        if (popupShown) {
            HeadPopup(
                turn = turn,
                agentName = content.agentName,
                placement = PopupPlacement(lane, right, position),
                onOpen = content.openChat,
                onDismiss = { dismissedTurn = turn.dismissKey },
            )
        }
        ChatHead(
            content = content,
            position = position,
            drag = drag,
            lane = lane,
            onTap = { if (hasReply) hidden = !hidden else content.openChat() },
        )
        if (turn.busy && !turn.hasReply) ThinkingAnnouncement(content.agentName)
    }
}

/** The band the head moves in, in dp: the free area less its margins and clearances. */
@Immutable
private class HeadLane(val widthDp: Float, val heightDp: Float) {
    val size: Float = ChatHeadDimens.head.value
    private val margin = ChatHeadDimens.edgeMargin.value
    val top: Float = ChatHeadDimens.topClearance.value.coerceAtMost((heightDp - size) / 2f)
    val bottom: Float = (heightDp - ChatHeadDimens.bottomClearance.value - size).coerceAtLeast(top)
    val leftX: Float = margin
    val rightX: Float = (widthDp - margin - size).coerceAtLeast(margin)

    fun rest(right: Boolean, lane: Float): Offset = Offset(if (right) rightX else leftX, top + (bottom - top) * lane.coerceIn(0f, 1f))

    /** The nearer side and the lane fraction for a head whose top-left is at [at]. */
    fun snap(at: Offset): Pair<Boolean, Float> {
        val right = at.x + size / 2f >= widthDp / 2f
        val span = bottom - top
        val lane = if (span <= 0f) 1f else ((at.y - top) / span).coerceIn(0f, 1f)
        return right to lane
    }
}

/**
 * The disc with the agent: its mascot (the page's one companion seat stands over it, scaled to
 * the disc) or, without one, the agent's sphere. Drag it anywhere; let go and it snaps to the
 * nearer edge. Nothing glows around it: the mascot's animation says the agent is working.
 */
@Composable
private fun ChatHead(
    content: TouchHeadContent,
    position: () -> Offset,
    drag: Animatable<Offset, *>,
    lane: HeadLane,
    onTap: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The gesture detectors outlive a composition (they are keyed on the dock alone): whatever
    // they read must be the latest, or a second drag snaps from where the head rested at first.
    val reducedMotion by rememberUpdatedState(LocalReducedMotion.current)
    val currentLane by rememberUpdatedState(lane)
    val currentTap by rememberUpdatedState(onTap)
    val currentPosition by rememberUpdatedState(position)
    val openAgent = content.openAgent
    val currentOpenAgent by rememberUpdatedState(openAgent)
    val anchors = LocalCompanionSeatAnchors.current
    val mascot = anchors != null && mascotAvailable(content.agentId)
    val label = stringResource(Res.string.chat_surface_head, content.agentName)
    val agentLabel = stringResource(Res.string.chat_surface_head_agent)
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .offset { position().let { IntOffset(it.x.dp.roundToPx(), it.y.dp.roundToPx()) } }
            .size(ChatHeadDimens.head)
            .testTag(TOUCH_HEAD_TAG)
            .semantics {
                contentDescription = label
                onClick { currentTap(); true }
                if (openAgent != null) onLongClick(agentLabel) { openAgent(); true }
            }
            .pointerInput(content.dock) {
                detectTapGestures(onTap = { currentTap() }, onLongPress = { currentOpenAgent?.invoke() })
            }
            .pointerInput(content.dock) {
                detectDragGestures(
                    onDragEnd = {
                        val at = currentPosition()
                        val (right, laneFraction) = currentLane.snap(at)
                        val target = currentLane.rest(right, laneFraction)
                        // The geometry moves the resting place at once; the head glides there.
                        scope.launch { drag.snapTo(at - target) }
                        content.dock.placeHead(if (right) 1f else 0f, laneFraction)
                        scope.launch {
                            if (reducedMotion) drag.snapTo(Offset.Zero) else drag.animateTo(Offset.Zero, tween(ChatHeadDimens.snapMillis))
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    scope.launch { drag.snapTo(drag.value + Offset(amount.x.toDp().value, amount.y.toDp().value)) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .shadow(ChatHeadDimens.elevation, CircleShape, clip = false)
                .background(scheme.surfaceContainerHigh, CircleShape)
                .border(LettaDimens.Stroke.hairline, scheme.outlineVariant, CircleShape),
        )
        if (mascot && anchors != null) {
            CompanionSeatAnchor(anchors, size = ChatHeadDimens.seat)
        } else {
            AgentSphere(size = ChatHeadDimens.fallbackSphere)
        }
    }
}

/** Where the popup goes: beside the head, towards the middle, aligned with its foot or its top. */
@Immutable
private class PopupPlacement(val lane: HeadLane, val headOnRight: Boolean, val head: () -> Offset) {
    /** In the lower half the popup grows upward from the head's foot; in the upper, down from its top. */
    fun tailAtTop(): Boolean = head().y + lane.size / 2f < lane.heightDp / 2f
}

/**
 * The reply, spoken from the head: markdown that follows the stream and scrolls once it outgrows
 * the popup, the "working" line while a tool runs, the "needs your input" chip. Tapping it opens the
 * chat; the x hides it until the next prompt.
 */
@Composable
private fun HeadPopup(
    turn: CollapsedTurn,
    agentName: String,
    placement: PopupPlacement,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val openLabel = stringResource(Res.string.chat_surface_docked_reply_expand)
    val working = stringResource(Res.string.chat_surface_collapsed_working)
    val reply = if (agentName.isBlank()) {
        stringResource(Res.string.chat_surface_collapsed_reply_unnamed, turn.text)
    } else {
        stringResource(Res.string.chat_surface_collapsed_reply, agentName, turn.text)
    }
    val announcement = listOfNotNull(reply.takeIf { turn.text.isNotBlank() }, working.takeIf { turn.working }).joinToString(" ")
    val lane = placement.lane
    // The head's place moves every frame of a drag; the popup recomposes only when it changes half.
    val tailAtTop by remember(placement) { derivedStateOf { placement.tailAtTop() } }
    val shape = remember(placement.headOnRight, tailAtTop) {
        SpeechBubbleShape(
            radius = LettaDimens.Radius.lg,
            tailWidth = ChatSurfaceDimens.collapsedBubbleTailWidth,
            tailHeight = ChatSurfaceDimens.collapsedBubbleTailHeight,
            tailAtEnd = placement.headOnRight,
            tailAtTop = tailAtTop,
        )
    }
    val tail = ChatSurfaceDimens.collapsedBubbleTailWidth
    // Beside the head, a compact bubble: what is left between the head and the far margin, at most
    // a share of the screen and a fixed cap.
    val besideHead = lane.widthDp - lane.size - 2 * ChatHeadDimens.edgeMargin.value - ChatHeadDimens.popupGap.value
    val maxWidth = minOf(besideHead, lane.widthDp * ChatHeadDimens.popupMaxWidthFraction, ChatHeadDimens.popupMaxWidth.value)
    Surface(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(maxWidth = maxWidth.dp.roundToPx(), maxHeight = constraints.maxHeight),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val head = placement.head()
                    val gap = ChatHeadDimens.popupGap.toPx()
                    val headLeft = head.x.dp.toPx()
                    val headTop = head.y.dp.toPx()
                    val headSize = lane.size.dp.toPx()
                    val x = if (placement.headOnRight) headLeft - gap - placeable.width else headLeft + headSize + gap
                    val y = if (tailAtTop) headTop else headTop + headSize - placeable.height
                    placeable.place(
                        x.roundToInt(),
                        y.roundToInt().coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0)),
                    )
                }
            }
            .testTag(TOUCH_POPUP_TAG),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = ChatSurfaceDimens.dockedReplyElevation,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)),
    ) {
        Box {
            Column(
                Modifier
                    .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
                    .semantics {
                        contentDescription = announcement
                        if (!turn.streaming) liveRegion = LiveRegionMode.Polite
                    }
                    .padding(
                        start = ChatHeadDimens.popupPaddingHorizontal + tail * (if (placement.headOnRight) 0f else 1f),
                        end = ChatHeadDimens.popupPaddingHorizontal + LettaDimens.Control.iconButtonSm + tail * (if (placement.headOnRight) 1f else 0f),
                        top = ChatHeadDimens.popupPaddingVertical,
                        bottom = ChatHeadDimens.popupPaddingVertical,
                    ),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            ) {
                if (turn.text.isNotBlank()) {
                    BubbleText(
                        turn = turn,
                        maxHeight = ChatHeadDimens.popupMaxHeight,
                        // A size down from the timeline's body: a bubble's aside, in sp so it scales with the font.
                        textStyle = MaterialTheme.typography.bodySmall,
                        fadeLength = ChatHeadDimens.popupFadeLength,
                    )
                }
                if (turn.working) WorkingLine(working)
                if (turn.needsInput) NeedsInputChip(onOpen)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = tail * (if (placement.headOnRight) 1f else 0f))
                    .padding(LettaDimens.Space.xs)
                    .size(LettaDimens.Control.iconButtonSm)
                    .testTag(TOUCH_POPUP_DISMISS_TAG),
            ) {
                Icon(
                    Lucide.X,
                    contentDescription = stringResource(Res.string.chat_surface_docked_reply_dismiss),
                    modifier = Modifier.size(LettaDimens.Control.iconSm),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val HALF = 0.5f

/** The head's layer fades this many times faster than the bar as the page grows over it. */
private const val HEAD_FADE_SPEED = 2f

internal const val TOUCH_DOCK_TAG = "chat-touch-dock"
internal const val TOUCH_CANVAS_TAG = "chat-touch-canvas"
internal const val TOUCH_HEAD_TAG = "chat-touch-head"
internal const val TOUCH_POPUP_TAG = "chat-touch-popup"
internal const val TOUCH_POPUP_DISMISS_TAG = "chat-touch-popup-dismiss"
