package com.letta.mobile.desktop.touch

import java.util.concurrent.atomic.AtomicReference

/**
 * Remembers whether the most recent pointer interaction came from a finger.
 *
 * The Windows touch keyboard must never appear for mouse or precision-touchpad
 * users, and Compose gives no hint about input provenance: by the time a text
 * field asks for an input method, the originating AWT event is long gone and
 * was a plain `MouseEvent` anyway. [DesktopWindowsTouchInput] already has to
 * classify every pointer event to do drag-to-scroll, so it records the verdict
 * here and [DesktopTouchKeyboardSessionGate] reads it back.
 *
 * Fail-safe by construction: when the reflective touch accessor is
 * unavailable — a missing `--add-opens`, a non-Windows host, a future JDK that
 * drops the hook — nothing ever records a touch and the keyboard simply never
 * appears, which is exactly today's behaviour.
 */
internal class DesktopTouchOriginTracker(
    private val recencyWindowMillis: Long = DEFAULT_RECENCY_WINDOW_MILLIS,
) {
    private data class Origin(val isTouch: Boolean, val atMillis: Long)

    private val last = AtomicReference(Origin(isTouch = false, atMillis = Long.MIN_VALUE))

    fun record(isTouch: Boolean, atMillis: Long) {
        last.set(Origin(isTouch, atMillis))
    }

    /**
     * True when the last pointer interaction was a touch and it happened
     * recently enough to plausibly be what focused the text field. The window
     * matters because focus also moves by Tab, by a programmatic
     * `FocusRequester`, and on navigation — none of which should raise a
     * keyboard just because the user tapped something minutes ago.
     */
    fun wasTouch(nowMillis: Long): Boolean {
        val origin = last.get()
        return origin.isTouch && nowMillis - origin.atMillis in 0..recencyWindowMillis
    }

    companion object {
        /**
         * Generous enough to survive a tap that navigates before the composer
         * requests focus, short enough that a later keyboard-driven focus does
         * not inherit the tap.
         */
        const val DEFAULT_RECENCY_WINDOW_MILLIS: Long = 3_000
    }
}

/** Process-wide tracker shared by the AWT shim and the text-input interceptor. */
internal val DesktopTouchOrigin = DesktopTouchOriginTracker()
