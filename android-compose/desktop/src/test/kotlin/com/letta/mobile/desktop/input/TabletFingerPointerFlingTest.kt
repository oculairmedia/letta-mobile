package com.letta.mobile.desktop.input

import com.letta.mobile.desktop.touch.DesktopSmoothScrollSwitch
import com.letta.mobile.desktop.touch.DesktopTouchSmoothScrollSuppressor
import java.awt.AWTEvent
import java.awt.Component
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabletFingerPointerFlingTest {

    @Test
    fun aTapNeverStartsAFlingOrScrolls() {
        val harness = Harness()
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_DOWN, 0f, 0f))
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_MOVE, 100f, 300f))
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_MOVE, 104f, 303f))
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_UP, 104f, 303f))

        assertEquals(0, harness.surface.wheels.size)
        assertFalse(harness.pointer.isFlinging)
        assertEquals(0, harness.disableCalls)
        val click = harness.surface.clicks.single()
        assertEquals(100, click.x)
        assertEquals(300, click.y)
    }

    @Test
    fun theStaleDownPointDoesNotSeedVelocity() {
        val harness = Harness()
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_DOWN, 0f, 0f))
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_MOVE, 100f, 300f))
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_UP, 0f, 0f))

        assertEquals(0, harness.surface.wheels.size)
        assertFalse(harness.pointer.isFlinging)
    }

    @Test
    fun aFastDragLiftStartsAFlingAndKeepsSuppressionOn() {
        val harness = Harness()
        flick(harness)
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_UP, 100f, 400f))

        assertTrue(harness.pointer.isFlinging)
        assertTrue(harness.surface.wheels.isNotEmpty())
        assertEquals(0, harness.enableCalls)
        harness.pointer.cancel()
    }

    @Test
    fun aSlowLiftDoesNotFling() {
        val harness = Harness()
        flick(harness)
        harness.now += FingerFlingVelocity.FINGER_STOPPED_MILLIS + 10
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_UP, 100f, 400f))

        assertFalse(harness.pointer.isFlinging)
        assertTrue(harness.enableCalls > 0)
    }

    @Test
    fun aNewDownCancelsTheFling() {
        val harness = Harness()
        flick(harness)
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_UP, 100f, 400f))
        assertTrue(harness.pointer.isFlinging)

        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_DOWN, 10f, 10f))
        assertFalse(harness.pointer.isFlinging)
        assertTrue(harness.enableCalls > 0)
    }

    @Test
    fun cancelStopsTheFling() {
        val harness = Harness()
        flick(harness)
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_UP, 100f, 400f))
        assertTrue(harness.pointer.isFlinging)

        harness.pointer.cancel()
        assertFalse(harness.pointer.isFlinging)
        assertTrue(harness.enableCalls > 0)
    }

    private fun flick(harness: Harness) {
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_DOWN, 0f, 0f))
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_MOVE, 100f, 300f))
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_MOVE, 100f, 320f))
        harness.now += 16
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_MOVE, 100f, 360f))
        harness.now += 8
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_MOVE, 100f, 400f))
    }

    private fun sample(kind: Int, x: Float, y: Float) = TabletPenDecoder.DecodedSample(
        kind = kind,
        x = x,
        y = y,
        rawX = x,
        rawY = y,
        force = TabletBridge.NO_PRESSURE,
        tool = TabletBridge.TOOL_UNKNOWN,
    )

    private class Harness {
        var now = 1_000L
        var enableCalls = 0
        var disableCalls = 0
        val surface = RecordingSurface()
        val pointer = TabletFingerPointer(
            smoothScroll = DesktopTouchSmoothScrollSuppressor(
                DesktopSmoothScrollSwitch { enabled ->
                    if (enabled) enableCalls++ else disableCalls++
                },
            ),
            clock = { now },
            flingFrameMillis = 60_000,
        )
    }

    private class RecordingSurface : Component() {
        val wheels = mutableListOf<MouseWheelEvent>()
        val clicks = mutableListOf<MouseEvent>()

        init {
            enableEvents(AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
            setSize(800, 600)
        }

        override fun processEvent(event: AWTEvent) {
            when (event) {
                is MouseWheelEvent -> wheels.add(event)
                is MouseEvent -> if (event.id == MouseEvent.MOUSE_CLICKED) clicks.add(event)
            }
        }
    }
}
