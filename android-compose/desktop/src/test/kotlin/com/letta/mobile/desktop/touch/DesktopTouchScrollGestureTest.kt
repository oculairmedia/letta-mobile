package com.letta.mobile.desktop.touch

import java.awt.event.MouseEvent
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // --- DesktopTouchGestureLatch --------------------------------------
    //
    // Measured on real Windows touch hardware: isCausedByTouchEvent is true
    // only on MOUSE_PRESSED/MOUSE_RELEASED, and always false on
    // MOUSE_DRAGGED/MOUSE_CLICKED, for touch input just as much as for a real
    // mouse. These tests feed exactly that realistic flag pattern — never a
    // flagged drag or click — through the latch, and (where relevant) through
    // the drag gesture it gates, to prove the fix works against the event
    // stream real hardware actually produces rather than an idealized one.

    @Test
    fun `a full touch gesture stays classified as touch through unflagged drags and click, and scrolls`() {
        val latch = DesktopTouchGestureLatch()
        val gesture = DesktopTouchDragGesture(slopPx = 12)

        // Flagged press, as measured on real hardware.
        assertTrue(latch.classify(MouseEvent.MOUSE_PRESSED, accessorSaysTouch = true))
        gesture.press(TouchSample(x = 100, y = 400, timeMillis = 0))

        // Unflagged drags: the accessor lies (always false), but the latch
        // must still report touch so the drag reaches the gesture and scrolls.
        assertTrue(latch.classify(MouseEvent.MOUSE_DRAGGED, accessorSaysTouch = false))
        assertNull(gesture.drag(TouchSample(x = 100, y = 396, timeMillis = 16)))

        assertTrue(latch.classify(MouseEvent.MOUSE_DRAGGED, accessorSaysTouch = false))
        val delta = assertNotNull(gesture.drag(TouchSample(x = 100, y = 360, timeMillis = 32)))
        assertTrue(delta.dy < 0f, "expected upward scroll output, got $delta")

        // Flagged release, as measured on real hardware.
        assertTrue(latch.classify(MouseEvent.MOUSE_RELEASED, accessorSaysTouch = true))
        val end = gesture.release(TouchSample(x = 100, y = 340, timeMillis = 48))
        assertIs<TouchGestureEnd.Scrolled>(end)

        // Trailing unflagged click must still read back as touch.
        assertTrue(latch.classify(MouseEvent.MOUSE_CLICKED, accessorSaysTouch = false))
    }

    @Test
    fun `a realistic mouse drag is never classified as touch and never reaches the gesture`() {
        val latch = DesktopTouchGestureLatch()

        assertFalse(latch.classify(MouseEvent.MOUSE_PRESSED, accessorSaysTouch = false))
        assertFalse(latch.classify(MouseEvent.MOUSE_DRAGGED, accessorSaysTouch = false))
        assertFalse(latch.classify(MouseEvent.MOUSE_DRAGGED, accessorSaysTouch = false))
        assertFalse(latch.classify(MouseEvent.MOUSE_RELEASED, accessorSaysTouch = false))
        assertFalse(latch.classify(MouseEvent.MOUSE_CLICKED, accessorSaysTouch = false))

        // The production dispatch loop never even calls into
        // DesktopTouchDragGesture when isTouch is false — a mouse drag
        // produces no scroll simply by never reaching it.
    }

    @Test
    fun `a tap's trailing unflagged click does not downgrade the origin from touch`() {
        val latch = DesktopTouchGestureLatch()
        val origin = DesktopTouchOriginTracker()

        origin.record(latch.classify(MouseEvent.MOUSE_PRESSED, accessorSaysTouch = true), atMillis = 0)
        origin.record(latch.classify(MouseEvent.MOUSE_RELEASED, accessorSaysTouch = true), atMillis = 10)
        // AWT's synthesized click never carries the touch flag, even for a tap.
        origin.record(latch.classify(MouseEvent.MOUSE_CLICKED, accessorSaysTouch = false), atMillis = 11)

        assertTrue(origin.wasTouch(nowMillis = 11))
    }

    @Test
    fun `a click with no matching press falls back to its own accessor verdict instead of throwing`() {
        val latch = DesktopTouchGestureLatch()
        // Shim attached mid-gesture: no MOUSE_PRESSED was ever latched.
        assertFalse(latch.classify(MouseEvent.MOUSE_CLICKED, accessorSaysTouch = false))
    }

    @Test
    fun `a release with no matching press falls back to its own accessor verdict instead of throwing`() {
        val latch = DesktopTouchGestureLatch()
        assertTrue(latch.classify(MouseEvent.MOUSE_RELEASED, accessorSaysTouch = true))
    }

    @Test
    fun `a mouse press right after a touch gesture is never classified as touch`() {
        val latch = DesktopTouchGestureLatch()
        assertTrue(latch.classify(MouseEvent.MOUSE_PRESSED, accessorSaysTouch = true))
        assertTrue(latch.classify(MouseEvent.MOUSE_RELEASED, accessorSaysTouch = true))
        assertTrue(latch.classify(MouseEvent.MOUSE_CLICKED, accessorSaysTouch = false))

        // A fresh press always re-latches from its own flag, so it cannot
        // inherit the previous gesture's touch classification.
        assertFalse(latch.classify(MouseEvent.MOUSE_PRESSED, accessorSaysTouch = false))
        assertFalse(latch.classify(MouseEvent.MOUSE_DRAGGED, accessorSaysTouch = false))
        assertFalse(latch.classify(MouseEvent.MOUSE_RELEASED, accessorSaysTouch = false))
    }

    @Test
    fun `a touch press right after a mouse gesture is never classified as mouse`() {
        val latch = DesktopTouchGestureLatch()
        assertFalse(latch.classify(MouseEvent.MOUSE_PRESSED, accessorSaysTouch = false))
        assertFalse(latch.classify(MouseEvent.MOUSE_RELEASED, accessorSaysTouch = false))

        assertTrue(latch.classify(MouseEvent.MOUSE_PRESSED, accessorSaysTouch = true))
        assertTrue(latch.classify(MouseEvent.MOUSE_DRAGGED, accessorSaysTouch = false))
        assertTrue(latch.classify(MouseEvent.MOUSE_RELEASED, accessorSaysTouch = true))
    }

    @Test
    fun `enter, move and wheel events pass the accessor verdict through unlatched`() {
        val latch = DesktopTouchGestureLatch()
        assertTrue(latch.classify(MouseEvent.MOUSE_PRESSED, accessorSaysTouch = true))

        // A hover/move/enter/wheel event mid-gesture (stylus, hover-capable
        // digitizer) is not part of gesture tracking and must not consult or
        // disturb the latch.
        assertFalse(latch.classify(MouseEvent.MOUSE_MOVED, accessorSaysTouch = false))
        assertFalse(latch.classify(MouseEvent.MOUSE_ENTERED, accessorSaysTouch = false))
        assertFalse(latch.classify(MouseEvent.MOUSE_WHEEL, accessorSaysTouch = false))

        // The latched press classification survives untouched.
        assertTrue(latch.classify(MouseEvent.MOUSE_DRAGGED, accessorSaysTouch = false))
    }

    // --- DesktopTouchDragExclusionLatch --------------------------------

    @Test
    fun `a press inside an excluded region passes through unmodified`() {
        val latch = DesktopTouchDragExclusionLatch()
        assertTrue(latch.classify(MouseEvent.MOUSE_PRESSED) { true })
    }

    @Test
    fun `a press outside any excluded region still scrolls`() {
        val latch = DesktopTouchDragExclusionLatch()
        assertFalse(latch.classify(MouseEvent.MOUSE_PRESSED) { false })
        assertFalse(latch.classify(MouseEvent.MOUSE_DRAGGED) { error("must not be consulted mid-gesture") })
        assertFalse(latch.classify(MouseEvent.MOUSE_RELEASED) { error("must not be consulted mid-gesture") })
    }

    @Test
    fun `a gesture starting inside an excluded region stays excluded after the finger wanders out`() {
        val latch = DesktopTouchDragExclusionLatch()
        assertTrue(latch.classify(MouseEvent.MOUSE_PRESSED) { true })

        // The finger has left the region; if this were re-evaluated per event
        // the gesture would flip to scrolling mid-drag. Only the press may
        // consult the predicate at all — a lambda that would answer "false"
        // must never even run for a dragged/released event.
        assertTrue(latch.classify(MouseEvent.MOUSE_DRAGGED) { error("must not be consulted mid-gesture") })
        assertTrue(latch.classify(MouseEvent.MOUSE_DRAGGED) { error("must not be consulted mid-gesture") })
        assertTrue(latch.classify(MouseEvent.MOUSE_RELEASED) { error("must not be consulted mid-gesture") })
    }

    @Test
    fun `a plain excluded drag with no trailing click leaves the latch set, but the next press still re-evaluates`() {
        val latch = DesktopTouchDragExclusionLatch()
        assertTrue(latch.classify(MouseEvent.MOUSE_PRESSED) { true })
        // A drag beyond the click threshold: AWT never synthesizes MOUSE_CLICKED,
        // so the latch has nothing to reset it on. It stays "excluded" — inert
        // until a new gesture's own press unconditionally overwrites it.
        assertTrue(latch.classify(MouseEvent.MOUSE_RELEASED) { true })

        assertFalse(latch.classify(MouseEvent.MOUSE_PRESSED) { false })
        assertFalse(latch.classify(MouseEvent.MOUSE_DRAGGED) { error("must not be consulted mid-gesture") })
    }

    @Test
    fun `the exclusion latch clears after a tap's trailing click`() {
        val latch = DesktopTouchDragExclusionLatch()
        assertTrue(latch.classify(MouseEvent.MOUSE_PRESSED) { true })
        assertTrue(latch.classify(MouseEvent.MOUSE_RELEASED) { true })
        // MOUSE_CLICKED reports the still-latched verdict, then clears it.
        assertTrue(latch.classify(MouseEvent.MOUSE_CLICKED) { error("must not be consulted on click") })

        assertFalse(latch.classify(MouseEvent.MOUSE_PRESSED) { false })
    }

    @Test
    fun `a release or click with no matching press degrades to not excluded instead of guessing`() {
        // Shim attached mid-gesture: no MOUSE_PRESSED was ever latched.
        assertFalse(DesktopTouchDragExclusionLatch().classify(MouseEvent.MOUSE_RELEASED) { true })
        assertFalse(DesktopTouchDragExclusionLatch().classify(MouseEvent.MOUSE_CLICKED) { true })
    }

    // --- DesktopTouchDragExclusionRegistry ------------------------------
    //
    // Tested against the generic registry with plain Any() stand-ins for the
    // window key: WeakHashMap keys on identity, so any distinct object works,
    // and a bare Any() never touches AWT or risks a HeadlessException the way
    // constructing a real java.awt.Window would on a display-less test runner.

    @Test
    fun `an unpublished window degrades to not excluded instead of throwing`() {
        val registry = DesktopTouchDragExclusionRegistry<Any>()
        val window = Any()
        assertFalse(registry.contains(window, screenX = 0, screenY = 0))
    }

    @Test
    fun `a point inside the published rect is excluded, a point outside is not`() {
        val registry = DesktopTouchDragExclusionRegistry<Any>()
        val window = Any()
        registry.publish(window, java.awt.Rectangle(100, 200, 300, 48))

        assertTrue(registry.contains(window, screenX = 150, screenY = 210))
        assertFalse(registry.contains(window, screenX = 50, screenY = 210))
        assertFalse(registry.contains(window, screenX = 150, screenY = 500))
    }

    @Test
    fun `clearing the published bounds degrades back to not excluded`() {
        val registry = DesktopTouchDragExclusionRegistry<Any>()
        val window = Any()
        registry.publish(window, java.awt.Rectangle(0, 0, 100, 100))
        assertTrue(registry.contains(window, screenX = 10, screenY = 10))

        registry.publish(window, null)
        assertFalse(registry.contains(window, screenX = 10, screenY = 10))
    }

    @Test
    fun `two windows keep independent excluded regions`() {
        val registry = DesktopTouchDragExclusionRegistry<Any>()
        val windowA = Any()
        val windowB = Any()
        registry.publish(windowA, java.awt.Rectangle(0, 0, 50, 50))

        assertTrue(registry.contains(windowA, screenX = 10, screenY = 10))
        assertFalse(registry.contains(windowB, screenX = 10, screenY = 10))
    }

    // --- DesktopTouchDragExclusionLatch + DesktopTouchDragExclusionRegistry,
    // wired together the way TouchTranslatingEventQueue.dispatchEvent does ---

    @Test
    fun `end to end, a press inside the published title bar bounds is excluded and a press outside scrolls`() {
        val registry = DesktopTouchDragExclusionRegistry<Any>()
        val window = Any()
        registry.publish(window, java.awt.Rectangle(0, 0, 800, 48))

        val insideGesture = DesktopTouchDragExclusionLatch()
        assertTrue(insideGesture.classify(MouseEvent.MOUSE_PRESSED) { registry.contains(window, 400, 20) })

        val outsideGesture = DesktopTouchDragExclusionLatch()
        assertFalse(outsideGesture.classify(MouseEvent.MOUSE_PRESSED) { registry.contains(window, 400, 300) })
    }
}
