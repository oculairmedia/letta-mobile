package com.letta.mobile.desktop.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewportClampedPopupPositionProviderTest {
    @Test
    fun testClamping() {
        val provider = ViewportClampedPopupPositionProvider(yOffsetPx = -6)

        val windowSize = IntSize(1000, 1000)
        val popupSize = IntSize(200, 100)

        // Normal case: plenty of space
        val normalAnchor = IntRect(left = 500, top = 500, right = 600, bottom = 600)
        assertEquals(
            IntOffset(500, 500 - 100 - 6),
            provider.calculatePosition(normalAnchor, windowSize, LayoutDirection.Ltr, popupSize)
        )

        // Offscreen top
        val topAnchor = IntRect(left = 500, top = 50, right = 600, bottom = 150)
        assertEquals(
            IntOffset(500, 0),
            provider.calculatePosition(topAnchor, windowSize, LayoutDirection.Ltr, popupSize)
        )

        // Offscreen left
        val leftAnchor = IntRect(left = -50, top = 500, right = 50, bottom = 600)
        assertEquals(
            IntOffset(0, 500 - 100 - 6),
            provider.calculatePosition(leftAnchor, windowSize, LayoutDirection.Ltr, popupSize)
        )

        // Offscreen right
        val rightAnchor = IntRect(left = 900, top = 500, right = 1000, bottom = 600)
        assertEquals(
            IntOffset(800, 500 - 100 - 6),
            provider.calculatePosition(rightAnchor, windowSize, LayoutDirection.Ltr, popupSize)
        )

        // Offscreen bottom
        val bottomAnchor = IntRect(left = 500, top = 1200, right = 600, bottom = 1300)
        assertEquals(
            IntOffset(500, 900),
            provider.calculatePosition(bottomAnchor, windowSize, LayoutDirection.Ltr, popupSize)
        )
    }
}
