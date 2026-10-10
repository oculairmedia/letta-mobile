package com.letta.mobile.data.context.limit

import com.letta.mobile.data.context.ContextMeter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * letta-mobile-joigh: the stops of the context-limit slider for one model.
 *
 * A ladder of familiar windows from [MIN_TOKENS] up to the model's catalog window ([modelMax]),
 * always including that maximum and the limit in force now ([current]) so the thumb starts on the
 * real value even when it is off the ladder. letta-code refuses less than 30,000 tokens without
 * `--override`, so the floor is the first ladder stop above it.
 *
 * With no catalog window the ladder runs to [UNKNOWN_MAX_CEILING] (letta-code then has no default
 * to check against either) and [maxKnown] is false, so the sheet says the model's maximum is not
 * listed. A model whose window is the floor has nothing to choose between: [of] answers null.
 */
data class ContextLimitStops(val values: List<Int>, val modelMax: Int?) {
    val count: Int get() = values.size
    val maxKnown: Boolean get() = modelMax != null

    /** The stop nearest [tokens] (an exact stop when it is one); the highest stop when unknown. */
    fun indexOf(tokens: Int?): Int {
        val wanted = tokens ?: return values.lastIndex
        return values.indices.minBy { abs(values[it].toLong() - wanted.toLong()) }
    }

    fun valueAt(index: Int): Int = values[index.coerceIn(0, values.lastIndex)]

    companion object {
        /** The slider's floor: letta-code's `MIN_CONTEXT_WINDOW_TOKENS` is 30,000. */
        const val MIN_TOKENS: Int = 32_000

        /** The ceiling when the catalog does not list the model's window. */
        const val UNKNOWN_MAX_CEILING: Int = 1_000_000

        val LADDER: List<Int> = listOf(32_000, 64_000, 128_000, 200_000, 256_000, 400_000, 1_000_000, 2_000_000)

        fun of(modelMax: Int?, current: Int?): ContextLimitStops? {
            val max = modelMax?.takeIf { it > 0 }
            // A window at or under the floor leaves nothing letta-code would accept to choose from.
            if (max != null && max <= MIN_TOKENS) return null
            val ceiling = max ?: maxOf(UNKNOWN_MAX_CEILING, current ?: 0)
            val ladder = LADDER.filter { it <= ceiling } + ceiling
            val values = (ladder + listOfNotNull(current?.takeIf { it > 0 })).distinct().sorted()
            if (values.size < 2) return null
            return ContextLimitStops(values, max)
        }
    }
}

/** What the sheet warns about for one candidate limit. */
enum class ContextLimitWarning {
    None,

    /** The conversation already holds more than the limit: the next turn compacts first. */
    BelowUsage,

    /** Usage is past the limit's auto-compact threshold: the next turn compacts. */
    CompactsNextTurn,

    /** Larger than the model's catalog window: letta-code refuses it, or the provider overflows. */
    ExceedsModel,
}

/**
 * letta-mobile-joigh: one candidate limit judged against the conversation: where letta-code would
 * auto-compact under it, and what to warn about.
 */
data class ContextLimitAdvice(val tokens: Int, val autoCompactAtTokens: Int?, val warning: ContextLimitWarning) {
    companion object {
        fun of(tokens: Int, usedTokens: Int?, modelMax: Int?): ContextLimitAdvice {
            // The float share times the window, rounded back: 111,616 for 128k, not 111,615.
            val threshold = ContextMeter.autoCompactAt(tokens)?.let { (it * tokens).roundToInt() }
            val warning = when {
                modelMax != null && tokens > modelMax -> ContextLimitWarning.ExceedsModel
                usedTokens != null && usedTokens >= tokens -> ContextLimitWarning.BelowUsage
                usedTokens != null && threshold != null && usedTokens >= threshold -> ContextLimitWarning.CompactsNextTurn
                else -> ContextLimitWarning.None
            }
            return ContextLimitAdvice(tokens, threshold, warning)
        }
    }
}
