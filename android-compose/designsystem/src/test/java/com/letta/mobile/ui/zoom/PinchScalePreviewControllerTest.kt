package com.letta.mobile.ui.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test
    fun `effectiveScale tracks base times transient and clamps`() {
        val controller = PinchScalePreviewController(minScale = 0.7f, maxScale = 1.6f, step = 0.02f)

        // Before any pinch the effective scale is just the base (1f default).
        assertEquals(1f, controller.effectiveScale, 0.001f)

        // Begin a pinch from 1.0 and zoom out a bit — effective scale should
        // follow base * transient live (this is what drives real reflow).
        controller.begin(activeScale = 1f)
        controller.applyZoom(0.8f)
        assertEquals(0.8f, controller.effectiveScale, 0.001f)

        // Zoom in past max — effectiveScale clamps to maxScale.
        controller.applyZoom(10f)
        assertEquals(1.6f, controller.effectiveScale, 0.001f)

        // Finish snaps and the controller exits pinch mode; effectiveScale
        // continues to reflect the new base * (transient = unchanged) =
        // the pre-snap live value until syncCommittedScale runs and resets
        // transient to 1.
        controller.finishPreview()
        controller.syncCommittedScale(1.6f)
        assertEquals(1.6f, controller.effectiveScale, 0.001f)
    }

    @Test
    fun `effectiveScale starts from a non-unit base on begin`() {
        val controller = PinchScalePreviewController(minScale = 0.7f, maxScale = 1.6f, step = 0.02f)

        controller.begin(activeScale = 1.3f)
        // No zoom applied yet — effective should equal base.
        assertEquals(1.3f, controller.effectiveScale, 0.001f)
        controller.applyZoom(1.1f)
        assertEquals(1.3f * 1.1f, controller.effectiveScale, 0.001f)
    }

    @Test
    fun `constructor rejects invalid scale and snap bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            PinchScalePreviewController(minScale = 0f, maxScale = 1f, step = 0.02f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PinchScalePreviewController(minScale = 2f, maxScale = 1f, step = 0.02f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PinchScalePreviewController(minScale = 1f, maxScale = 2f, step = 0f)
        }
    }
}
