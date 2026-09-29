package com.letta.mobile.desktop.input

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PenMouseMirrorTest {

    @Test
    fun emulatedMouseIsNotPostedAgain() {
        assertFalse(penSampleMirrorsToMouse(TabletBridge.TOOL_EMULATED, windowMoved = false))
    }

    @Test
    fun aRealPenStillClicksWhileTheWindowIsStill() {
        assertTrue(penSampleMirrorsToMouse(TabletBridge.TOOL_DRAW, windowMoved = false))
        assertTrue(penSampleMirrorsToMouse(TabletBridge.TOOL_ERASER, windowMoved = false))
    }

    @Test
    fun samplesDuringAWindowDragAreNotPostedAsMouseInput() {
        assertFalse(penSampleMirrorsToMouse(TabletBridge.TOOL_DRAW, windowMoved = true))
        assertFalse(penSampleMirrorsToMouse(TabletBridge.TOOL_ERASER, windowMoved = true))
    }
}
