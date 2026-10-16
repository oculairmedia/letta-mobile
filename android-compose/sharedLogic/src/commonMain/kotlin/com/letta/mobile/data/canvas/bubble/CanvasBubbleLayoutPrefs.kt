package com.letta.mobile.data.canvas.bubble

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * letta-mobile-zxk2n.29: how big the minimised chat's mascot stands over the canvas and how wide and
 * tall its prompt bar runs. The person sets them (drag the bar's side or top edges, Ctrl + wheel over
 * the mascot, or the "Bubble layout" sliders); the host persists them. Each platform has its own defaults: the desktop's
 * window is wide, so a small mascot over a centred bar; the phone keeps the sizes it always had.
 */

/**
 * The minimised chat's layout. [composerWidthDp] null means "as wide as the open panel" (the phone's
 * and the original behaviour); at or past [COMPOSER_FULL_DP] the bar runs the canvas's whole width.
 * [replyHoldSeconds] is how long the reply's speech bubble stays once its text settles: null holds
 * it for a time that grows with its length ([replyHoldMillis]); at or past [REPLY_HOLD_ALWAYS_S]
 * it stays until dismissed (the phone's and the original behaviour). [composerHeightDp] is the bar's
 * prompt field height: null (or [COMPOSER_HEIGHT_MIN_DP]) keeps the one-line bar; taller, the field
 * takes several lines.
 */
@Immutable
data class CanvasBubbleLayout(
    val mascotSizeDp: Float,
    val composerWidthDp: Float?,
    val replyHoldSeconds: Float? = null,
    val composerHeightDp: Float? = null,
) {
    /** The reply's bubble stays until it is dismissed. */
    val replyHoldsForever: Boolean get() = replyHoldSeconds != null && replyHoldSeconds >= REPLY_HOLD_ALWAYS_S

    /**
     * How long a settled reply of [textLength] characters stays, in ms; null: until dismissed.
     * Automatic, a short note goes in [REPLY_HOLD_BASE_MS] and a long one stays up to [REPLY_HOLD_AUTO_MAX_MS].
     */
    fun replyHoldMillis(textLength: Int): Long? {
        val fixed = replyHoldSeconds ?: return (REPLY_HOLD_BASE_MS + REPLY_HOLD_PER_CHAR_MS * textLength.coerceAtLeast(0))
            .coerceAtMost(REPLY_HOLD_AUTO_MAX_MS)
        if (replyHoldsForever) return null
        return (fixed * MILLIS_PER_SECOND).toLong()
    }

    /** The bar runs as wide as the canvas allows. */
    val composerFull: Boolean get() = composerWidthDp != null && composerWidthDp >= COMPOSER_FULL_DP

    /** The bar's field is taller than one line. */
    val composerMultiLine: Boolean get() = composerHeightDp != null && composerHeightDp > COMPOSER_HEIGHT_MIN_DP

    /** Every value inside its range; a value that is not a number falls back to [defaults]'. */
    fun sanitize(defaults: CanvasBubbleLayout): CanvasBubbleLayout = CanvasBubbleLayout(
        mascotSizeDp = clampMascot(mascotSizeDp, defaults.mascotSizeDp),
        composerWidthDp = composerWidthDp?.let { clampComposer(it, defaults.composerWidthDp) },
        replyHoldSeconds = replyHoldSeconds?.let { clampReplyHold(it, defaults.replyHoldSeconds) },
        composerHeightDp = composerHeightDp?.let { clampComposerHeight(it, defaults.composerHeightDp) },
    )

    companion object {
        const val MASCOT_MIN_DP: Float = 72f
        const val MASCOT_MAX_DP: Float = 400f

        /** One Ctrl + wheel notch (or one accessibility step) of mascot size. */
        const val MASCOT_STEP_DP: Float = 16f

        /** The narrowest bar that still fits its buttons and a few characters of prompt. */
        const val COMPOSER_MIN_DP: Float = 260f

        /** The bar's prompt field at one line (the bar's natural height)... */
        const val COMPOSER_HEIGHT_MIN_DP: Float = 40f

        /** ...up to this tall, dragged by its top edge (the canvas bounds it further). */
        const val COMPOSER_HEIGHT_MAX_DP: Float = 480f

        /** The top of the width range: the bar takes the canvas's whole width. */
        const val COMPOSER_FULL_DP: Float = 1400f

        const val REPLY_HOLD_MIN_S: Float = 3f
        const val REPLY_HOLD_MAX_S: Float = 30f

        /** Past the last second of the range: the reply stays until dismissed. */
        const val REPLY_HOLD_ALWAYS_S: Float = 31f

        const val REPLY_HOLD_BASE_MS: Long = 4_000L
        const val REPLY_HOLD_PER_CHAR_MS: Long = 40L
        const val REPLY_HOLD_AUTO_MAX_MS: Long = 15_000L
        private const val MILLIS_PER_SECOND = 1000f

        /** The desktop: a small mascot over a centred, readable bar; replies go on their own. */
        val Desktop: CanvasBubbleLayout = CanvasBubbleLayout(mascotSizeDp = 96f, composerWidthDp = 640f, replyHoldSeconds = null)

        /**
         * The phone (and tablets): today's sizes, the mascot at the composer companion's size, the bar
         * the panel's width, and the reply staying until dismissed.
         */
        val Touch: CanvasBubbleLayout = CanvasBubbleLayout(
            mascotSizeDp = 120f,
            composerWidthDp = null,
            replyHoldSeconds = REPLY_HOLD_ALWAYS_S,
        )

        fun clampMascot(dp: Float, fallback: Float): Float =
            (if (dp.isFinite()) dp else fallback).coerceIn(MASCOT_MIN_DP, MASCOT_MAX_DP)

        fun clampComposer(dp: Float, fallback: Float?): Float? {
            val value = if (dp.isFinite()) dp else fallback ?: return null
            return value.coerceIn(COMPOSER_MIN_DP, COMPOSER_FULL_DP)
        }

        fun clampComposerHeight(dp: Float, fallback: Float?): Float? {
            val value = if (dp.isFinite()) dp else fallback ?: return null
            return value.coerceIn(COMPOSER_HEIGHT_MIN_DP, COMPOSER_HEIGHT_MAX_DP)
        }

        fun clampReplyHold(seconds: Float, fallback: Float?): Float? {
            val value = if (seconds.isFinite()) seconds else fallback ?: return null
            return value.coerceIn(REPLY_HOLD_MIN_S, REPLY_HOLD_ALWAYS_S)
        }
    }
}

/** How a [CanvasBubbleLayout] is written to a flat key-value store (a properties file, a preference). */
object CanvasBubbleLayoutCodec {
    const val KEY_MASCOT_SIZE: String = "canvas.bubble.mascotSizeDp"
    const val KEY_COMPOSER_WIDTH: String = "canvas.bubble.composerWidthDp"
    const val KEY_REPLY_HOLD: String = "canvas.bubble.replyHoldSeconds"
    const val KEY_COMPOSER_HEIGHT: String = "canvas.bubble.composerHeightDp"

    /** Written for an automatic hold, so a stored "automatic" is not read back as the platform's default. */
    private const val AUTO = "auto"

    fun encode(layout: CanvasBubbleLayout): Map<String, String> = buildMap {
        put(KEY_MASCOT_SIZE, layout.mascotSizeDp.toString())
        layout.composerWidthDp?.let { put(KEY_COMPOSER_WIDTH, it.toString()) }
        put(KEY_REPLY_HOLD, layout.replyHoldSeconds?.toString() ?: AUTO)
        layout.composerHeightDp?.let { put(KEY_COMPOSER_HEIGHT, it.toString()) }
    }

    /** What [values] hold, missing or damaged entries read as [defaults]', all of it in range. */
    fun decode(values: Map<String, String>, defaults: CanvasBubbleLayout): CanvasBubbleLayout {
        val mascot = values[KEY_MASCOT_SIZE]?.toFloatOrNull() ?: defaults.mascotSizeDp
        val composer = values[KEY_COMPOSER_WIDTH]?.toFloatOrNull() ?: defaults.composerWidthDp
        val hold = when (val stored = values[KEY_REPLY_HOLD]) {
            null -> defaults.replyHoldSeconds
            AUTO -> null
            else -> stored.toFloatOrNull() ?: defaults.replyHoldSeconds
        }
        val height = values[KEY_COMPOSER_HEIGHT]?.toFloatOrNull() ?: defaults.composerHeightDp
        return CanvasBubbleLayout(mascot, composer, hold, height).sanitize(defaults)
    }
}

/**
 * The live layout and its setters: every change is clamped into range and seen at once by
 * [layout]'s collectors; the host persists what it collects. [initial] is what the host loaded.
 */
@Stable
class CanvasBubbleLayoutPrefs(
    val defaults: CanvasBubbleLayout,
    initial: CanvasBubbleLayout? = null,
) {
    private val state = MutableStateFlow((initial ?: defaults).sanitize(defaults))

    val layout: StateFlow<CanvasBubbleLayout> = state.asStateFlow()

    fun setMascotSize(dp: Float) {
        state.update { it.copy(mascotSizeDp = CanvasBubbleLayout.clampMascot(dp, it.mascotSizeDp)) }
    }

    /** Grows (positive) or shrinks the mascot by [steps] of [CanvasBubbleLayout.MASCOT_STEP_DP]. */
    fun stepMascot(steps: Int) {
        setMascotSize(state.value.mascotSizeDp + steps * CanvasBubbleLayout.MASCOT_STEP_DP)
    }

    fun setComposerWidth(dp: Float) {
        state.update { it.copy(composerWidthDp = CanvasBubbleLayout.clampComposer(dp, it.composerWidthDp)) }
    }

    /** The bar's prompt field height; at [CanvasBubbleLayout.COMPOSER_HEIGHT_MIN_DP] it is one line again. */
    fun setComposerHeight(dp: Float) {
        state.update { it.copy(composerHeightDp = CanvasBubbleLayout.clampComposerHeight(dp, it.composerHeightDp)) }
    }

    /** A fixed hold in seconds ([CanvasBubbleLayout.REPLY_HOLD_ALWAYS_S] and past: until dismissed). */
    fun setReplyHold(seconds: Float) {
        state.update { it.copy(replyHoldSeconds = CanvasBubbleLayout.clampReplyHold(seconds, it.replyHoldSeconds)) }
    }

    fun reset() {
        state.value = defaults
    }
}
