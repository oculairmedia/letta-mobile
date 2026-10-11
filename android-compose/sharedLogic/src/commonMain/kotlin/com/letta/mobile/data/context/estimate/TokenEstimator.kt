package com.letta.mobile.data.context.estimate

/**
 * letta-mobile-cyh28: how a span of prompt text is counted in tokens when no provider figure
 * exists for it.
 *
 * letta-code has no tokenizer: every local estimate it makes (`estimateLocalMessageTokens`,
 * `estimateSerializedTokens`, the compaction stats) is `ceil(chars / 4)`. [CharsPerToken] matches
 * that so a section here agrees with the server's own accounting. A real tokenizer can replace it
 * behind this interface without touching the estimator; the calibration to the provider total
 * keeps the rows summing to the exact figure either way.
 */
fun interface TokenEstimator {
    fun tokensForChars(chars: Long): Int
}

fun TokenEstimator.tokens(text: String): Int = tokensForChars(text.length.toLong())

/** letta-code's `Math.ceil(chars / 4)`. */
object CharsPerToken : TokenEstimator {
    private const val CHARS_PER_TOKEN = 4L

    override fun tokensForChars(chars: Long): Int {
        if (chars <= 0L) return 0
        return ((chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
