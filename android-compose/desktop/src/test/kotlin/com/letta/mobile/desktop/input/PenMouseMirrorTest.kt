package com.letta.mobile.desktop.input

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PenMouseMirrorTest {

    @Test
    fun emulatedMouseIsNotPostedAgain() {
        assertFalse(penSampleMirrorsToMouse(TabletBridge.TOOL_EMULATED, windowMoved = false, hasPressureAxis = true))
    }

    @Test
    fun aRealPenStillClicksWhileTheWindowIsStill() {
        assertTrue(penSampleMirrorsToMouse(TabletBridge.TOOL_DRAW, windowMoved = false, hasPressureAxis = true))
        assertTrue(penSampleMirrorsToMouse(TabletBridge.TOOL_ERASER, windowMoved = false, hasPressureAxis = true))
    }

    @Test
    fun aPenDownWithNoPoseWaitsForOne() {
        assertTrue(penDownWaitsForPose(TabletBridge.TOOL_DRAW, TabletBridge.KIND_DOWN, TabletBridge.NO_PRESSURE))
        assertTrue(isPenNib(TabletBridge.TOOL_ERASER))
        assertFalse(penDownWaitsForPose(TabletBridge.TOOL_DRAW, TabletBridge.KIND_MOVE, 0.4f))
        assertFalse(penDownWaitsForPose(TabletBridge.TOOL_TOUCH, TabletBridge.KIND_DOWN, TabletBridge.NO_PRESSURE))
    }

    @Test
    fun aFingerHasNoPressureAxisAndIsNotPostedAsAMouse() {
        assertFalse(penSampleMirrorsToMouse(TabletBridge.TOOL_DRAW, windowMoved = false, hasPressureAxis = false))
        assertFalse(penSampleMirrorsToMouse(TabletBridge.TOOL_UNKNOWN, windowMoved = false, hasPressureAxis = false))
    }

    @Test
    fun samplesDuringAWindowDragAreNotPostedAsMouseInput() {
        assertFalse(penSampleMirrorsToMouse(TabletBridge.TOOL_DRAW, windowMoved = true, hasPressureAxis = true))
        assertFalse(penSampleMirrorsToMouse(TabletBridge.TOOL_ERASER, windowMoved = true, hasPressureAxis = true))
    }

    @Test
    fun aFingerOrAnInkCancelTakesTheTouchPath() {
        assertTrue(isFingerSample(TabletBridge.TOOL_TOUCH, TabletBridge.KIND_MOVE))
        assertTrue(isFingerSample(TabletBridge.TOOL_UNKNOWN, TabletBridge.KIND_CANCEL))
        assertFalse(isFingerSample(TabletBridge.TOOL_DRAW, TabletBridge.KIND_MOVE))
    }

    @Test
    fun aRejectedToolsPhaseEventIsNeitherInkNorAClick() {
        assertTrue(isRejectedToolPhase(TabletBridge.TOOL_UNKNOWN, TabletBridge.KIND_UP, lastPoseHadPressure = false))
        // A nib, a tool whose pose had pressure, or a tool coming into range is the pen's.
        assertFalse(isRejectedToolPhase(TabletBridge.TOOL_DRAW, TabletBridge.KIND_UP, lastPoseHadPressure = false))
        assertFalse(isRejectedToolPhase(TabletBridge.TOOL_UNKNOWN, TabletBridge.KIND_UP, lastPoseHadPressure = true))
        assertFalse(isRejectedToolPhase(TabletBridge.TOOL_UNKNOWN, TabletBridge.KIND_IN, lastPoseHadPressure = false))
    }

    @Test
    fun liftingIsUpOrOutOfRange() {
        assertTrue(isLift(TabletBridge.KIND_UP))
        assertTrue(isLift(TabletBridge.KIND_OUT))
        assertFalse(isLift(TabletBridge.KIND_MOVE))
    }
}
