package com.letta.mobile.ui.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchScalePreviewControllerTest {
    @Test
    fun `pinch applies transient scale without changing base until finish`() {
        val controller = PinchScalePreviewController(minScale = 0.7f, maxScale = 1.6f, step = 0.02f)

        controller.begin(activeScale = 1f)
        controller.applyZoom(1.24f)

        assertTrue(controller.isPinching)
        assertEquals(1.24f, controller.transientScale, 0.001f)
        assertEquals(1.24f, controller.finishPreview(), 0.001f)
        assertFalse(controller.isPinching)
        assertEquals(1.24f, controller.transientScale, 0.001f)
        assertEquals(1f, controller.visualScaleFor(activeScale = 1.24f), 0.001f)
        assertEquals(1.24f, controller.visualScaleFor(activeScale = 1f), 0.001f)
        controller.syncCommittedScale(1.24f)
        assertEquals(1f, controller.transientScale, 0.001f)
    }

    @Test
    fun `pinch clamps visual scale`() {
        val controller = PinchScalePreviewController(minScale = 0.7f, maxScale = 1.6f, step = 0.02f)

        controller.begin(activeScale = 1.5f)
        controller.applyZoom(2f)

        assertEquals(1.6f / 1.5f, controller.transientScale, 0.001f)
        assertEquals(1.6f, controller.finishPreview(), 0.001f)
    }
}
