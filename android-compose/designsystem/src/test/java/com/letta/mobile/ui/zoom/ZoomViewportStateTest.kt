package com.letta.mobile.ui.zoom

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ZoomViewportStateTest {
    @Test
    fun `zoom anchors around centroid`() {
        val state = ZoomViewportState()

        state.onTransform(
            zoomChange = 2f,
            panChange = Offset.Zero,
            centroid = Offset(75f, 50f),
            viewportSize = IntSize(100, 100),
        )

        assertEquals(2f, state.scale, 0.001f)
        assertEquals(-25f, state.pan.x, 0.001f)
        assertEquals(0f, state.pan.y, 0.001f)
    }

    @Test
    fun `reset clears scale and pan`() {
        val state = ZoomViewportState()
        state.onTransform(2f, Offset(8f, 4f), Offset(50f, 50f), IntSize(100, 100))

        state.reset()

        assertEquals(1f, state.scale, 0.001f)
        assertEquals(Offset.Zero, state.pan)
    }

    @Test
    fun `constructor rejects invalid scale bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            ZoomViewportState(minScale = 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ZoomViewportState(minScale = 2f, maxScale = 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ZoomViewportState(initialScale = Float.NaN)
        }
    }
}
