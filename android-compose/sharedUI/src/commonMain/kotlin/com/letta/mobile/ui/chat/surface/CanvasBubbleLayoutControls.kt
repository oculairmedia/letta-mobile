package com.letta.mobile.ui.chat.surface

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SlidersHorizontal
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayout
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayoutPrefs
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_bubble_layout
import com.letta.mobile.sharedui.resources.chat_surface_bubble_layout_always
import com.letta.mobile.sharedui.resources.chat_surface_bubble_layout_auto
import com.letta.mobile.sharedui.resources.chat_surface_bubble_layout_dp
import com.letta.mobile.sharedui.resources.chat_surface_bubble_layout_full
import com.letta.mobile.sharedui.resources.chat_surface_bubble_layout_hold
import com.letta.mobile.sharedui.resources.chat_surface_bubble_layout_mascot
import com.letta.mobile.sharedui.resources.chat_surface_bubble_layout_reset
import com.letta.mobile.sharedui.resources.chat_surface_bubble_layout_resize
import com.letta.mobile.sharedui.resources.chat_surface_bubble_layout_seconds
import com.letta.mobile.sharedui.resources.chat_surface_bubble_layout_width
import com.letta.mobile.ui.canvas.LocalWheelZoomRegions
import com.letta.mobile.ui.components.ResizeDirection
import com.letta.mobile.ui.components.resizePointerIcon
import com.letta.mobile.ui.theme.ChatSurfaceDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/*
 * letta-mobile-zxk2n.29: the person's own layout for the minimised chat. Drag the bar's left or right
 * edge to set its width (it grows or shrinks about its centre); Ctrl/Cmd + wheel over the mascot sizes
 * it; the "Bubble layout" button beside the mascot holds the same as sliders, with how long a reply's
 * bubble stays, and a Reset. Every change shows at once and glides there (or jumps, under reduced
 * motion); the host persists the values. A host that provides no prefs keeps today's sizes and no
 * controls.
 */

/** The host's layout prefs for the minimised chat; null: the phone's sizes, no controls. */
val LocalCanvasBubbleLayoutPrefs = staticCompositionLocalOf<CanvasBubbleLayoutPrefs?> { null }

/** The layout in force: the host's, or the phone's. */
@Composable
internal fun currentBubbleLayout(): CanvasBubbleLayout {
    val prefs = LocalCanvasBubbleLayoutPrefs.current ?: return CanvasBubbleLayout.Touch
    return prefs.layout.collectAsState().value
}

/** A size change glides; a drag in progress (or reduced motion) follows the pointer exactly. */
@Composable
private fun layoutSpec(following: Boolean): AnimationSpec<Float> =
    if (following || LocalReducedMotion.current) snap() else tween(LettaMotionTokens.CONTENT_SIZE_MILLIS)

/** The mascot's size, gliding to what the person set. */
@Composable
internal fun animatedBubbleMascotSize(): Dp {
    val target = currentBubbleLayout().mascotSizeDp
    val size by animateFloatAsState(target, layoutSpec(following = false), label = "bubbleMascotSize")
    return size.dp
}

/**
 * The minimised bar's width in dp for [state]'s frame, gliding to what the person set (exactly
 * under an edge drag); null keeps it as wide as the open panel.
 */
@Composable
internal fun animatedBubbleComposerWidth(state: ChatDockState): Float? {
    val target = currentBubbleLayout().composerWidthDp ?: return null
    val width by animateFloatAsState(target, layoutSpec(following = state.layoutResizing), label = "bubbleComposerWidth")
    return width
}

/** Around the bar, for the bar's own width: an invisible strip just outside each side edge. */
@Composable
internal fun BoxScope.ComposerWidthHandles(state: ChatDockState, barHeight: Dp) {
    val prefs = LocalCanvasBubbleLayoutPrefs.current ?: return
    if (barHeight == Dp.Unspecified) return
    val strip = BubbleLayoutDimens.edgeStrip
    val label = stringResource(Res.string.chat_surface_bubble_layout_resize)
    for (side in listOf(-1f, 1f)) {
        val align = if (side < 0f) Alignment.BottomStart else Alignment.BottomEnd
        Box(
            Modifier
                .align(align)
                .offset(x = strip * side)
                .width(strip)
                .height(barHeight)
                .pointerHoverIcon(resizePointerIcon(ResizeDirection.Horizontal))
                .composerEdgeDrag(state, prefs, side)
                .semantics { contentDescription = label }
                .testTag(if (side < 0f) BUBBLE_WIDTH_HANDLE_LEFT_TAG else BUBBLE_WIDTH_HANDLE_RIGHT_TAG),
        )
    }
}

/**
 * Dragging an edge sets the bar's width about its centre: the far edge mirrors the near one, so the
 * width changes by twice the travel. The drag starts from the width as drawn (already kept to the
 * canvas), so nothing is dead at either end.
 */
private fun Modifier.composerEdgeDrag(state: ChatDockState, prefs: CanvasBubbleLayoutPrefs, side: Float): Modifier =
    pointerInput(state, prefs, side) {
        var start = 0f
        var travelled = 0f
        detectHorizontalDragGestures(
            onDragStart = {
                start = state.collapsedBarWidthDp() ?: prefs.layout.value.composerWidthDp ?: CanvasBubbleLayout.COMPOSER_MIN_DP
                travelled = 0f
                state.layoutResizing = true
            },
            onDragEnd = { state.layoutResizing = false },
            onDragCancel = { state.layoutResizing = false },
        ) { change, amount ->
            change.consume()
            travelled += amount.toDp().value
            prefs.setComposerWidth(start + 2f * side * travelled)
        }
    }

/** Where Ctrl/Cmd + wheel sizes the mascot: told to the host's UI-zoom gesture, which leaves it alone. */
private class WheelBounds {
    var rect: Rect? = null
}

/**
 * Ctrl/Cmd + wheel over the mascot grows (wheel up) or shrinks it a notch at a time. Nothing where
 * the host provides no prefs.
 */
@Composable
internal fun rememberMascotWheelResize(): Modifier {
    val prefs = LocalCanvasBubbleLayoutPrefs.current
    val regions = LocalWheelZoomRegions.current
    val bounds = remember { WheelBounds() }
    DisposableEffect(regions, prefs) {
        val unregister = if (prefs != null) regions?.register { bounds.rect } else null
        onDispose { unregister?.invoke() }
    }
    if (prefs == null) return Modifier
    return Modifier
        .onGloballyPositioned { bounds.rect = it.boundsInRoot() }
        .pointerInput(prefs) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val keys = event.keyboardModifiers
                    if (!keys.isCtrlPressed && !keys.isMetaPressed) continue
                    val delta = event.changes.fold(0f) { acc, change -> acc + change.scrollDelta.y }
                    if (delta == 0f) continue
                    prefs.stepMascot(if (delta < 0f) 1 else -1)
                    event.changes.forEach { it.consume() }
                }
            }
        }
}

/** The "Bubble layout" button beside the mascot and its sliders; nothing where the host provides no prefs. */
@Composable
internal fun BubbleLayoutButton(modifier: Modifier = Modifier) {
    val prefs = LocalCanvasBubbleLayoutPrefs.current ?: return
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box(modifier) {
        Surface(
            shape = CircleShape,
            color = scheme.surfaceContainer,
            contentColor = scheme.onSurfaceVariant,
            shadowElevation = ChatSurfaceDimens.dockedReplyElevation,
            border = BorderStroke(LettaDimens.Stroke.hairline, scheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)),
        ) {
            IconButton(
                onClick = { open = !open },
                modifier = Modifier.size(BubbleLayoutDimens.control).testTag(BUBBLE_LAYOUT_BUTTON_TAG),
            ) {
                Icon(
                    Lucide.SlidersHorizontal,
                    contentDescription = stringResource(Res.string.chat_surface_bubble_layout),
                    modifier = Modifier.size(LettaDimens.Control.icon),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BubbleLayoutMenu(prefs)
        }
    }
}

/** The menu's body: three sliders and a Reset, each a full touch target. */
@Composable
internal fun BubbleLayoutMenu(prefs: CanvasBubbleLayoutPrefs) {
    val layout by prefs.layout.collectAsState()
    Column(
        Modifier
            .width(BubbleLayoutDimens.menuWidth)
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)
            .testTag(BUBBLE_LAYOUT_MENU_TAG),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Text(stringResource(Res.string.chat_surface_bubble_layout), style = MaterialTheme.typography.titleSmall)
        LayoutSlider(
            row = SliderRow(
                label = stringResource(Res.string.chat_surface_bubble_layout_mascot),
                value = layout.mascotSizeDp,
                valueText = dpText(layout.mascotSizeDp),
                range = CanvasBubbleLayout.MASCOT_MIN_DP..CanvasBubbleLayout.MASCOT_MAX_DP,
                tag = BUBBLE_LAYOUT_MASCOT_SLIDER_TAG,
            ),
            onChange = prefs::setMascotSize,
        )
        val width = layout.composerWidthDp ?: CanvasBubbleLayout.COMPOSER_FULL_DP
        LayoutSlider(
            row = SliderRow(
                label = stringResource(Res.string.chat_surface_bubble_layout_width),
                value = width,
                valueText = if (width >= CanvasBubbleLayout.COMPOSER_FULL_DP) stringResource(Res.string.chat_surface_bubble_layout_full) else dpText(width),
                range = CanvasBubbleLayout.COMPOSER_MIN_DP..CanvasBubbleLayout.COMPOSER_FULL_DP,
                tag = BUBBLE_LAYOUT_WIDTH_SLIDER_TAG,
            ),
            onChange = prefs::setComposerWidth,
        )
        LayoutSlider(
            row = SliderRow(
                label = stringResource(Res.string.chat_surface_bubble_layout_hold),
                value = layout.replyHoldSeconds ?: AUTO_HOLD_SLIDER_SECONDS,
                valueText = holdText(layout),
                range = CanvasBubbleLayout.REPLY_HOLD_MIN_S..CanvasBubbleLayout.REPLY_HOLD_ALWAYS_S,
                tag = BUBBLE_LAYOUT_HOLD_SLIDER_TAG,
                // Whole seconds, the last stop past the range meaning "always".
                steps = (CanvasBubbleLayout.REPLY_HOLD_ALWAYS_S - CanvasBubbleLayout.REPLY_HOLD_MIN_S).roundToInt() - 1,
            ),
            onChange = prefs::setReplyHold,
        )
        TextButton(
            onClick = prefs::reset,
            modifier = Modifier.align(Alignment.End).heightIn(min = BubbleLayoutDimens.control).testTag(BUBBLE_LAYOUT_RESET_TAG),
        ) { Text(stringResource(Res.string.chat_surface_bubble_layout_reset)) }
    }
}

@Composable
private fun dpText(dp: Float): String = stringResource(Res.string.chat_surface_bubble_layout_dp, dp.roundToInt())

@Composable
private fun holdText(layout: CanvasBubbleLayout): String {
    val seconds = layout.replyHoldSeconds
    return when {
        seconds == null -> stringResource(Res.string.chat_surface_bubble_layout_auto)
        layout.replyHoldsForever -> stringResource(Res.string.chat_surface_bubble_layout_always)
        else -> stringResource(Res.string.chat_surface_bubble_layout_seconds, seconds.roundToInt())
    }
}

/** One slider row: what it sets, its value now and as read out, its range and its tag. */
@Immutable
private class SliderRow(
    val label: String,
    val value: Float,
    val valueText: String,
    val range: ClosedFloatingPointRange<Float>,
    val tag: String,
    val steps: Int = 0,
)

@Composable
private fun LayoutSlider(row: SliderRow, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.label, style = MaterialTheme.typography.labelMedium)
            Text(row.valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = row.value,
            onValueChange = onChange,
            valueRange = row.range,
            steps = row.steps,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BubbleLayoutDimens.control)
                .semantics {
                    contentDescription = row.label
                    stateDescription = row.valueText
                }
                .testTag(row.tag),
        )
    }
}

/** The minimised chat's layout controls. */
internal object BubbleLayoutDimens {
    /** The layout button, the slider rows and Reset: comfortable touch targets. */
    val control: Dp = 44.dp

    /** The invisible strip outside each side of the bar that resizes it. */
    val edgeStrip: Dp = 8.dp

    val menuWidth: Dp = 300.dp
}

/** Where the hold slider sits while the hold is automatic. */
private const val AUTO_HOLD_SLIDER_SECONDS = 6f

internal const val BUBBLE_LAYOUT_BUTTON_TAG = "chat-bubble-layout-button"
internal const val BUBBLE_LAYOUT_MENU_TAG = "chat-bubble-layout-menu"
internal const val BUBBLE_LAYOUT_MASCOT_SLIDER_TAG = "chat-bubble-layout-mascot"
internal const val BUBBLE_LAYOUT_WIDTH_SLIDER_TAG = "chat-bubble-layout-width"
internal const val BUBBLE_LAYOUT_HOLD_SLIDER_TAG = "chat-bubble-layout-hold"
internal const val BUBBLE_LAYOUT_RESET_TAG = "chat-bubble-layout-reset"
internal const val BUBBLE_WIDTH_HANDLE_LEFT_TAG = "chat-bubble-width-left"
internal const val BUBBLE_WIDTH_HANDLE_RIGHT_TAG = "chat-bubble-width-right"
