package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.runtime.Composable
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_duration_minutes
import com.letta.mobile.sharedui.resources.rows_duration_ms
import com.letta.mobile.sharedui.resources.rows_duration_seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

// letta-mobile-bglj6.1: desktop's messageClockLabel (java.time) on kotlinx-datetime, so the
// shared rows can show it on Android and desktop alike.

/** "9:41 AM" in the system zone, or null for a blank or unparseable timestamp. */
internal fun messageClockLabel(iso: String, zone: TimeZone = TimeZone.currentSystemDefault()): String? {
    val local = parseLocalDateTime(iso, zone) ?: return null
    return local.format(ClockFormat)
}

/**
 * Accepts the shapes the backends emit: an instant (`…Z`), an offset date-time, and a bare
 * local date-time. A malformed timestamp costs one label, never the row.
 */
internal fun parseLocalDateTime(iso: String, zone: TimeZone): LocalDateTime? {
    if (iso.isBlank()) return null
    return runCatching { Instant.parse(iso).toLocalDateTime(zone) }
        .recoverCatching { LocalDateTime.parse(iso) }
        .getOrNull()
}

private val ClockFormat = LocalDateTime.Format {
    amPmHour(Padding.NONE)
    char(':')
    minute()
    char(' ')
    amPmMarker("AM", "PM")
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

/** "850 ms", "12s", "3m 5s". */
@Composable
internal fun formatDuration(durationMs: Long): String {
    if (durationMs < MILLIS_PER_SECOND) return stringResource(Res.string.rows_duration_ms, durationMs)
    val seconds = durationMs / MILLIS_PER_SECOND
    if (seconds < SECONDS_PER_MINUTE) return stringResource(Res.string.rows_duration_seconds, seconds)
    return stringResource(Res.string.rows_duration_minutes, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}
