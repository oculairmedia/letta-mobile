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
    fun aFingerDragScrollsAndAShortLiftClicks() {
        val contact = FingerContact()
        contact.down()
        // The down sample is the previous pose. The first move places the finger and must not scroll.
        assertFalse(contact.move(400, 300))
        assertFalse(contact.move(404, 302))
        val tap = contact.up()
        assertTrue(tap != null && tap.tap)
        assertTrue(tap!!.x == 400 && tap.y == 300)

        contact.down()
        assertFalse(contact.move(400, 300))
        assertTrue(contact.move(430, 300))
        val drag = contact.up()
        assertTrue(drag != null && !drag.tap)
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
}
