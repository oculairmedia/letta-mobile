package com.letta.mobile.desktop

import com.letta.mobile.ui.shell.sidebar.ShellRelativeTime
import kotlin.time.Clock

/**
 * Format an ISO-8601 instant (e.g. lastMessageAt) as a compact relative label
 * (now / 5m / 2h / 4d / 3w / 2mo): the shared [ShellRelativeTime]. Non-ISO values are returned unchanged.
 */
internal fun formatRelativeTimestamp(raw: String): String = ShellRelativeTime.compact(raw, Clock.System.now())

/** "8:07 PM" for today, else the relative label ("3d"): the time the window's hover cards show. */
internal fun hoverTimeLabel(raw: String): String {
    val instant = runCatching { java.time.Instant.parse(raw) }.getOrNull() ?: return raw
    val zone = java.time.ZoneId.systemDefault()
    val local = instant.atZone(zone)
    return if (local.toLocalDate() == java.time.LocalDate.now(zone)) {
        local.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
    } else {
        formatRelativeTimestamp(raw)
    }
}
