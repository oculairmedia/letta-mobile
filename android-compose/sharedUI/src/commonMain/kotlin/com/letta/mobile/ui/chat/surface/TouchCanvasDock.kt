package com.letta.mobile.ui.chat.surface

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_collapsed_reply
import com.letta.mobile.sharedui.resources.chat_surface_collapsed_reply_unnamed
import com.letta.mobile.sharedui.resources.chat_surface_docked_reply_dismiss
import com.letta.mobile.sharedui.resources.chat_surface_docked_reply_expand
import com.letta.mobile.sharedui.resources.chat_surface_head
import com.letta.mobile.sharedui.resources.chat_surface_head_agent
import com.letta.mobile.ui.chat.AgentSphere
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.surface.composer.CompanionSeatAnchor
import com.letta.mobile.ui.chat.surface.composer.LocalCompanionSeatAnchors
import com.letta.mobile.ui.mascot.mascotAvailable
import com.letta.mobile.ui.theme.ChatHeadDimens
import com.letta.mobile.ui.theme.ChatRowMotion
import com.letta.mobile.ui.theme.ChatSurfaceDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.TouchComposerDimens
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/*
 * letta-mobile-bglj6.1.9: the Touch canvas mode. No panel: a flush chat bar pinned to the bottom of
 * the screen (the full page's bar, same draft, same send) and, over the canvas, a chat head in the
 * manner of Google Messages' bubbles: a disc with the agent's mascot, dragged anywhere and snapped
 * back to the nearer screen edge. No glow surrounds it: while the agent works the mascot's own
 * animation is the cue, as on the desktop's minimised dock. Its reply grows out of the head's corner
 * as a compact card just above it (below it, high on the screen) that peeks at a few lines. Tapping
 * the card opens the chat, its dismiss or a swipe hides it until the next prompt; tapping the head shows or hides the popup
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
    /** The input tray is up (a question or a form waits on the person): the reply's popup steps aside. */
    val inputPending: Boolean = false,
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
    /** What waits on the person, over the canvas just above the bar ([TouchInputTray]); null for none. */
    inputTray: @Composable () -> Unit = {},
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
        // Over the head: an answer waiting on the person outranks the reply's popup.
        DockInputTray(
            tray = inputTray,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topChromeInset, bottom = with(density) { bar.heightPx.toDp() })
                .graphicsLayer { alpha = morphDockedAlpha(fraction()) }
                .then(fade),
        )
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

@Composable
private fun DockInputTray(tray: @Composable () -> Unit, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.BottomCenter) { tray() }
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
    // A question waiting in the input tray outranks the reply: the popup steps aside rather than
    // sit under the tray, and comes back once it is answered.
    val popupShown = hasReply && !hidden && !content.inputPending
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val lane = HeadLane(maxWidth.value, maxHeight.value)
        val geometry = content.dock.geometry
        val right = geometry.anchorX >= HALF
        val rest = lane.rest(right, geometry.anchorY)
        val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        val position: () -> Offset = { rest + drag.value }
        HeadPopup(
            reply = PopupTurn(turn, visible = popupShown, dismiss = { dismissedTurn = turn.dismissKey }),
            content = content,
            placement = PopupPlacement(lane, right, position),
        )
        ChatHead(
            content = content,
            place = HeadPlace(lane, drag, position),
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

/** Where the head is: its lane, its drag off the resting place, and its top-left now. */
@Stable
private class HeadPlace(val lane: HeadLane, val drag: Animatable<Offset, *>, val position: () -> Offset)

/**
 * The disc with the agent: its mascot (the page's one companion seat stands over it, scaled to
 * the disc) or, without one, the agent's sphere. Drag it anywhere; let go and it snaps to the
 * nearer edge. Nothing glows around it: the mascot's animation says the agent is working.
 */
@Composable
private fun ChatHead(content: TouchHeadContent, place: HeadPlace, onTap: () -> Unit) {
    val scope = rememberCoroutineScope()
    val position = place.position
    val drag = place.drag
    // The gesture detectors outlive a composition (they are keyed on the dock alone): whatever
    // they read must be the latest, or a second drag snaps from where the head rested at first.
    val reducedMotion by rememberUpdatedState(LocalReducedMotion.current)
    val currentLane by rememberUpdatedState(place.lane)
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

/**
 * Where the popup goes: just above the head (or, high on the screen, just below it), its outer edge
 * flush with the head's so it speaks from the head's corner, and never lower than the head's lane
 * (whose foot keeps clear of the canvas's tool bar).
 */
@Immutable
private class PopupPlacement(val lane: HeadLane, val headOnRight: Boolean, val head: () -> Offset) {
    /** High on the screen the popup opens downward from the head; elsewhere upward from it. */
    fun opensBelow(): Boolean = head().y + lane.size / 2f < lane.heightDp / 2f

    /** Widest the popup grows, in dp: the screen less its margins, a share of it, and a fixed cap. */
    val maxWidthDp: Float = minOf(
        lane.widthDp - 2 * ChatHeadDimens.edgeMargin.value,
        lane.widthDp * ChatHeadDimens.popupMaxWidthFraction,
        ChatHeadDimens.popupMaxWidth.value,
    ).coerceAtLeast(0f)

    /** Lowest the popup's foot may sit, in dp: the foot of the head's lane, above the tool bar. */
    val floorDp: Float get() = lane.bottom + lane.size

    /** Where a [popup] of this size goes in the [area] it is laid out in, in px. */
    fun offset(below: Boolean, popup: IntSize, area: IntSize, density: Density): IntOffset {
        return with(density) { offsetPx(below, popup, area) }
    }

    private fun Density.offsetPx(below: Boolean, popup: IntSize, area: IntSize): IntOffset {
        val at = head()
        val gap = ChatHeadDimens.popupGap.toPx()
        val headLeft = at.x.dp.toPx()
        val headTop = at.y.dp.toPx()
        val headSize = lane.size.dp.toPx()
        val x = if (headOnRight) headLeft + headSize - popup.width else headLeft
        val y = if (below) headTop + headSize + gap else headTop - gap - popup.height
        val floor = floorDp.dp.roundToPx().coerceAtMost(area.height)
        return IntOffset(
            x.roundToInt().coerceIn(0, (area.width - popup.width).coerceAtLeast(0)),
            y.roundToInt().coerceIn(0, (floor - popup.height).coerceAtLeast(0)),
        )
    }
}

/**
 * The reply, spoken from the head: a compact card that peeks at the newest few lines (following
 * the stream, fading at an edge with more to read), the "working" line while a tool runs and the
 * "needs your input" chip. It grows out of the head's corner and folds back into it; tapping it
 * opens the chat, its dismiss (or a sideways swipe) hides it until the next prompt.
 */
@Composable
private fun HeadPopup(reply: PopupTurn, content: TouchHeadContent, placement: PopupPlacement) {
    // The head's place moves every frame of a drag; the popup recomposes only when it changes half.
    val below by remember(placement) { derivedStateOf { placement.opensBelow() } }
    val style = PopupCardStyle(placement, below, ChatRowMotion(LocalReducedMotion.current))
    val origin = TransformOrigin(if (placement.headOnRight) 1f else 0f, if (below) 0f else 1f)
    Box(popupLayout(style)) {
        AnimatedVisibility(visible = reply.visible, enter = style.motion.popEnter(origin), exit = style.motion.popExit(origin)) {
            PopupCard(reply, content, style)
        }
    }
}

/** The turn the popup tells, whether it shows, and how it is dismissed until the next prompt. */
@Immutable
private class PopupTurn(val turn: CollapsedTurn, val visible: Boolean, val dismiss: () -> Unit)

/**
 * Lays the popup out over the head: as wide as it needs up to [PopupPlacement.maxWidthDp], its
 * outer edge on the head's, its foot a gap above the head's top (or its top a gap below the head's
 * foot), kept on screen and never below [PopupPlacement.floorDp].
 */
private fun popupLayout(style: PopupCardStyle): Modifier {
    val placement = style.placement
    return Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints(maxWidth = placement.maxWidthDp.dp.roundToPx(), maxHeight = constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val area = IntSize(constraints.maxWidth, constraints.maxHeight)
            placeable.place(placement.offset(style.below, IntSize(placeable.width, placeable.height), area, this@layout))
        }
    }
}

/** How the card sits against the head, and the motion it grows with. */
@Immutable
private class PopupCardStyle(val placement: PopupPlacement, val below: Boolean, val motion: ChatRowMotion)

/** The card itself: a tonal surface with a hairline edge, its corner by the head tucked in. */
@Composable
private fun PopupCard(reply: PopupTurn, content: TouchHeadContent, style: PopupCardStyle) {
    val scheme = MaterialTheme.colorScheme
    val shape = remember(style.placement.headOnRight, style.below) { popupShape(style) }
    Surface(
        modifier = Modifier.testTag(TOUCH_POPUP_TAG).then(rememberSwipeToDismiss(style.motion, reply.dismiss)),
        shape = shape,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        shadowElevation = ChatHeadDimens.popupElevation,
        border = BorderStroke(LettaDimens.Stroke.hairline, scheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)),
    ) {
        // A streaming reply grows the card smoothly (up to its peek) instead of jumping a line at a time.
        Box(Modifier.animateContentSize(style.motion.contentSize())) {
            PopupReply(reply.turn, content, style.placement)
            PopupDismiss(reply.dismiss, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** Rounded all round but for the corner nearest the head, which stays tight: the card's anchor. */
private fun popupShape(style: PopupCardStyle): Shape {
    // Clockwise from the top left, as the shape takes them.
    val corners = Array(CORNERS) { CornerSize(LettaDimens.Radius.lg) }
    corners[anchorCorner(style)] = CornerSize(ChatHeadDimens.popupAnchorCorner)
    return AbsoluteRoundedCornerShape(corners[0], corners[1], corners[2], corners[3])
}

/** Which corner (clockwise from the top left) faces the head. */
private fun anchorCorner(style: PopupCardStyle): Int {
    val right = style.placement.headOnRight
    return when {
        style.below -> if (right) TOP_RIGHT else TOP_LEFT
        else -> if (right) BOTTOM_RIGHT else BOTTOM_LEFT
    }
}

/**
 * Swiping the card sideways drags it with the finger, fading as it goes; let go past
 * [ChatHeadDimens.popupSwipeDismissFraction] of its width and it is dismissed, short of that it
 * eases back (snaps, under reduced motion).
 */
@Composable
private fun rememberSwipeToDismiss(motion: ChatRowMotion, onDismiss: () -> Unit): Modifier {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentMotion by rememberUpdatedState(motion)
    val settle: () -> Unit = { scope.launch { offset.animateTo(0f, currentMotion.contentSize()) } }
    return Modifier
        .graphicsLayer {
            translationX = offset.value
            alpha = 1f - (abs(offset.value) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f) * SWIPE_FADE
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (abs(offset.value) > size.width * ChatHeadDimens.popupSwipeDismissFraction) currentDismiss() else settle()
                },
                onDragCancel = settle,
            ) { change, amount ->
                change.consume()
                scope.launch { offset.snapTo(offset.value + amount) }
            }
        }
}

/** What the popup says, as one announcement: the reply (with who says it) and the working line. */
@Composable
private fun popupAnnouncement(turn: CollapsedTurn, agentName: String, working: String?): String {
    val reply = if (agentName.isBlank()) {
        stringResource(Res.string.chat_surface_collapsed_reply_unnamed, turn.text)
    } else {
        stringResource(Res.string.chat_surface_collapsed_reply, agentName, turn.text)
    }
    return listOfNotNull(reply.takeIf { turn.text.isNotBlank() }, working).joinToString(" ")
}

/** The popup's reply, working line and input chip; a tap opens the chat. */
@Composable
private fun PopupReply(turn: CollapsedTurn, content: TouchHeadContent, placement: PopupPlacement) {
    val openLabel = stringResource(Res.string.chat_surface_docked_reply_expand)
    val working = if (turn.working) collapsedWorkingLabel(turn) else null
    val announcement = popupAnnouncement(turn, content.agentName, working)
    val onOpen = content.openChat
    Column(
        Modifier
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
            .semantics {
                contentDescription = announcement
                if (!turn.streaming) liveRegion = LiveRegionMode.Polite
            }
            .padding(
                start = ChatHeadDimens.popupPaddingHorizontal,
                end = ChatHeadDimens.popupDismissTarget,
                top = ChatHeadDimens.popupPaddingVertical,
                bottom = ChatHeadDimens.popupPaddingVertical,
            ),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        // A short reply hugs its words; the working line and the chip want the full width.
        if (turn.text.isNotBlank()) PopupText(turn, placement, hug = working == null && !turn.needsInput)
        if (working != null) WorkingLine(working)
        if (turn.needsInput) NeedsInputChip(onOpen)
    }
}

/** The reply's opening lines, [hug]ging their width or filling the popup's. */
@Composable
private fun PopupText(turn: CollapsedTurn, placement: PopupPlacement, hug: Boolean) {
    // The body's own type, in sp so it scales with the font: a note, not a headline.
    val textStyle = MaterialTheme.typography.bodyMedium
    val width = if (hug) Modifier.width(peekTextWidth(turn.text, textStyle, placement)) else Modifier
    Box(width.testTag(TOUCH_POPUP_TEXT_TAG)) {
        BubbleText(
            turn = turn,
            maxHeight = peekHeight(textStyle),
            textStyle = textStyle,
            fadeLength = ChatHeadDimens.popupFadeLength,
        )
    }
}

/** [ChatHeadDimens.popupPeekLines] lines of [style]: the most of the reply the popup shows. */
@Composable
private fun peekHeight(style: TextStyle): Dp {
    val line = if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize
    return with(LocalDensity.current) { (line * ChatHeadDimens.popupPeekLines).toDp() }
}

/**
 * How wide [text] sets in [style] within the popup's text column, so a one-line reply makes a
 * small card rather than a wide one. Only the opening of a long reply is measured: it fills the
 * column anyway.
 */
@Composable
private fun peekTextWidth(text: String, style: TextStyle, placement: PopupPlacement): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val column = (placement.maxWidthDp.dp - ChatHeadDimens.popupPaddingHorizontal - ChatHeadDimens.popupDismissTarget).coerceAtLeast(0.dp)
    val sample = text.take(PEEK_MEASURE_CHARS)
    val widthPx = remember(sample, style, column, density) {
        val maxPx = with(density) { column.roundToPx() }
        measurer.measure(sample, style, constraints = Constraints(maxWidth = maxPx), density = density).size.width
    }
    // A little slack so the markdown's own line breaking never wraps a line the measure fitted.
    return (with(density) { widthPx.toDp() } + LettaDimens.Space.xs).coerceAtMost(column)
}

/** The popup's dismiss: a small tonal disc with an x, inside a comfortably larger hit target. */
@Composable
private fun PopupDismiss(onDismiss: () -> Unit, modifier: Modifier) {
    IconButton(
        onClick = onDismiss,
        modifier = modifier
            .padding(top = LettaDimens.Space.xs)
            .size(ChatHeadDimens.popupDismissTarget)
            .testTag(TOUCH_POPUP_DISMISS_TAG),
    ) {
        Box(
            Modifier
                .size(LettaDimens.Control.iconButtonSm)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
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

/**
 * letta-mobile-bglj6.1.22: the canvas's input tray. A question the agent waits on (and any
 * generated form) is answered here, at thumb reach above the bar, without opening the full page.
 * It keeps to a share of the band above the bar and scrolls past it, so the board stays in view.
 */
@Composable
internal fun TouchInputTray(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            modifier = Modifier
                .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm)
                .widthIn(max = ChatColumnMaxWidth)
                .fillMaxWidth()
                .heightIn(max = maxHeight * INPUT_TRAY_MAX_HEIGHT_FRACTION)
                .testTag(TOUCH_INPUT_TRAY_TAG),
            shape = RoundedCornerShape(LettaDimens.Radius.lg),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = ChatSurfaceDimens.dockedReplyElevation,
            border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(LettaDimens.Space.lg),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
                content = content,
            )
        }
    }
}

/** The tray takes at most this share of the band between the canvas's top chrome and the bar. */
private const val INPUT_TRAY_MAX_HEIGHT_FRACTION = 0.6f

private const val HALF = 0.5f

/** The popup's four corners, clockwise from the top left. */
private const val CORNERS = 4
private const val TOP_LEFT = 0
private const val TOP_RIGHT = 1
private const val BOTTOM_RIGHT = 2
private const val BOTTOM_LEFT = 3

/** How much a fully swiped popup fades. */
private const val SWIPE_FADE = 0.6f

/** The popup measures at most this much of a reply to size itself; a longer one fills its width. */
private const val PEEK_MEASURE_CHARS = 160

/** The head's layer fades this many times faster than the bar as the page grows over it. */
private const val HEAD_FADE_SPEED = 2f

internal const val TOUCH_DOCK_TAG = "chat-touch-dock"
internal const val TOUCH_CANVAS_TAG = "chat-touch-canvas"
internal const val TOUCH_HEAD_TAG = "chat-touch-head"
internal const val TOUCH_POPUP_TAG = "chat-touch-popup"
internal const val TOUCH_POPUP_DISMISS_TAG = "chat-touch-popup-dismiss"
internal const val TOUCH_POPUP_TEXT_TAG = "chat-touch-popup-text"
internal const val TOUCH_INPUT_TRAY_TAG = "chat-touch-input-tray"
