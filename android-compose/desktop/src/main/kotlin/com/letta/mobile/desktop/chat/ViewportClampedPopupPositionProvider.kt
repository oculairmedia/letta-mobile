package com.letta.mobile.desktop.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

internal class ViewportClampedPopupPositionProvider(
    private val yOffsetPx: Int = -6
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val rawX = anchorBounds.left
        val rawY = anchorBounds.top - popupContentSize.height + yOffsetPx

        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)

        return IntOffset(
            x = rawX.coerceIn(0, maxX),
            y = rawY.coerceIn(0, maxY)
        )
    }
}
