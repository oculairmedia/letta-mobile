package com.letta.mobile.data.chat.display

import com.letta.mobile.data.storage.SecureSettingsStore
import kotlin.math.roundToInt

/**
 * The chat's text size as a multiple of the app's own type sizes (1.0 = unmodified), and its
 * rules: the range a gesture can reach, the step per wheel notch, and how a stored value is read.
 */
object ChatFontScale {
    /** Smallest scale a gesture can reach. */
    const val MIN: Float = 0.8f

    /** Largest scale a gesture can reach. */
    const val MAX: Float = 2.0f

    /** Neutral: the app's own type sizes. */
    const val DEFAULT: Float = 1.0f

    /** Multiplicative step per wheel notch, so a notch feels the same at 0.8x as at 2.0x. */
    private const val STEP = 1.1f

    /**
     * The next scale for one Ctrl+scroll gesture. [scrollDeltaY] follows Compose's sign: positive is
     * a scroll down, which scales down. The result is clamped and snapped (see [snap]).
     */
    fun next(current: Float, scrollDeltaY: Float): Float {
        if (scrollDeltaY == 0f) return current
        return snap(if (scrollDeltaY > 0f) current / STEP else current * STEP)
    }

    /**
     * Clamps any candidate scale, a stored one included, and snaps it to two decimals so repeated
     * multiply/divide round trips cannot drift the neutral 1.0 into 0.99999.
     */
    fun snap(value: Float): Float {
        if (value.isNaN() || value.isInfinite()) return DEFAULT
        return (value.coerceIn(MIN, MAX) * 100f).roundToInt() / 100f
    }
}

/**
 * Where the chat's text size is kept. Not namespaced by backend: type size belongs to the person
 * reading the screen, not to the backend they point at.
 */
class ChatFontScaleStore(private val store: SecureSettingsStore) {
    /** The stored scale, snapped into range; [ChatFontScale.DEFAULT] when none (or nothing readable) is stored. */
    fun load(): Float = store.getString(KEY)?.toFloatOrNull()?.let(ChatFontScale::snap) ?: ChatFontScale.DEFAULT

    fun save(scale: Float) = store.putString(KEY, scale.toString())

    private companion object {
        const val KEY = "chat.fontScale"
    }
}
