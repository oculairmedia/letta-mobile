package com.letta.mobile.desktop.touch

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the parts of the Windows touch shim that do not need a touchscreen:
 * the tap-vs-scroll state machine, the axis lock, the fling decay, and the
 * wheel-rotation inversion of Compose's Windows scroll formula.
 */
class DesktopTouchScrollGestureTest {

    @Test
    fun `movement inside the slop is a tap and emits no scroll`() {
        val gesture = DesktopTouchDragGesture(slopPx = 12)
        gesture.press(TouchSample(x = 100, y = 100, timeMillis = 0))

        assertNull(gesture.drag(TouchSample(x = 103, y = 104, timeMillis = 16)))
        assertNull(gesture.drag(TouchSample(x = 105, y = 108, timeMillis = 32)))

        assertEquals(TouchGestureEnd.Tap, gesture.release(TouchSample(x = 105, y = 108, timeMillis = 48)))
        assertEquals(false, gesture.isScrolling)
    }

    @Test
    fun `crossing the slop vertically emits only the step since the last sample`() {
        val gesture = DesktopTouchDragGesture(slopPx = 12)
        gesture.press(TouchSample(x = 100, y = 200, timeMillis = 0))
        assertNull(gesture.drag(TouchSample(x = 100, y = 195, timeMillis = 16)))

        // Crosses the slop at y = 180. Replaying the whole 20px press-to-here
        // vector here would visibly jump the content under the finger.
        val delta = assertNotNull(gesture.drag(TouchSample(x = 100, y = 180, timeMillis = 32)))
        assertEquals(-15f, delta.dy)
        assertEquals(0f, delta.dx)
        assertEquals(TouchScrollAxis.Vertical, gesture.axis)
    }

    @Test
    fun `a dominantly horizontal drag locks to the horizontal axis and stays there`() {
        val gesture = DesktopTouchDragGesture(slopPx = 12)
        gesture.press(TouchSample(x = 300, y = 100, timeMillis = 0))

        val first = assertNotNull(gesture.drag(TouchSample(x = 260, y = 105, timeMillis = 16)))
        assertEquals(TouchScrollAxis.Horizontal, gesture.axis)
        assertEquals(-40f, first.dx)
        assertEquals(0f, first.dy)

        // A later vertical wobble must not leak into the locked gesture,
        // otherwise a horizontal carousel fights the surrounding list.
        val second = assertNotNull(gesture.drag(TouchSample(x = 250, y = 200, timeMillis = 32)))
        assertEquals(-10f, second.dx)
        assertEquals(0f, second.dy)
    }

    @Test
    fun `release after scrolling reports the lift-off velocity on the locked axis only`() {
        val gesture = DesktopTouchDragGesture(slopPx = 12)
        gesture.press(TouchSample(x = 100, y = 400, timeMillis = 0))
        gesture.drag(TouchSample(x = 100, y = 380, timeMillis = 20))
        gesture.drag(TouchSample(x = 100, y = 340, timeMillis = 40))
        gesture.drag(TouchSample(x = 100, y = 300, timeMillis = 60))

        val end = gesture.release(TouchSample(x = 100, y = 260, timeMillis = 80))
        assertIs<TouchGestureEnd.Scrolled>(end)
        val scrolled: TouchGestureEnd.Scrolled = end
        assertEquals(TouchScrollAxis.Vertical, scrolled.axis)
        assertEquals(0f, scrolled.velocityX)
        // 100px upward over the trailing 100ms window.
        assertTrue(scrolled.velocityY < -1.5f, "expected an upward fling velocity, got ${scrolled.velocityY}")
    }

    @Test
    fun `velocity is clamped so a jittery sample cannot launch a runaway fling`() {
        val gesture = DesktopTouchDragGesture(slopPx = 12, maxVelocityPxPerMs = 4f)
        gesture.press(TouchSample(x = 100, y = 900, timeMillis = 0))
        gesture.drag(TouchSample(x = 100, y = 700, timeMillis = 4))

        val end = gesture.release(TouchSample(x = 100, y = 100, timeMillis = 8)) as TouchGestureEnd.Scrolled
        assertEquals(-4f, end.velocityY)
    }

    @Test
    fun `a fling decays monotonically and terminates`() {
        val fling = DesktopTouchFling(velocityX = 0f, velocityY = -3f)
        var previous = Float.MAX_VALUE
        var frames = 0
        var total = 0f
        while (true) {
            val delta = fling.advance(16) ?: break
            val travelled = abs(delta.dy)
            assertTrue(travelled <= previous, "fling accelerated: $travelled > $previous")
            previous = travelled
            total += travelled
            frames++
            assertTrue(frames < 1_000, "fling never settled")
        }
        assertTrue(fling.isFinished)
        assertTrue(total > 100f, "expected a meaningful coast, travelled $total")
    }

    @Test
    fun `a fling below the visible-motion threshold never starts`() {
        val fling = DesktopTouchFling(velocityX = 0f, velocityY = 0.01f)
        assertTrue(fling.isFinished)
        assertNull(fling.advance(16))
    }

    @Test
    fun `wheel rotation inverts Compose's Windows scroll formula`() {
        // WindowsWinUIConfig resolves a wheel event to
        // -preciseWheelRotation * scrollAmount * (extent / 20) pixels.
        val extent = 800
        val dragPx = -120f
        val rotation = wheelRotationForDrag(dragPx, extent)

        val composePixels = -rotation * (extent / WHEEL_SCROLL_DIVISOR)
        assertEquals(dragPx.toDouble(), composePixels, 0.001)
    }

    @Test
    fun `dragging up scrolls the content up, matching a positive wheel rotation`() {
        assertTrue(wheelRotationForDrag(dragPx = -50f, viewportExtentPx = 500) > 0.0)
        assertTrue(wheelRotationForDrag(dragPx = 50f, viewportExtentPx = 500) < 0.0)
    }

    @Test
    fun `a zero-sized viewport cannot produce an infinite rotation`() {
        assertEquals(0.0, wheelRotationForDrag(dragPx = 50f, viewportExtentPx = 0))
    }
}
