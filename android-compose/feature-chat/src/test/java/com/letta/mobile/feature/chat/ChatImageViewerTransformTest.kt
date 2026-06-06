package com.letta.mobile.feature.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.letta.mobile.feature.chat.screen.DoubleTapImageScale
import com.letta.mobile.feature.chat.screen.ImageTransformState
import com.letta.mobile.feature.chat.screen.SwipeDismissThresholdPx
import com.letta.mobile.feature.chat.screen.applyImageTransformGesture
import com.letta.mobile.feature.chat.screen.clampImageOffset
import com.letta.mobile.feature.chat.screen.doubleTapImageTransform
import com.letta.mobile.feature.chat.screen.shouldDismissImageViewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageViewerTransformTest {
    private val container = Size(width = 400f, height = 300f)

    @Test
    fun `pinch delta updates scale`() {
        val next = applyImageTransformGesture(
            state = ImageTransformState(),
            zoom = 2f,
            pan = Offset.Zero,
            centroid = Offset(200f, 150f),
            containerSize = container,
        )

        assertEquals(2f, next.scale, 0.001f)
    }

    @Test
    fun `centroid and pan affect translation`() {
        val next = applyImageTransformGesture(
            state = ImageTransformState(),
            zoom = 2f,
            pan = Offset(12f, -8f),
            centroid = Offset(250f, 175f),
            containerSize = container,
        )

        assertEquals(62f, next.offset.x, 0.001f)
        assertEquals(17f, next.offset.y, 0.001f)
    }

    @Test
    fun `clamp keeps image inside visible bounds`() {
        val clamped = clampImageOffset(
            offset = Offset(10_000f, -10_000f),
            scale = 2f,
            containerSize = container,
        )

        assertEquals(200f, clamped.x, 0.001f)
        assertEquals(-150f, clamped.y, 0.001f)
    }

    @Test
    fun `double tap toggles target zoom through transform state`() {
        val zoomed = doubleTapImageTransform(
            state = ImageTransformState(),
            tap = Offset(200f, 150f),
            containerSize = container,
        )
        val reset = doubleTapImageTransform(
            state = zoomed,
            tap = Offset(200f, 150f),
            containerSize = container,
        )

        assertEquals(DoubleTapImageScale, zoomed.scale, 0.001f)
        assertEquals(1f, reset.scale, 0.001f)
        assertEquals(Offset.Zero, reset.offset)
    }

    @Test
    fun `non zoomed swipe dismiss still works but zoomed pan does not dismiss`() {
        assertTrue(shouldDismissImageViewer(1f, SwipeDismissThresholdPx + 1f))
        assertFalse(shouldDismissImageViewer(1.1f, SwipeDismissThresholdPx + 1f))
    }
}
