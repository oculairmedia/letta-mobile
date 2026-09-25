package com.letta.mobile.debug

import android.view.MotionEvent
import ca.oculair.meridian.BuildConfig
import com.letta.mobile.util.Telemetry

/**
 * letta-mobile-erx7m: debug-only record of every touch stream boundary the activity window
 * receives, so a stall can be split into "the window never saw it", "Compose dropped it" and
 * "a node swallowed it".
 *
 * Every entry point checks `BuildConfig.DEBUG`; nothing here runs in a release build. Only
 * DOWN / UP / CANCEL and their pointer variants are logged, never MOVE.
 *
 * `source` and `toolType` are logged because Compose's AndroidComposeView resets its whole
 * pointer state (processCancel) when either differs from the previous event's — a candidate
 * explanation for why an adb-injected tap un-sticks the app while finger taps do not.
 */
internal object TouchDispatchDiagnostics {
    const val TAG = "Input"

    /** Switches the feature-module gesture probes on; a no-op in release builds. */
    fun enableForDebugBuild() {
        if (BuildConfig.DEBUG) Telemetry.inputDiagEnabled.set(true)
    }

    /** Called with every event the activity window dispatched; a no-op in release builds. */
    fun onDispatched(event: MotionEvent, handled: Boolean) {
        if (BuildConfig.DEBUG) record(event, handled)
    }

    private fun record(event: MotionEvent, handled: Boolean) {
        val action = event.actionMasked
        if (!isLoggedTouchAction(action)) return
        Telemetry.event(
            TAG,
            "touch.dispatch",
            "action" to touchActionName(action),
            "handled" to handled,
            "source" to "0x" + event.source.toString(HEX),
            "deviceId" to event.deviceId,
            "toolType" to event.getToolType(0),
            "flags" to touchFlagNames(event.flags),
            "pointerCount" to event.pointerCount,
            "x" to event.x,
            "y" to event.y,
            "eventTimeMs" to event.eventTime,
        )
    }
}

private const val HEX = 16

// MotionEvent action / flag values, spelled out so this stays pure (unit-testable without the
// Android runtime) and so FLAG_CANCELED (API 33) needs no version check.
private const val ACTION_DOWN = 0
private const val ACTION_UP = 1
private const val ACTION_CANCEL = 3
private const val ACTION_POINTER_DOWN = 5
private const val ACTION_POINTER_UP = 6
private const val FLAG_WINDOW_IS_OBSCURED = 0x1
private const val FLAG_WINDOW_IS_PARTIALLY_OBSCURED = 0x2
private const val FLAG_CANCELED = 0x20

private val LOGGED_ACTIONS = mapOf(
    ACTION_DOWN to "DOWN",
    ACTION_UP to "UP",
    ACTION_CANCEL to "CANCEL",
    ACTION_POINTER_DOWN to "POINTER_DOWN",
    ACTION_POINTER_UP to "POINTER_UP",
)

private val NAMED_FLAGS = listOf(
    FLAG_WINDOW_IS_OBSCURED to "WINDOW_IS_OBSCURED",
    FLAG_WINDOW_IS_PARTIALLY_OBSCURED to "WINDOW_IS_PARTIALLY_OBSCURED",
    FLAG_CANCELED to "CANCELED",
)

internal fun isLoggedTouchAction(actionMasked: Int): Boolean = actionMasked in LOGGED_ACTIONS

internal fun touchActionName(actionMasked: Int): String = LOGGED_ACTIONS[actionMasked] ?: "ACTION_$actionMasked"

/** Raw hex plus the names of the flags that matter for a stall, e.g. `0x21[WINDOW_IS_OBSCURED,CANCELED]`. */
internal fun touchFlagNames(flags: Int): String {
    val names = NAMED_FLAGS.filter { (bit, _) -> flags and bit != 0 }.map { it.second }
    return "0x" + flags.toString(HEX) + names.joinToString(separator = ",", prefix = "[", postfix = "]")
}
