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
    fun `wheel rotation inverts Compose's Windows scroll formula, scaled by the calibrated gain`() {
        // WindowsWinUIConfig resolves a wheel event to
        // -preciseWheelRotation * scrollAmount * (extent / 20) pixels. When the
        // extent handed to both sides of the formula is the same value, the
        // round trip reproduces dragPx scaled by TOUCH_SCROLL_GAIN exactly --
        // the gain is a deliberate multiplier here, not an inversion error.
        val extent = 800
        val dragPx = -120f
        val rotation = wheelRotationForDrag(dragPx, extent)

        val composePixels = -rotation * (extent / WHEEL_SCROLL_DIVISOR)
        assertEquals(dragPx.toDouble() * TOUCH_SCROLL_GAIN, composePixels, 0.001)
    }

    @Test
    fun `the calibrated gain closes the transcript's title-bar-and-chrome extent gap`() {
        // viewportExtentPx is what DesktopWindowsTouchInput actually has: the
        // whole window content area, because AWT delivers touch through one
        // Skia canvas per window (see wheelRotationForDrag's KDoc). trueBoundsPx
        // approximates what WindowsWinUIConfig actually divides by on the other
        // side -- the chat transcript LazyColumn's own height once the 48dp
        // title bar and composer chrome are excluded -- reproducing the ratio
        // TOUCH_SCROLL_GAIN was tuned against.
        val viewportExtentPx = 800
        val trueBoundsPx = 650
        val dragPx = -200f

        val rotation = wheelRotationForDrag(dragPx, viewportExtentPx)
        val actualPixelsMoved = -rotation * (trueBoundsPx / WHEEL_SCROLL_DIVISOR)

        // Within 5% of tracking the finger 1:1, versus the ~19% shortfall
        // (650/800) an uncorrected formula would produce.
        assertEquals(dragPx.toDouble(), actualPixelsMoved, abs(dragPx.toDouble()) * 0.05)
    }

    @Test
    fun `dragging up scrolls the content up, matching a positive wheel rotation`() {
        assertTrue(wheelRotationForDrag(dragPx = -50f, viewportExtentPx = 500) > 0.0)
        assertTrue(wheelRotationForDrag(dragPx = 50f, viewportExtentPx = 500) < 0.0)
    }

    @Test
    fun `sequential drag events lose no fractional distance to rounding`() {
        // wheelRotationForDrag returns a Double and DesktopWindowsTouchInput
        // hands the same Double straight through as MouseWheelEvent's
        // preciseWheelRotation (the field WindowsWinUIConfig actually reads on
        // Windows -- see ComposeSceneMediator.onMouseWheelEvent). Nothing here
        // rounds or truncates per event, so many small per-event drags must sum
        // to the same total as one big drag of the same overall distance,
        // instead of leaking a fractional remainder to the void on every frame.
        val extent = 800
        val wholeDragRotation = wheelRotationForDrag(dragPx = -10f, extent)
        var summedRotation = 0.0
        repeat(10) { summedRotation += wheelRotationForDrag(dragPx = -1f, extent) }
        assertEquals(wholeDragRotation, summedRotation, 1e-6)
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

    /**
     * Regression coverage for letta-mobile#1258: a real finger drag on the
     * tab strip logged the title bar's published bounds as
     * `Rectangle[x=95,y=0,width=2219,height=84]` and the very next press at
     * screen `(180,68)` -- which lies inside that rectangle -- still came
     * back `excluded=false`, so the drag got mistranslated into wheel-scroll
     * instead of passing through untouched. This pins the registry's own
     * math against those exact numbers: it resolves correctly here, which
     * is what narrows the real bug to the *glue* around the registry
     * (window identity resolution in `TouchTranslatingEventQueue.
     * managedWindow`/`dispatchIfExcluded`, or the screen-coordinate source),
     * not the registry itself.
     *
     * This is also why the suite above never caught the real failure: every
     * other registry test constructs its own small, hand-picked rectangle
     * and calls `contains` directly with a plain `Any()` key -- exercising
     * `DesktopTouchDragExclusionRegistry` in isolation, never
     * `dispatchIfExcluded`'s actual window-resolution path
     * (`managedWindow`'s `SwingUtilities.getWindowAncestor` +
     * `event.xOnScreen`/`yOnScreen`) that only runs against a real AWT
     * `MouseEvent` and `Window` -- exactly the two things a headless test
     * runner can't easily construct (see this file's own header comment on
     * why `Any()` stands in for `Window` here). A bug confined to that glue
     * is invisible to a suite that only ever calls the registry directly.
     */
    @Test
    fun `a press inside the exact real-world title bar rectangle from letta-mobile#1258 resolves as excluded`() {
        val registry = DesktopTouchDragExclusionRegistry<Any>()
        val window = Any()
        registry.publish(window, java.awt.Rectangle(95, 0, 2219, 84))

        assertTrue(registry.contains(window, screenX = 180, screenY = 68))
    }

    // --- screenExclusionRectOrNull ---------------------------------------
    //
    // Regression coverage for a real startup crash: Compose's
    // LayoutCoordinates.positionOnScreen() returns Offset.Unspecified (NaN in
    // both components) before the title bar's layout is attached to a
    // screen, which happens during the window's very first composition.
    // roundToInt() throws IllegalArgumentException on NaN, and that crashed
    // app startup outright. These tests pin the guard, and the fact that the
    // resulting null flows harmlessly through the publish path instead of
    // throwing or leaving a stale rectangle behind.

    @Test
    fun `a NaN screen position never throws and yields no rectangle`() {
        assertNull(screenExclusionRectOrNull(Float.NaN, Float.NaN, width = 800, height = 48))
        assertNull(screenExclusionRectOrNull(Float.NaN, 20f, width = 800, height = 48))
        assertNull(screenExclusionRectOrNull(400f, Float.NaN, width = 800, height = 48))
    }

    @Test
    fun `an infinite screen position also yields no rectangle`() {
        assertNull(screenExclusionRectOrNull(Float.POSITIVE_INFINITY, 20f, width = 800, height = 48))
        assertNull(screenExclusionRectOrNull(400f, Float.NEGATIVE_INFINITY, width = 800, height = 48))
    }

    @Test
    fun `a finite screen position builds the expected rectangle`() {
        val rect = assertNotNull(screenExclusionRectOrNull(100f, 200.4f, width = 800, height = 48))
        assertEquals(java.awt.Rectangle(100, 200, 800, 48), rect)
    }

    @Test
    fun `publishing a NaN-derived bounds never throws and leaves the window not excluded`() {
        val registry = DesktopTouchDragExclusionRegistry<Any>()
        val window = Any()

        // Simulates the exact call site in DesktopJewelWindow.kt: the result
        // of screenExclusionRectOrNull is fed straight into publish().
        registry.publish(window, screenExclusionRectOrNull(Float.NaN, Float.NaN, width = 800, height = 48))

        assertFalse(registry.contains(window, screenX = 10, screenY = 10))
    }

    @Test
    fun `a NaN position clears a previously published rectangle rather than leaving it stale`() {
        val registry = DesktopTouchDragExclusionRegistry<Any>()
        val window = Any()
        registry.publish(window, java.awt.Rectangle(0, 0, 800, 48))
        assertTrue(registry.contains(window, screenX = 10, screenY = 10))

        // A later layout pass reports an unresolved position (e.g. the
        // window was briefly detached). The fail-safe direction is "not
        // excluded" — a stale rectangle sitting over ordinary content would
        // silently and durably break scrolling there, which is worse than
        // the title bar losing touch-drag-to-move for one frame.
        registry.publish(window, screenExclusionRectOrNull(Float.NaN, Float.NaN, width = 800, height = 48))

        assertFalse(registry.contains(window, screenX = 10, screenY = 10))
    }
}
