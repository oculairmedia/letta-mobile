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
 * they bypass the `PointerType.Mouse` drag ban entirely.
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

        runCatching {
            Toolkit.getDefaultToolkit().systemEventQueue.push(TouchTranslatingEventQueue(accessor))
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

    private fun isManaged(component: Component?): Boolean {
        val target = component ?: return false
        val window = target as? Window ?: SwingUtilities.getWindowAncestor(target)
        return window != null && window in windows
    }

    /**
     * Intercepts touch-caused mouse events before Compose's own
     * `MouseListener`/`MouseMotionListener` ever see them.
     */
    private class TouchTranslatingEventQueue(
        private val accessor: DesktopTouchEventAccessor,
    ) : EventQueue() {

        private val gesture = DesktopTouchDragGesture()

        /** Held back until the gesture is known to be a tap rather than a scroll. */
        private var withheldPress: MouseEvent? = null

        /**
         * AWT synthesizes MOUSE_CLICKED after a press/release pair; after a
         * scroll the press was never delivered, so the click must not be either.
         */
        private var suppressNextClick = false

        private var flingTimer: Timer? = null

        override fun dispatchEvent(event: AWTEvent) {
            if (event !is MouseEvent) {
                super.dispatchEvent(event)
                return
            }
            val component = event.source as? Component
            if (!isManaged(component)) {
                super.dispatchEvent(event)
                return
            }
            val isTouch = accessor.isCausedByTouchEvent(event)
            recordOrigin(event, isTouch)
            if (!isTouch) {
                if (event.id == MouseEvent.MOUSE_PRESSED) cancelFling()
                super.dispatchEvent(event)
                return
            }
            when (event.id) {
                MouseEvent.MOUSE_PRESSED -> onTouchPress(event)
                MouseEvent.MOUSE_DRAGGED -> onTouchDrag(event, requireNotNull(component))
                MouseEvent.MOUSE_RELEASED -> onTouchRelease(event, requireNotNull(component))
                MouseEvent.MOUSE_CLICKED ->
                    if (suppressNextClick) suppressNextClick = false else super.dispatchEvent(event)
                // Enter/exit/move carry no gesture state; letting them through
                // keeps hover affordances working under a stylus or hover-capable
                // digitizer.
                else -> super.dispatchEvent(event)
            }
        }

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
            if (fling.isFinished) return
            var lastFrameMillis = System.currentTimeMillis()
            val timer = Timer(FLING_FRAME_MILLIS, null)
            timer.addActionListener {
                val now = System.currentTimeMillis()
                val delta = fling.advance(now - lastFrameMillis)
                lastFrameMillis = now
                if (delta == null || !component.isShowing) {
                    timer.stop()
                    if (flingTimer === timer) flingTimer = null
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
        }

        private fun MouseEvent.toSample() = TouchSample(x = x, y = y, timeMillis = `when`)
    }
}
