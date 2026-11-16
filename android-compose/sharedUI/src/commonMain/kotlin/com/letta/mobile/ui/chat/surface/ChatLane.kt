package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import com.letta.mobile.ui.chat.surface.timeline.ScrollToLatestButton
import com.letta.mobile.ui.chat.surface.timeline.TouchScrollToLatestButton
import com.letta.mobile.ui.theme.ChatTimelineDimens
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.letta.mobile.data.chat.layout.ChatViews
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.ui.theme.LettaDimens

// The frame projects slots rather than importing shell layout into the chat renderer (a package cycle).
internal val LocalChatLane = compositionLocalOf<List<Pair<RuntimeViewId, String?>>> { ChatViews.Default.map { it to null } }
internal val LocalChatModuleContent = compositionLocalOf<@Composable (Int, Modifier) -> Unit> { { _, _ -> } }

/**
 * The scroll-to-latest button's action while it should show (null while hidden), handed up by the
 * timeline so [ChatLane] draws the button over the timeline and the composer alike.
 */
internal val LocalScrollToLatestAction = compositionLocalOf<MutableState<(() -> Unit)?>?> { null }

/**
 * Root bounds the lane places that button against: [LANE_TIMELINE] (the list's area, less what it
 * reserves at the bottom), [LANE_CARD] (the composer's prompt) and [LANE_ABOVE_CARD] (what stands
 * above the prompt and could sit under the button: the chips' own extent, or the whole band).
 */
internal val LocalChatLaneBounds = compositionLocalOf<SnapshotStateMap<String, Rect>?> { null }

internal const val LANE_TIMELINE = "timeline"
internal const val LANE_CARD = "card"
internal const val LANE_ABOVE_CARD = "aboveCard"

/** One content lane, reused by the full page and the dock's conversation area. */
@Composable
internal fun ChatLane(
    modifier: Modifier,
    docked: Boolean = false,
    transcript: @Composable (Modifier) -> Unit,
    status: @Composable () -> Unit,
    composer: @Composable (Modifier, Boolean) -> Unit,
) {
    val lane = LocalChatLane.current
    val hasExtras = lane.any { it.first !in ChatViews.Default }
    val wide = remember(hasExtras) { mutableStateOf(false) }
    val measuredModifier = if (hasExtras) {
        val breakpoint = with(LocalDensity.current) { LettaDimens.Pane.wideBreakpoint.toPx() }
        modifier.onSizeChanged { wide.value = it.width >= breakpoint }
    } else {
        modifier
    }
    val attachedStatus = lane.indexOfFirst { it.first == ChatViews.STATUS }.let { index ->
        index >= 0 && lane.getOrNull(index + 1)?.first == ChatViews.COMPOSER
    }
    val content: @Composable (Modifier) -> Unit = { bodyModifier ->
        Column(bodyModifier) {
            lane.forEachIndexed { index, slot ->
                key(slot) {
                    when (slot.first) {
                        ChatViews.TRANSCRIPT -> transcript(Modifier.weight(1f).fillMaxWidth())
                        ChatViews.STATUS -> if (!attachedStatus) status()
                        ChatViews.COMPOSER -> if (!docked) composer(Modifier.fillMaxWidth(), attachedStatus)
                        else -> ExtraChatModule(wide, index, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
    val action = remember { mutableStateOf<(() -> Unit)?>(null) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    CompositionLocalProvider(LocalScrollToLatestAction provides action, LocalChatLaneBounds provides bounds) {
        Box(measuredModifier) {
            // One composition position even when an optional view adds/removes the width observer.
            content(Modifier.fillMaxWidth())
            ScrollToLatestOverlay(action.value, bounds, Modifier.matchParentSize())
        }
    }
}

/**
 * The scroll-to-latest button over the lane: the Touch FAB at the timeline's bottom end, the pointer
 * squircle centred over it. It rests just above the composer's prompt; only when something above the
 * prompt (a chip row, the queue, a notice) would sit under it does it rise to clear that, to just
 * above the timeline's own bottom edge, where it always stood.
 */
@Composable
private fun ScrollToLatestOverlay(action: (() -> Unit)?, bounds: SnapshotStateMap<String, Rect>, modifier: Modifier) {
    var lastAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    if (action != null) lastAction = action
    var origin by remember { mutableStateOf(Rect.Zero) }
    val touch = touchStyle()
    val insetPx = with(LocalDensity.current) { ChatTimelineDimens.scrollToLatestTouchInset.toPx() }
    val onClick: () -> Unit = { lastAction?.invoke() }
    Layout(
        content = { LaneScrollToLatestButton(touch, shown = action != null, onClick) },
        modifier = modifier.onGloballyPositioned { origin = it.boundsInRoot() },
    ) { measurables, constraints ->
        val button = measurables.firstOrNull()?.measure(Constraints())
        val timeline = bounds[LANE_TIMELINE]
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (button != null && timeline != null) {
                val left = scrollToLatestLeft(timeline, button.width, touch, insetPx)
                val bottom = scrollToLatestBottom(timeline, bounds, left..left + button.width, insetPx)
                button.place((left - origin.left).toInt(), (bottom - button.height - origin.top).toInt())
            }
        }
    }
}

/** The lane's button: the round Touch one (which animates its own coming and going), or the pointer pill while shown. */
@Composable
private fun LaneScrollToLatestButton(touch: Boolean, shown: Boolean, onClick: () -> Unit) {
    if (touch) {
        TouchScrollToLatestButton(visible = shown, onClick = onClick)
    } else if (shown) {
        ScrollToLatestButton(onClick = onClick)
    }
}

/** Where the button's left edge sits (root px): inset from the timeline's end on Touch, centred on pointer. */
private fun scrollToLatestLeft(timeline: Rect, width: Int, touch: Boolean, insetPx: Float): Float =
    if (touch) timeline.right - insetPx - width else timeline.center.x - width / 2f

/** Where the button's bottom rests (root px): above the prompt, unless something above it is in the button's way. */
private fun scrollToLatestBottom(
    timeline: Rect,
    bounds: Map<String, Rect>,
    span: ClosedFloatingPointRange<Float>,
    insetPx: Float,
): Float {
    val rest = timeline.bottom - insetPx
    val card = bounds[LANE_CARD]?.takeIf { it.top > timeline.bottom } ?: return rest
    val blocked = bounds[LANE_ABOVE_CARD]?.let { it.left < span.endInclusive && it.right > span.start } == true
    return if (blocked) rest else card.top - insetPx
}

/** Only extra slots observe width changes; input and transcript retain their composition and focus. */
@Composable
private fun ExtraChatModule(wide: State<Boolean>, index: Int, modifier: Modifier) {
    if (wide.value) LocalChatModuleContent.current(index, modifier)
}
