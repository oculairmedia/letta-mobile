package com.letta.mobile.ui.shell

import com.letta.mobile.util.time.TimeLabels
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime

// letta-mobile-8k3xs: the time a pointer host's hover card shows (desktop formats its own with java.time).

/** "8:07 PM" for today, else the compact age ("3d"); text that is not an instant comes back unchanged. */
internal fun shellHoverTimeLabel(raw: String, now: Instant = Clock.System.now(), zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val instant = runCatching { Instant.parse(raw) }.getOrNull() ?: return raw
    val local = instant.toLocalDateTime(zone)
    return if (local.date == now.toLocalDateTime(zone).date) local.format(HoverClock) else TimeLabels.age(instant, now)
}

private val HoverClock = LocalDateTime.Format {
    amPmHour(Padding.NONE)
    char(':')
    minute()
    char(' ')
    amPmMarker("AM", "PM")
}
