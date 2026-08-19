package com.letta.mobile.desktop.touch

import com.letta.mobile.desktop.DesktopCrashReporter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Flips Compose Desktop's internal "animate wheel scroll" flag for one target
 * scroll-config instance.
 *
 * Read from Compose Foundation 1.11.1 sources
 * (`androidx.compose.foundation.gestures.DesktopScrollConfig`,
 * `MouseWheelScrollingLogic.kt`, `DesktopScrollable.desktop.kt`):
 *
 * - A synthetic wheel event is only applied immediately when
 *   `!isSmoothScrollingEnabled || isPreciseWheelScroll(event)`; otherwise the
 *   delta is animated through a `tween`, which is the visible lag a
 *   drag-to-scroll caused by [DesktopWindowsTouchInput] rides on top of.
 * - `isPreciseWheelScroll` resolves to `MouseWheelEvent.isPreciseWheelRotation`
 *   on Windows, which `WindowsWinUIConfig` hardcodes false ("On Windows, even
 *   free scrolling wheels should trigger animation") — so `isPreciseWheelScroll`
 *   can never be true through wheel events on Windows, and
 *   `isSmoothScrollingEnabled` is the only lever left.
 * - `isSmoothScrollingEnabled` lives on `DesktopScrollConfig` as
 *   `override var isSmoothScrollingEnabled = ...; internal set`. The actual
 *   runtime instance behind Windows wheel scrolling is the
 *   `WindowsWinUIConfig` singleton (resolved by `LocalScrollConfig`'s default
 *   from `DesktopPlatform.Current`). Kotlin compiles a public getter
 *   (`isSmoothScrollingEnabled()`) and a module-mangled public setter
 *   (`setSmoothScrollingEnabled$foundation(boolean)` as of this Compose
 *   build) — reachable by reflection without any `--add-opens`, since neither
 *   method is JVM-`private`. The mangle suffix is an implementation detail of
 *   the `foundation-desktop` module name, so it is matched by prefix rather
 *   than hardcoded.
 */
internal fun interface DesktopSmoothScrollSwitch {
    fun setEnabled(enabled: Boolean)

    companion object {
        /**
         * Binds against `androidx.compose.foundation.gestures.WindowsWinUIConfig`,
         * or returns null when the singleton or its setter cannot be found —
         * a Compose Foundation version that renamed or removed the field, for
         * instance. Callers must fail safe: a laggy drag-scroll is far better
         * than a crash or a permanently disabled scroll animation.
         */
        fun bindOrNull(): DesktopSmoothScrollSwitch? = runCatching {
            val configClass = Class.forName("androidx.compose.foundation.gestures.WindowsWinUIConfig")
            val instance = requireNotNull(configClass.getField("INSTANCE").get(null)) {
                "WindowsWinUIConfig.INSTANCE is null"
            }
            val setter = configClass.methods.firstOrNull { method ->
                method.name.startsWith("setSmoothScrollingEnabled") &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType
            } ?: error("no setSmoothScrollingEnabled*(boolean) method found on $configClass")

            DesktopSmoothScrollSwitch { enabled ->
                // A per-call throw must never escape into the AWT dispatch
                // loop or leave a gesture unable to restore the flag.
                runCatching { setter.invoke(instance, enabled) }
            }
        }.getOrNull()
    }
}

/**
 * Suppresses Compose's animated wheel scroll for the lifetime of one touch
 * drag/fling, and restores it when the gesture ends — so finger scrolling
 * tracks 1:1 while real mouse-wheel scrolling keeps its animation.
 *
 * [begin]/[end] are both idempotent: repeated drag events call [begin] on
 * every emitted delta, and a gesture can end from several different places
 * (a spent fling, a cancelled fling, a new press interrupting one). Whichever
 * of those fires first restores the flag; later calls are no-ops. That is the
 * "always restored, however the gesture ends" guarantee — window focus loss
 * and fling cancellation both route through [DesktopWindowsTouchInput]'s
 * `cancelFling()`, which unconditionally calls [end].
 *
 * Binding failure degrades to a permanent no-op (logged once): scrolling
 * stays laggy rather than the app crashing or smooth scrolling getting stuck
 * off for mouse users.
 */
internal class DesktopTouchSmoothScrollSuppressor(
    private val switch: DesktopSmoothScrollSwitch?,
) {
    private val bindFailureLogged = AtomicBoolean(false)
    private var suppressed = false

    init {
        if (switch == null && bindFailureLogged.compareAndSet(false, true)) {
            DesktopCrashReporter.logCrash(
                IllegalStateException(
                    "WindowsWinUIConfig.isSmoothScrollingEnabled setter is unavailable; touch " +
                        "drag-to-scroll keeps Compose's tween animation and will visibly lag the finger.",
                ),
                context = "touch smooth-scroll suppressor bind",
            )
        }
    }

    /** Begins suppressing smooth scroll for a gesture. Safe to call repeatedly. */
    fun begin() {
        if (switch == null || suppressed) return
        suppressed = true
        switch.setEnabled(false)
    }

    /** Restores smooth scroll. Safe to call even when [begin] was never reached. */
    fun end() {
        if (switch == null || !suppressed) return
        suppressed = false
        switch.setEnabled(true)
    }
}
