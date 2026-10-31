package com.letta.mobile.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.PopupPositionProvider
import com.letta.mobile.ui.theme.LettaDimens

// The arithmetic of foundation's desktop TooltipPlacement.CursorPoint and .ComponentRect (both desktop-only),
// so a tooltip lands on the same pixel on desktop and in the browser.

private val WINDOW_MARGIN = 4.dp

/** The popup placement for [position]: beside [cursor] (local to the control), or under the control. */
@Composable
internal fun rememberTooltipPositionProvider(position: PointerTooltipPosition, cursor: Offset): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(position, cursor, density) {
        with(density) {
            when (position) {
                PointerTooltipPosition.Cursor -> cursorTooltipPosition(
                    cursor = cursor.round(),
                    offset = IntOffset(LettaDimens.Space.md.roundToPx(), LettaDimens.Space.md.roundToPx()),
                    windowMargin = WINDOW_MARGIN.roundToPx(),
                )
                PointerTooltipPosition.BelowAnchor -> anchorTooltipPosition(
                    anchor = Alignment.BottomCenter,
                    alignment = Alignment.TopCenter,
                    offset = IntOffset(0, LettaDimens.Space.sm.roundToPx()),
                )
            }
        }
    }
}

/** Area twice the popup's size around [point], so an [Alignment] picks which side of the point the popup takes. */
private fun areaAround(point: IntOffset, popup: IntSize): IntRect =
    IntRect(IntOffset(point.x - popup.width, point.y - popup.height), IntSize(popup.width * 2, popup.height * 2))

/** At the cursor, below and after it, flipped to the other side when it would leave the window. */
internal fun cursorTooltipPosition(
    cursor: IntOffset,
    offset: IntOffset,
    windowMargin: Int,
    alignment: Alignment = Alignment.BottomEnd,
): PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val area = areaAround(anchorBounds.topLeft + cursor, popupContentSize)
        val aligned = alignment.align(popupContentSize, area.size, layoutDirection)
        var x = area.left + aligned.x + offset.x
        var y = area.top + aligned.y + offset.y
        if (x + popupContentSize.width > windowSize.width - windowMargin) x -= popupContentSize.width
        if (y + popupContentSize.height > windowSize.height - windowMargin) y -= popupContentSize.height
        return IntOffset(x.coerceAtLeast(windowMargin), y.coerceAtLeast(windowMargin))
    }
}

/** At the [anchor] point of the control, the popup taking the [alignment] side of it. */
internal fun anchorTooltipPosition(anchor: Alignment, alignment: Alignment, offset: IntOffset): PopupPositionProvider =
    object : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowSize: IntSize,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize,
        ): IntOffset {
            val point = anchorBounds.topLeft + anchor.align(IntSize.Zero, anchorBounds.size, layoutDirection)
            val area = areaAround(point, popupContentSize)
            return area.topLeft + alignment.align(popupContentSize, area.size, layoutDirection) + offset
        }
    }
