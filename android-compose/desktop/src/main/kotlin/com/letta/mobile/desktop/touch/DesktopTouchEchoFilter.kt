package com.letta.mobile.desktop.touch

import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.math.abs

/**
 * Drops the JDK's own copy of a finger in windows whose fingers already reach Compose as touch.
 *
 * Windows still hands AWT every finger, and AWT turns it into mouse events and touch-pan wheel
 * steps. Only presses and releases carry AWT's touch flag; the moves, drags, enters and exits
 * between them do not, so those are recognised the way the JDK itself matches a touch to its
 * mouse copy: at the finger's place, just after it was there. [ComposeTouchInjector] delivers the same finger as
 * a real touch pointer, so letting AWT's copy through as well is a second pointer: the board
 * pans on the wheel steps and jumps under the finger. Only those echoes are dropped. A real
 * mouse, a real wheel and the pen are not touch-caused and pass untouched.
 *
 * Telling the echo apart needs `sun.awt.AWTAccessor`'s touch flag, opened by
 * `--add-opens java.desktop/sun.awt=ALL-UNNAMED`. Without it only the pan wheel is dropped,
 * logged once.
 */
internal object DesktopTouchEchoFilter {
    private val installed = AtomicBoolean(false)

    // One event queue per process feeds every window, so the set of windows it guards is too.
    @Suppress("NoProcessGlobalMutableState")
    private val windows: MutableSet<Window> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** Where and when fingers were last seen, newest last; the event thread only. */
    @Suppress("NoProcessGlobalMutableState") // Read by the one event queue, as the window set is.
    private val recentFingers = ArrayDeque<FingerMark>()

    /** A finger at [screen] (AWT points) at [atMillis], so its unflagged mouse copies can be told. */
    fun noteFinger(screen: Point, atMillis: Long) {
        recentFingers.addLast(FingerMark(screen, atMillis))
        while (recentFingers.size > RECENT_FINGERS) recentFingers.removeFirst()
    }

    /** True when [screen] is where a finger was within [ECHO_WINDOW_MILLIS] before [atMillis]. */
    internal fun nearRecentFinger(screen: Point, atMillis: Long): Boolean = recentFingers.any { mark ->
        atMillis - mark.atMillis in 0..ECHO_WINDOW_MILLIS &&
            abs(mark.screen.x - screen.x) <= COORDS_DELTA && abs(mark.screen.y - screen.y) <= COORDS_DELTA
    }

    /** Forgets every finger, for tests. */
    internal fun forgetFingers() = recentFingers.clear()

    private class FingerMark(val screen: Point, val atMillis: Long)

    /** Drops AWT's copies of fingers in [window] from now on. */
    fun guard(window: Window) {
        windows += window
        if (!installed.compareAndSet(false, true)) return
        val touchCaused = touchCausedTest()
        if (touchCaused == null) println("TOUCH: AWT touch flag unavailable; only touch-pan wheel echoes are dropped")
        runCatching { Toolkit.getDefaultToolkit().systemEventQueue.push(Queue(touchCaused)) }
            .onFailure {
                installed.set(false)
                println("TOUCH: could not install the touch echo filter: $it")
            }
    }

    /**
     * True for the JDK's copy of a finger: a touch-pan wheel step, a touch-flagged press or
     * release, or an unflagged move, drag, enter or exit where a finger just was ([nearFinger]).
     * Each one let through switches Compose's text selection to mouse mode, which hid the touch
     * bar and its handles until the next finger sample showed them again: a flicker.
     */
    internal fun isEcho(
        event: AWTEvent,
        touchCaused: ((MouseEvent) -> Boolean)?,
        nearFinger: (MouseEvent) -> Boolean = { false },
    ): Boolean = when {
        event is MouseWheelEvent -> event.scrollType in TOUCH_PAN_SCROLL_TYPES || touchCaused?.invoke(event) == true
        event is MouseEvent -> touchCaused?.invoke(event) == true || (event.id in UNFLAGGED_IDS && nearFinger(event))
        else -> false
    }

    /** Mouse events AWT makes from a finger without setting its touch flag. */
    private val UNFLAGGED_IDS = setOf(
        MouseEvent.MOUSE_MOVED,
        MouseEvent.MOUSE_DRAGGED,
        MouseEvent.MOUSE_ENTERED,
        MouseEvent.MOUSE_EXITED,
    )

    /** The JDK's own touch-to-mouse match radius (TOUCH_MOUSE_COORDS_DELTA), in AWT points. */
    private const val COORDS_DELTA = 10

    /** Long enough for Windows' delayed promotion of a lifted finger to the mouse. */
    private const val ECHO_WINDOW_MILLIS = 500L
    private const val RECENT_FINGERS = 8

    private class Queue(private val touchCaused: ((MouseEvent) -> Boolean)?) : EventQueue() {
        override fun dispatchEvent(event: AWTEvent) {
            if (!isGuardedEcho(event)) super.dispatchEvent(event)
        }

        private fun isGuardedEcho(event: AWTEvent): Boolean {
            if (event !is MouseEvent || !guarded(event)) return false
            return isEcho(event, touchCaused) { mouse ->
                val screen = runCatching { mouse.locationOnScreen }.getOrNull()
                screen != null && nearRecentFinger(screen, System.currentTimeMillis())
            }
        }

        private fun guarded(event: MouseEvent): Boolean {
            val component = event.source as? Component ?: return false
            val window = component as? Window ?: SwingUtilities.getWindowAncestor(component) ?: return false
            return window in windows
        }
    }

    /**
     * `AWTAccessor.getMouseEventAccessor().isCausedByTouchEvent(event)`, or null when this JDK
     * or launcher does not let it be reached. A throw on one event answers "not touch".
     */
    private fun touchCausedTest(): ((MouseEvent) -> Boolean)? = runCatching {
        // AWTAccessor fills in its MouseEvent accessor from MouseEvent's static initialiser.
        Class.forName(MouseEvent::class.java.name, true, MouseEvent::class.java.classLoader)
        val accessor = requireNotNull(
            Class.forName("sun.awt.AWTAccessor").getMethod("getMouseEventAccessor").invoke(null),
        ) { "AWTAccessor.getMouseEventAccessor() returned null" }
        val isCausedByTouch: Method = Class.forName("sun.awt.AWTAccessor\$MouseEventAccessor")
            .getMethod("isCausedByTouchEvent", MouseEvent::class.java)
            .apply { isAccessible = true }
        val test: (MouseEvent) -> Boolean = { event ->
            runCatching { isCausedByTouch.invoke(accessor, event) as? Boolean }.getOrNull() == true
        }
        test
    }.getOrNull()

    /** `sun.awt.event.TouchEvent`'s BEGIN/UPDATE/END, which AWT carries as the wheel scroll type. */
    internal val TOUCH_PAN_SCROLL_TYPES = setOf(2, 3, 4)
}
