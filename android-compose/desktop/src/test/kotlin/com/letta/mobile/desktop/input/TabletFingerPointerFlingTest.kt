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

    @Test
    fun aSliderFingerHoldsTheButtonFromItsPlacedPoint() {
        val harness = Harness()
        harness.pointer.onSample(harness.surface, sample(TabletBridge.KIND_DOWN, 0f, 0f))
        harness.pointer.onControlSample(harness.surface, sample(TabletBridge.KIND_MOVE, 200f, 100f))
        harness.pointer.onControlSample(harness.surface, sample(TabletBridge.KIND_MOVE, 260f, 102f))
        assertTrue(harness.pointer.isHolding)
        harness.pointer.onControlSample(harness.surface, sample(TabletBridge.KIND_UP, 0f, 0f))

        val held = harness.surface.buttons.map { Triple(it.id, it.x, it.y) }
        assertEquals(
            listOf(
                Triple(MouseEvent.MOUSE_PRESSED, 200, 100),
                Triple(MouseEvent.MOUSE_DRAGGED, 260, 102),
                Triple(MouseEvent.MOUSE_RELEASED, 260, 102),
            ),
            held,
        )
        assertEquals(0, harness.surface.wheels.size)
        assertFalse(harness.pointer.isHolding)
    }

    @Test
    fun theBoardHoldPressesWhereTheFingerLandedAndDragsToIt() {
        val harness = Harness()
        harness.pointer.hold(harness.surface, java.awt.Point(10, 20), java.awt.Point(40, 5))
        harness.pointer.cancelScroll()
        harness.pointer.dragHeld(java.awt.Point(60, 5))
        harness.pointer.releaseControl()

        assertEquals(
            listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_RELEASED),
            harness.surface.buttons.map { it.id },
        )
        assertEquals(10, harness.surface.buttons.first().x)
        assertEquals(60, harness.surface.buttons.last().x)
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

    private companion object {
        val HELD_IDS = setOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_RELEASED)
    }

    private class RecordingSurface : Component() {
        val wheels = mutableListOf<MouseWheelEvent>()
        val clicks = mutableListOf<MouseEvent>()
        val buttons = mutableListOf<MouseEvent>()

        init {
            enableEvents(
                AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK,
            )
            setSize(800, 600)
        }

        override fun processEvent(event: AWTEvent) {
            when (event) {
                is MouseWheelEvent -> wheels.add(event)
                is MouseEvent -> {
                    if (event.id == MouseEvent.MOUSE_CLICKED) clicks.add(event)
                    if (event.id in HELD_IDS) buttons.add(event)
                }
            }
        }
    }
}
