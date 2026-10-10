package com.letta.mobile.ui.chat.surface

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Square
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_bubble_stop
import com.letta.mobile.sharedui.resources.chat_surface_bubble_card
import com.letta.mobile.sharedui.resources.chat_surface_bubble_card_unnamed
import com.letta.mobile.sharedui.resources.chat_surface_bubble_collapse
import com.letta.mobile.sharedui.resources.chat_surface_recents_close
import com.letta.mobile.sharedui.resources.chat_surface_recents_open
import com.letta.mobile.ui.chat.surface.recents.RecentInteractionsList
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatHeadDimens
import com.letta.mobile.ui.theme.ChatRowMotion
import com.letta.mobile.ui.theme.ChatSurfaceDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/*
 * letta-mobile-y5q9z: the canvas bubble expanded. Tapping the head's bubble opens a card anchored
 * to the head, in the manner of Android's conversation bubbles: the agent's name, the recent
 * exchange and the prompt (the same bar, draft and send as the full page; its chevron still opens
 * the full chat). Beside the head a "+" swaps the exchange for the agent's recent interactions. The
 * board stays live around both. The card grows out of the head and folds back into it; it rides
 * above the keyboard as the keyboard rises, its anchor unchanged.
 */

/** What the expanded bubble shows: its state, the exchange, the prompt and the recent interactions. */
@Immutable
internal class TouchBubble(
    val state: CanvasBubbleState,
    /** The recent exchange, laid out within the modifier it is given. */
    val exchange: @Composable (Modifier) -> Unit,
    /** The prompt bar: the page's own composer, so the draft and the send are the page's. */
    val composer: @Composable () -> Unit,
    /** The "+"'s recent interactions and their hop; null: the host has none to offer, and there is no "+". */
    val recents: BubbleRecents?,
    /** Stops the turn from the collapsed bubble; null while nothing runs. */
    val onStop: (() -> Unit)?,
)

/** Where the head is, which the card and the "+" are anchored to. */
@Immutable
internal class BubbleAnchor(val lane: HeadLane, val headOnRight: Boolean, val head: () -> Offset) {
    /** High on the screen the card opens downward from the head; elsewhere upward from it, as the popup does. */
    fun opensBelow(): Boolean = head().y + lane.size / 2f < lane.heightDp / 2f

    /** Where a [card] of this size goes in the [area] (above the keyboard) it is laid out in, in px. */
    fun cardOffset(below: Boolean, card: IntSize, area: IntSize, density: Density): IntOffset = with(density) {
        val at = head()
        val gap = ChatHeadDimens.popupGap.toPx()
        val margin = ChatHeadDimens.edgeMargin.roundToPx()
        val headLeft = at.x.dp.toPx()
        val headTop = at.y.dp.toPx()
        val headSize = lane.size.dp.toPx()
        val x = if (headOnRight) headLeft + headSize - card.width else headLeft
        val y = if (below) headTop + headSize + gap else headTop - gap - card.height
        // The keyboard only ever narrows [area] from below: the card slides up with it, never re-anchors.
        val maxX = (area.width - card.width - margin).coerceAtLeast(0)
        IntOffset(
            x.roundToInt().coerceIn(minOf(margin, maxX), maxX),
            y.roundToInt().coerceIn(0, (area.height - card.height).coerceAtLeast(0)),
        )
    }

    /** The "+": beside the head on the board's side of it, centred on the head's height. */
    fun plusOffset(density: Density): IntOffset = with(density) {
        val at = head()
        val plus = ChatHeadDimens.plus.value
        val gap = ChatHeadDimens.plusGap.value
        val x = if (headOnRight) at.x - gap - plus else at.x + lane.size + gap
        val y = at.y + (lane.size - plus) / 2f
        IntOffset(x.dp.roundToPx(), y.dp.roundToPx())
    }
}

/**
 * The expanded card over the board (above the keyboard) and the "+" beside the head. Both come and
 * go with [TouchBubble.state]'s expanded; the "+" draws over the card should the keyboard lift the
 * card over the head.
 */
@Composable
internal fun BubbleCardLayer(bubble: TouchBubble, agentName: String, anchor: BubbleAnchor) {
    val reduced = LocalReducedMotion.current
    val motion = ChatRowMotion(reduced)
    val below by remember(anchor) { derivedStateOf { anchor.opensBelow() } }
    val origin = TransformOrigin(if (anchor.headOnRight) 1f else 0f, if (below) 0f else 1f)
    val expanded = bubble.state.expanded
    // Laid out above the keyboard; the head's own band is not, so the head never moves with it.
    Box(Modifier.fillMaxSize().imePadding().then(cardLayout(anchor, below))) {
        AnimatedVisibility(visible = expanded, enter = motion.popEnter(origin), exit = motion.popExit(origin)) {
            BubbleCard(bubble, agentName, CardLook(anchor.headOnRight, below, motion, reduced))
        }
    }
    if (bubble.recents != null) BubblePlus(bubble.state, anchor, motion)
}

/** How the card sits against the head, and the motion it grows with. */
@Immutable
private class CardLook(val headOnRight: Boolean, val below: Boolean, val motion: ChatRowMotion, val reduced: Boolean)

/** Measures the card within the width and the share of the height it may take, and places it by the head. */
private fun cardLayout(anchor: BubbleAnchor, below: Boolean): Modifier = Modifier.layout { measurable, constraints ->
    val area = IntSize(constraints.maxWidth, constraints.maxHeight)
    val margin = ChatHeadDimens.edgeMargin.roundToPx()
    val maxWidth = minOf(area.width - 2 * margin, ChatHeadDimens.cardMaxWidth.roundToPx()).coerceAtLeast(0)
    val maxHeight = (area.height * ChatHeadDimens.cardMaxHeightFraction).roundToInt().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints(minWidth = maxWidth, maxWidth = maxWidth, maxHeight = maxHeight))
    layout(area.width, area.height) {
        placeable.place(anchor.cardOffset(below, IntSize(placeable.width, placeable.height), area, this@layout))
    }
}

/** The card: the agent's name over the exchange (or the recent interactions) over the prompt. */
@Composable
private fun BubbleCard(bubble: TouchBubble, agentName: String, look: CardLook) {
    val scheme = MaterialTheme.colorScheme
    val title = if (agentName.isBlank()) {
        stringResource(Res.string.chat_surface_bubble_card_unnamed)
    } else {
        stringResource(Res.string.chat_surface_bubble_card, agentName)
    }
    Surface(
        modifier = Modifier.semantics { paneTitle = title }.testTag(BUBBLE_CARD_TAG),
        shape = headAnchoredShape(look.headOnRight, look.below),
        color = scheme.surfaceContainer,
        contentColor = scheme.onSurface,
        shadowElevation = ChatSurfaceDimens.dockedReplyElevation,
        border = BorderStroke(LettaDimens.Stroke.hairline, scheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)),
    ) {
        // A reply streaming in, or the recents arriving, grows the card instead of jumping it.
        Column(Modifier.animateContentSize(look.motion.contentSize())) {
            BubbleCardHeader(agentName, onCollapse = bubble.state::collapse)
            BubbleCardBody(bubble, look, Modifier.weight(1f, fill = false))
            bubble.composer()
        }
    }
}

/** The agent's name, and the control that folds the card back into the head. */
@Composable
private fun BubbleCardHeader(agentName: String, onCollapse: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(ChatHeadDimens.cardHeader).padding(start = LettaDimens.Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            agentName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onCollapse, modifier = Modifier.size(ChatHeadDimens.cardHeader).testTag(BUBBLE_COLLAPSE_TAG)) {
            // Open, the chevron points down: folding the card back down into the head.
            DisclosureChevron(
                expanded = true,
                contentDescription = stringResource(Res.string.chat_surface_bubble_collapse),
                opensUpward = true,
            )
        }
    }
}

/** The exchange, or the recent interactions while the "+" has them open: a crossfade, never a jump. */
@Composable
private fun BubbleCardBody(bubble: TouchBubble, look: CardLook, modifier: Modifier) {
    val recents = bubble.recents
    AnimatedContent(
        targetState = recents?.state?.recentsOpen == true,
        modifier = modifier,
        transitionSpec = { bodySwap(look.reduced) },
        label = "bubbleCardBody",
    ) { showRecents ->
        val shown = if (showRecents) recents else null
        if (shown != null) {
            RecentInteractionsList(
                rows = shown.recents.conversations,
                actions = shown.hop,
                maxHeight = ChatHeadDimens.cardRecentsMaxHeight,
                modifier = Modifier.padding(bottom = LettaDimens.Space.sm),
            )
        } else {
            bubble.exchange(Modifier.fillMaxWidth().heightIn(max = ChatHeadDimens.cardExchangeMaxHeight))
        }
    }
}

/** A crossfade; the card's own content-size animation carries the change of height. */
private fun bodySwap(reduced: Boolean): ContentTransform {
    if (reduced) return ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
    val fade = tween<Float>(LettaMotionTokens.CHIP_MILLIS)
    return ContentTransform(fadeIn(fade), fadeOut(fade), sizeTransform = null)
}

/**
 * The "+" beside the head while the card is open: opens the recent interactions (and turns into a
 * close), or closes them. A full touch target, announced as what it does.
 */
@Composable
private fun BubblePlus(state: CanvasBubbleState, anchor: BubbleAnchor, motion: ChatRowMotion) {
    val open = state.recentsOpen
    val reduced = LocalReducedMotion.current
    val turn by animateFloatAsState(
        if (open) PLUS_OPEN_ROTATION else 0f,
        if (reduced) snap() else tween(LettaMotionTokens.CHIP_MILLIS),
        label = "bubblePlusTurn",
    )
    val label = stringResource(if (open) Res.string.chat_surface_recents_close else Res.string.chat_surface_recents_open)
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.offset { anchor.plusOffset(this) }.size(ChatHeadDimens.plus)) {
        AnimatedVisibility(
            visible = state.expanded,
            enter = motion.popEnter(TransformOrigin.Center),
            exit = motion.popExit(TransformOrigin.Center),
        ) {
            Surface(
                shape = CircleShape,
                color = if (open) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                contentColor = if (open) scheme.onSecondaryContainer else scheme.onSurface,
                shadowElevation = ChatHeadDimens.popupElevation,
                border = BorderStroke(LettaDimens.Stroke.hairline, scheme.outlineVariant),
            ) {
                IconButton(
                    onClick = state::toggleRecents,
                    modifier = Modifier.size(ChatHeadDimens.plus).testTag(BUBBLE_PLUS_TAG),
                ) {
                    Icon(LettaIcons.Add, contentDescription = label, modifier = Modifier.graphicsLayer { rotationZ = turn })
                }
            }
        }
    }
}

/** Stops the turn from the collapsed bubble, beside its working line: a full touch target. */
@Composable
internal fun BubbleStopButton(onStop: () -> Unit) {
    IconButton(onClick = onStop, modifier = Modifier.size(ChatHeadDimens.plus).testTag(BUBBLE_STOP_TAG)) {
        Icon(
            Lucide.Square,
            contentDescription = stringResource(Res.string.chat_surface_bubble_stop),
            modifier = Modifier.size(LettaDimens.Control.icon),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

/** A quarter-turn-and-a-half: the "+" reads as an "x" while the recents are open. */
private const val PLUS_OPEN_ROTATION = 45f

internal const val BUBBLE_CARD_TAG = "chat-bubble-card"
internal const val BUBBLE_COLLAPSE_TAG = "chat-bubble-collapse"
internal const val BUBBLE_PLUS_TAG = "chat-bubble-plus"
internal const val BUBBLE_STOP_TAG = "chat-bubble-stop"
