package com.letta.mobile.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun formatRelativeTime(isoString: String?): String {
    if (isoString.isNullOrBlank()) return ""
    val instant = try {
        Instant.parse(isoString)
    } catch (_: Exception) {
        return isoString
    }

    val now = Instant.now()
    val duration = Duration.between(instant, now)

    return when {
        duration.isNegative -> "Just now"
        duration.toMinutes() < 1 -> "Just now"
        duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
        duration.toHours() < 24 -> "${duration.toHours()}h ago"
        duration.toHours() < 48 -> "Yesterday"
        duration.toDays() < 7 -> "${duration.toDays()}d ago"
        else -> {
            val date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
            val today = LocalDate.now()
            if (date.year == today.year) {
                date.format(DateTimeFormatter.ofPattern("MMM d"))
            } else {
                date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
            }
        }
    }
}
