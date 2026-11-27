package com.letta.mobile.util.time

import kotlin.math.roundToInt

/**
 * The one compact count label ("980", "12.3k", "1.2M") every surface shows for token usage and
 * other tallies, a sibling of [TimeLabels] in this leaf package.
 *
 * The math lives here, not in `FormatHelpers` (which keeps the settings pages' [Int] API by
 * delegating here): this package imports nothing of the app's, so any package may use it
 * without an import cycle, including the chat packages that cannot import
 * `com.letta.mobile.util` because that package reaches them.
 */
object CountLabels {
    /** [value] in at most one compact unit, one decimal where the unit applies; a small count stays exact. */
    fun compact(value: Long): String {
        if (value < 9_950L) return value.toString()
        val inThousands = (value / 100.0).roundToInt() / 10.0
        if (inThousands >= 1000.0) {
            val inMillions = (value / 100_000.0).roundToInt() / 10.0
            return "${inMillions}M"
        }
        return "${inThousands}k"
    }
}
