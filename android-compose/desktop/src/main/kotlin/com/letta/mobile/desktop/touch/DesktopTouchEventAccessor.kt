package com.letta.mobile.desktop.touch

import java.awt.event.MouseEvent
import java.lang.reflect.Method

/**
 * Answers "did this AWT mouse event come from a finger?".
 *
 * AWT has no public touch event on Windows. `WM_TOUCH` is translated into an
 * ordinary [MouseEvent] and the only surviving evidence is a private boolean
 * that `sun.awt.AWTAccessor.getMouseEventAccessor().isCausedByTouchEvent(...)`
 * exposes. Reaching it needs `--add-opens java.desktop/sun.awt=ALL-UNNAMED`,
 * which the desktop module wires into both `:desktop:run` and the packaged
 * launcher.
 *
 * Every failure mode degrades to [Unavailable] rather than throwing: without
 * the accessor the app behaves exactly as it did before this shim existed.
 */
internal fun interface DesktopTouchEventAccessor {

    fun isCausedByTouchEvent(event: MouseEvent): Boolean

    companion object {
        /** Answers false for everything — the pre-shim behaviour. */
        val Unavailable = DesktopTouchEventAccessor { false }

        /**
         * Reflectively binds `AWTAccessor.MouseEventAccessor`, or returns null
         * when the hook is missing (no `--add-opens`, non-Windows JDK build, a
         * future JDK that drops it). Callers log once and fall back to
         * [Unavailable].
         */
        fun bindOrNull(): DesktopTouchEventAccessor? = runCatching {
            // AWTAccessor populates its MouseEvent accessor from MouseEvent's
            // static initializer, so force that class to initialize first.
            Class.forName(MouseEvent::class.java.name, true, MouseEvent::class.java.classLoader)

            val getMouseEventAccessor = Class.forName("sun.awt.AWTAccessor")
                .getMethod("getMouseEventAccessor")
                .apply { isAccessible = true }
            // Without `--add-opens java.desktop/sun.awt=ALL-UNNAMED` this is
            // where the binding fails, before any event is ever classified.
            val accessorInstance = requireNotNull(getMouseEventAccessor.invoke(null)) {
                "AWTAccessor.getMouseEventAccessor() returned null"
            }
            val isCausedByTouchEvent: Method =
                Class.forName("sun.awt.AWTAccessor\$MouseEventAccessor")
                    .getMethod("isCausedByTouchEvent", MouseEvent::class.java)
                    .apply { isAccessible = true }

            DesktopTouchEventAccessor { event ->
                // A per-event throw must never escape into AWT's dispatch loop;
                // "not touch" is always the safe answer.
                runCatching { isCausedByTouchEvent.invoke(accessorInstance, event) as? Boolean }
                    .getOrNull() ?: false
            }
        }.getOrNull()
    }
}
