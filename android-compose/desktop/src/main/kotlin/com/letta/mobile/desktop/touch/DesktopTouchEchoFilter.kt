package com.letta.mobile.desktop.touch

import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Drops the JDK's own copy of a finger in windows whose fingers already reach Compose as touch.
 *
 * Windows still hands AWT every finger, and AWT turns it into mouse presses and drags (flagged
 * as touch-caused) and touch-pan wheel steps. [ComposeTouchInjector] delivers the same finger as
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

    /** True for the JDK's copy of a finger: a touch-pan wheel step or a touch-caused mouse event. */
    internal fun isEcho(event: AWTEvent, touchCaused: ((MouseEvent) -> Boolean)?): Boolean = when {
        event is MouseWheelEvent -> event.scrollType in TOUCH_PAN_SCROLL_TYPES || touchCaused?.invoke(event) == true
        event is MouseEvent -> touchCaused?.invoke(event) == true
        else -> false
    }

    private class Queue(private val touchCaused: ((MouseEvent) -> Boolean)?) : EventQueue() {
        override fun dispatchEvent(event: AWTEvent) {
            if (event is MouseEvent && guarded(event) && isEcho(event, touchCaused)) return
            super.dispatchEvent(event)
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
