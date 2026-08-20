package com.letta.mobile.desktop.touch

import com.letta.mobile.desktop.DesktopCrashReporter
import dev.nucleusframework.core.runtime.Platform
import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowEvent
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.roundToInt

/**
 * Makes Windows touchscreens usable in Letta Desktop by translating finger
 * drags into wheel scrolling.
 *
 * The chain this works around: AWT registers the window with
 * `RegisterTouchWindow`, so Windows delivers `WM_TOUCH` instead of legacy
 * gesture panning; AWT turns `WM_TOUCH` into plain [MouseEvent]s; Compose
 * Desktop's scene mediator stamps every AWT pointer with `PointerType.Mouse`;
 * and Compose Foundation's `CanDragCalculation` refuses drag scrolling for
 * mouse pointers. The net effect is that a finger drag scrolls nothing, and
 * because AWT took over touch there is no OS-level panning fallback either.
 *
 * The shim sits in front of Compose's own AWT listeners (a pushed [EventQueue]
 * is the only hook that is reliably earlier), withholds touch-caused
 * press/drag/release events, and replays them as either a tap (press+release,
 * unchanged) or a stream of synthetic [MouseWheelEvent]s plus a kinetic fling.
 * Wheel events are the one scroll input Compose Desktop accepts from AWT, and
 * they bypass the `PointerType.Mouse` drag ban entirely. Every synthetic wheel
 * event a touch drag emits would otherwise be smoothed through Compose's
 * `tween` animation like a real mouse wheel, visibly lagging the finger;
 * [DesktopTouchSmoothScrollSuppressor] turns that animation off for the
 * duration of each drag/fling and restores it afterwards.
 *
 * Measured directly on Windows touch hardware, with the exact reflective
 * accessor this shim uses: `isCausedByTouchEvent` is true only on
 * `MOUSE_PRESSED` and `MOUSE_RELEASED`. It is **always false on
 * `MOUSE_DRAGGED` and `MOUSE_CLICKED`**, touch or mouse alike. Classifying
 * each event independently — the original design — left drag-to-scroll and
 * the touch keyboard both permanently dark on real hardware, because the
 * event that actually carries the gesture (the drag) never reported touch.
 * [DesktopTouchGestureLatch] is the fix: the classification is decided once,
 * at press, and carried through drag/release/click by [TouchTranslatingEventQueue].
 *
 * A gesture that starts inside a region published to [DesktopTouchDragExclusion]
 * — e.g. Nucleus's title bar, whose own drag-to-move needs an unmolested
 * press+drag stream — is passed through untouched instead of being withheld
 * and translated, decided once at press and latched for the whole gesture.
 *
 * Windows only, and a no-op whenever the reflective touch hook is unavailable.
 */
internal object DesktopWindowsTouchInput {

    /** ~60Hz; matches the cadence Windows delivers touch moves at. */
    private const val FLING_FRAME_MILLIS = 16

    private val windows: MutableSet<Window> =
        Collections.newSetFromMap(WeakHashMap<Window, Boolean>())
    private val installed = AtomicBoolean(false)
    private val degradationLogged = AtomicBoolean(false)

    /**
     * Registers [window] for touch translation, installing the shared event
     * queue on first use. Safe to call more than once; safe to call on any
     * platform.
     */
    fun attach(window: Window) {
        if (Platform.Current != Platform.Windows) return
        windows.add(window)
        if (!installed.compareAndSet(false, true)) return

        val accessor = DesktopTouchEventAccessor.bindOrNull()
        if (accessor == null) {
            logDegradation(
                IllegalStateException(
                    "sun.awt.AWTAccessor.MouseEventAccessor is unavailable; touch drag-to-scroll " +
                        "and the touch keyboard stay disabled. Expected --add-opens " +
                        "java.desktop/sun.awt=ALL-UNNAMED on the launcher.",
                ),
            )
            return
        }

        val smoothScroll = DesktopTouchSmoothScrollSuppressor(DesktopSmoothScrollSwitch.bindOrNull())

        runCatching {
            Toolkit.getDefaultToolkit().systemEventQueue.push(TouchTranslatingEventQueue(accessor, smoothScroll))
        }.onFailure { error ->
            logDegradation(error)
            installed.set(false)
        }
    }

    private fun logDegradation(error: Throwable) {
        if (degradationLogged.compareAndSet(false, true)) {
            DesktopCrashReporter.logCrash(error, context = "windows touch input")
        }
    }

    /** The managed [Window] a component's events belong to, or null if the shim doesn't own it. */
    private fun managedWindow(component: Component?): Window? {
        val target = component ?: return null
        val window = target as? Window ?: SwingUtilities.getWindowAncestor(target)
        return window?.takeIf { it in windows }
    }

    /**
     * Intercepts touch-caused mouse events before Compose's own
     * `MouseListener`/`MouseMotionListener` ever see them.
     */
    private class TouchTranslatingEventQueue(
        private val accessor: DesktopTouchEventAccessor,
        private val smoothScroll: DesktopTouchSmoothScrollSuppressor,
    ) : EventQueue() {

        private val gesture = DesktopTouchDragGesture()

        /**
         * Decides touch-vs-mouse once per gesture, at `MOUSE_PRESSED`, and
         * carries that verdict through drag/release/click — see the class doc
         * above and [DesktopTouchGestureLatch] for why per-event classification
         * does not work on real hardware.
         */
        private val originLatch = DesktopTouchGestureLatch()

        /**
         * Whether the current gesture's `MOUSE_PRESSED` landed inside a
         * published [DesktopTouchDragExclusion] region (e.g. the title bar).
         * See [DesktopTouchDragExclusionLatch] for why only the press decides.
         */
        private val exclusionLatch = DesktopTouchDragExclusionLatch()

        /** Held back until the gesture is known to be a tap rather than a scroll. */
        private var withheldPress: MouseEvent? = null

        /**
         * AWT synthesizes MOUSE_CLICKED after a press/release pair; after a
         * scroll the press was never delivered, so the click must not be either.
         */
        private var suppressNextClick = false

        private var flingTimer: Timer? = null

        override fun dispatchEvent(event: AWTEvent) {
            if (isGestureInterruptingFocusLoss(event)) {
                // Safety net for an interrupted gesture: if the window loses
                // focus mid-drag, Windows may never deliver a MOUSE_RELEASED
                // for the finger still down. cancelFling() unconditionally
                // restores smooth scrolling, so this guarantees the
                // suppression never gets stuck off.
                cancelFling()
                super.dispatchEvent(event)
                return
            }
            if (event !is MouseEvent) {
                super.dispatchEvent(event)
                return
            }
            dispatchMouse(event)
        }

        private fun isGestureInterruptingFocusLoss(event: AWTEvent): Boolean =
            event is WindowEvent &&
                (event.id == WindowEvent.WINDOW_LOST_FOCUS || event.id == WindowEvent.WINDOW_DEACTIVATED)

        /** Entry point for every [MouseEvent] this queue is asked to dispatch. */
        private fun dispatchMouse(event: MouseEvent) {
            val component = event.source as? Component
            val window = managedWindow(component)
            if (window == null) {
                // Not one of ours (a different window, or a source with no
                // window ancestor at all): the shim has nothing to say about it.
                super.dispatchEvent(event)
                return
            }
            if (dispatchIfExcluded(event, window)) return
            dispatchClassified(event, requireNotNull(component))
        }

        /**
         * Handles [event] and returns true when it fell inside a
         * [DesktopTouchDragExclusion] region — e.g. Nucleus's title bar — and
         * was therefore passed straight through instead of being classified
         * as touch or mouse at all.
         */
        private fun dispatchIfExcluded(event: MouseEvent, window: Window): Boolean {
            val excluded = exclusionLatch.classify(event.id) {
                DesktopTouchDragExclusion.contains(window, event.xOnScreen, event.yOnScreen)
            }
            // TEMPORARY (letta-mobile #1249 touch-reorder diagnosis): confirms
            // whether a touch press landing on the tab strip is recognized as
            // falling inside the published title-bar exclusion region at all.
            if (event.id == MouseEvent.MOUSE_PRESSED) {
                System.err.println(
                    "TABTOUCHDIAG exclusion press windowIdentity=" + System.identityHashCode(window) +
                        " screen=(" + event.xOnScreen + "," + event.yOnScreen + ") excluded=" + excluded,
                )
            }
            if (!excluded) return false
            if (event.id == MouseEvent.MOUSE_PRESSED) cancelFling()
            super.dispatchEvent(event)
            return true
        }

        /** Classifies [event] as touch or mouse and routes it accordingly. */
        private fun dispatchClassified(event: MouseEvent, component: Component) {
            val isTouch = originLatch.classify(event.id, accessor.isCausedByTouchEvent(event))
            recordOrigin(event, isTouch)
            if (!isTouch) {
                if (event.id == MouseEvent.MOUSE_PRESSED) cancelFling()
                super.dispatchEvent(event)
                return
            }
            dispatchTouchGesture(event, component)
        }

        /** Feeds a touch-classified [event] into the press/drag/release gesture state machine. */
        private fun dispatchTouchGesture(event: MouseEvent, component: Component) {
            when (event.id) {
                MouseEvent.MOUSE_PRESSED -> onTouchPress(event)
                MouseEvent.MOUSE_DRAGGED -> onTouchDrag(event, component)
                MouseEvent.MOUSE_RELEASED -> onTouchRelease(event, component)
                MouseEvent.MOUSE_CLICKED ->
                    if (suppressNextClick) suppressNextClick = false else super.dispatchEvent(event)
                // Enter/exit/move carry no gesture state; letting them through
                // keeps hover affordances working under a stylus or hover-capable
                // digitizer.
                else -> super.dispatchEvent(event)
            }
        }

        /**
         * Records the latch-corrected [isTouch] verdict, not the accessor's raw
         * per-event flag. That matters most for `MOUSE_CLICKED`: the accessor
         * always reports false on it, so recording the raw flag would silently
         * downgrade every touch tap's origin to "mouse" right before the text
         * field asks whether to raise the keyboard.
         */
        private fun recordOrigin(event: MouseEvent, isTouch: Boolean) {
            when (event.id) {
                MouseEvent.MOUSE_PRESSED,
                MouseEvent.MOUSE_DRAGGED,
                MouseEvent.MOUSE_RELEASED,
                MouseEvent.MOUSE_CLICKED,
                MouseEvent.MOUSE_WHEEL,
                -> DesktopTouchOrigin.record(isTouch, System.currentTimeMillis())
            }
        }

        private fun onTouchPress(event: MouseEvent) {
            cancelFling()
            gesture.press(event.toSample())
            withheldPress = event
            suppressNextClick = false
        }

        private fun onTouchDrag(event: MouseEvent, component: Component) {
            val delta = gesture.drag(event.toSample()) ?: return
            // The gesture just committed to scrolling (or already had): keep
            // wheel deltas applying immediately instead of through Compose's
            // tween, so content tracks the finger 1:1. Idempotent — cheap to
            // call on every drag sample.
            smoothScroll.begin()
            withheldPress = null
            dispatchScroll(component, event, delta)
        }

        private fun onTouchRelease(event: MouseEvent, component: Component) {
            when (val end = gesture.release(event.toSample())) {
                TouchGestureEnd.Tap -> {
                    // A tap: hand Compose the press it never saw, then the
                    // release, so clicks and text-field focus behave normally.
                    withheldPress?.let { super.dispatchEvent(it) }
                    withheldPress = null
                    super.dispatchEvent(event)
                }

                is TouchGestureEnd.Scrolled -> {
                    withheldPress = null
                    suppressNextClick = true
                    startFling(component, event, end)
                }
            }
        }

        private fun dispatchScroll(component: Component, source: MouseEvent, delta: TouchScrollDelta) {
            val horizontal = delta.dx != 0f
            val extent = if (horizontal) component.width else component.height
            val rotation = wheelRotationForDrag(if (horizontal) delta.dx else delta.dy, extent)
            if (rotation == 0.0) return
            component.dispatchEvent(
                MouseWheelEvent(
                    component,
                    MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(),
                    // Compose Desktop routes a shift-modified wheel to the
                    // horizontal axis; that is the only way to express an
                    // x-axis scroll as an AWT wheel event.
                    if (horizontal) InputEvent.SHIFT_DOWN_MASK else 0,
                    source.x,
                    source.y,
                    source.xOnScreen,
                    source.yOnScreen,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    1,
                    rotation.roundToInt(),
                    rotation,
                ),
            )
        }

        private fun startFling(component: Component, source: MouseEvent, end: TouchGestureEnd.Scrolled) {
            val fling = DesktopTouchFling(end.velocityX, end.velocityY)
            if (fling.isFinished) {
                // Drag scrolled but lift-off carried no coasting velocity: the
                // gesture is already over, so restore smooth scrolling now —
                // no timer will run to do it later.
                smoothScroll.end()
                return
            }
            var lastFrameMillis = System.currentTimeMillis()
            val timer = Timer(FLING_FRAME_MILLIS, null)
            timer.addActionListener {
                val now = System.currentTimeMillis()
                val delta = fling.advance(now - lastFrameMillis)
                lastFrameMillis = now
                if (delta == null || !component.isShowing) {
                    timer.stop()
                    if (flingTimer === timer) flingTimer = null
                    smoothScroll.end()
                } else {
                    dispatchScroll(component, source, delta)
                }
            }
            flingTimer = timer
            timer.start()
        }

        private fun cancelFling() {
            flingTimer?.stop()
            flingTimer = null
            // Unconditional and idempotent: covers a fling interrupted by a
            // new press, a non-touch press, or the window-focus-loss guard in
            // dispatchEvent above — every path that can end a gesture without
            // going through the normal release/fling-finish flow.
            smoothScroll.end()
        }

        private fun MouseEvent.toSample() = TouchSample(x = x, y = y, timeMillis = `when`)
    }
}
