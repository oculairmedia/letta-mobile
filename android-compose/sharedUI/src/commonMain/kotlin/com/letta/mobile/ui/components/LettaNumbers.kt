package com.letta.mobile.ui.components

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Exact counts for UI copy: grouped thousands ("1,000,000"), a used/limit pair with its unit, a
 * meter fraction and a short percent. Compact token counts ("1M") stay with formatContextTokens.
 */

/** 1000000 -> "1,000,000"; negatives keep their sign. */
internal fun lettaCount(value: Int): String {
    val digits = abs(value.toLong()).toString()
    val grouped = digits.reversed().chunked(DIGIT_GROUP).joinToString(",").reversed()
    return if (value < 0) "-$grouped" else grouped
}

/** "801 / 1,000,000 tokens", or "801 tokens" without a limit. */
internal fun lettaUsage(used: Int, limit: Int?, unit: String): String =
    if (limit != null) "${lettaCount(used)} / ${lettaCount(limit)} $unit" else "${lettaCount(used)} $unit"

/** Fraction of [limit] in 0..1, or null when there is no positive limit. */
internal fun lettaFraction(used: Int, limit: Int?): Float? =
    limit?.takeIf { it > 0 }?.let { (used.toFloat() / it).coerceIn(0f, 1f) }

/** "0.1%", "4%", "42%"; "<0.1%" for a sliver. */
internal fun lettaPercent(used: Int, limit: Int): String {
    if (limit <= 0) return "—"
    val pct = used * PERCENT / limit.toDouble()
    return when {
        used > 0 && pct < MIN_SHOWN -> "<0.1%"
        pct < TENTHS_BELOW -> tenths(pct)
        else -> "${pct.roundToInt()}%"
    }
}

private fun tenths(pct: Double): String {
    val t = (pct * TEN).roundToInt()
    return if (t % TEN_INT == 0) "${t / TEN_INT}%" else "${t / TEN_INT}.${t % TEN_INT}%"
}

private const val DIGIT_GROUP = 3
private const val PERCENT = 100.0
private const val MIN_SHOWN = 0.1
private const val TENTHS_BELOW = 10.0
private const val TEN = 10.0
private const val TEN_INT = 10
