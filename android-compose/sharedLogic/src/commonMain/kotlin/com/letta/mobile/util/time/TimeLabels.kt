package com.letta.mobile.util.time

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The one relative-age label and the one duration label every surface shows.
 *
 * [age] is how long ago something happened, in one compact unit ("now", "5m", "3h", "2d", "1w",
 * "2mo"): the sidebar, nav drawer, Conversations page, Home and search rows. [duration] is how long
 * something took or has been running, in at most two units ("340ms", "12s", "1m 12s", "2h 5m"):
 * the Runs page, schedules and the subagent roster.
 *
 * Its neighbours are deliberately different idioms: a signed countdown ("in 2h 14m") is
 * `ScheduleFormat.relative`, and a split value/unit pair for a metric tile is
 * `FormatHelpers.formatDurationValue` / `formatDurationSuffix`.
 *
 * This package imports nothing of the app's, so any package may use it without an import cycle.
 */
object TimeLabels {
    /** [at] relative to [now] in one compact unit; a time in the future reads "now". */
    fun age(at: Instant, now: Instant = Clock.System.now()): String {
        val seconds = (now - at).inWholeSeconds
        return when {
            seconds < SECONDS_PER_MINUTE -> "now"
            seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MINUTE}m"
            seconds < SECONDS_PER_DAY -> "${seconds / SECONDS_PER_HOUR}h"
            seconds < SECONDS_PER_WEEK -> "${seconds / SECONDS_PER_DAY}d"
            seconds < SECONDS_PER_MONTH -> "${seconds / SECONDS_PER_WEEK}w"
            else -> "${seconds / SECONDS_PER_MONTH}mo"
        }
    }

    /** [age] of a raw ISO-8601 label; text that is not an instant ("Queued", "Remote") comes back unchanged. */
    fun ageLabel(raw: String, now: Instant = Clock.System.now()): String =
        runCatching { Instant.parse(raw) }.getOrNull()?.let { age(it, now) } ?: raw

    /**
     * [millis] in at most two units. Below a minute [tenths] adds one decimal ("4.2s", for timings
     * where a second is coarse); the value is rounded first, so 59,950 ms reads "1m 0s", never
     * "60.0s". A negative duration reads as zero.
     */
    fun duration(millis: Long, tenths: Boolean = false): String {
        val ms = millis.coerceAtLeast(0L)
        if (ms < MILLIS_PER_SECOND) return "${ms}ms"
        if (tenths) {
            val tenthsOfSecond = (ms + HALF_TENTH_MILLIS) / TENTH_MILLIS
            if (tenthsOfSecond < SECONDS_PER_MINUTE * TENTHS_PER_SECOND) {
                return "${tenthsOfSecond / TENTHS_PER_SECOND}.${tenthsOfSecond % TENTHS_PER_SECOND}s"
            }
        }
        val seconds = if (tenths) (ms + MILLIS_PER_SECOND / 2) / MILLIS_PER_SECOND else ms / MILLIS_PER_SECOND
        val hours = seconds / SECONDS_PER_HOUR
        val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds % SECONDS_PER_MINUTE}s"
            else -> "${seconds}s"
        }
    }

    private const val MILLIS_PER_SECOND = 1_000L
    private const val TENTH_MILLIS = 100L
    private const val HALF_TENTH_MILLIS = 50L
    private const val TENTHS_PER_SECOND = 10L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val SECONDS_PER_DAY = 86_400L
    private const val SECONDS_PER_WEEK = 604_800L
    private const val SECONDS_PER_MONTH = 2_592_000L
}
