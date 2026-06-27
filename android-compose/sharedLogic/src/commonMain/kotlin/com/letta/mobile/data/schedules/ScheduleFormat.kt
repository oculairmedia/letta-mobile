package com.letta.mobile.data.schedules

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Instant

/**
 * Shared presentation helpers for the schedule surfaces — clock labels,
 * relative countdowns, day headings. Pure and platform-neutral (commonMain) so
 * desktop and mobile render schedule times identically without a per-platform
 * formatter. `kotlinx-datetime` has no locale-aware patterning, so the small
 * amount of formatting we need is done by hand.
 */
object ScheduleFormat {

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val WEEKDAYS_FULL = listOf(
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
    )

    fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()

    /** 12-hour clock label, e.g. `9:05 AM`, `12:00 PM`. */
    fun clockLabel(hour: Int, minute: Int): String {
        val period = if (hour < 12) "AM" else "PM"
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "$h12:${pad2(minute)} $period"
    }

    /** 12-hour clock label for [instant] in [zone]. */
    fun timeOfDay(instant: Instant, zone: TimeZone): String {
        val ldt = instant.toLocalDateTime(zone)
        return clockLabel(ldt.hour, ldt.minute)
    }

    /** Weekday name for an ISO day number (Mon=1 .. Sun=7). */
    fun weekdayShort(isoDay: Int): String = WEEKDAYS[(isoDay - 1).coerceIn(0, 6)]

    fun weekdayShort(day: DayOfWeek): String = weekdayShort(day.isoDayNumber)

    fun weekdayFull(isoDay: Int): String = WEEKDAYS_FULL[(isoDay - 1).coerceIn(0, 6)]

    fun monthShort(monthNumber: Int): String = MONTHS[(monthNumber - 1).coerceIn(0, 11)]

    /** e.g. `Jun 24`. */
    fun monthDay(instant: Instant, zone: TimeZone): String {
        val ldt = instant.toLocalDateTime(zone)
        return "${monthShort(ldt.month.ordinal + 1)} ${ldt.day}"
    }

    /** e.g. `Mon, Jun 24` — full weekday-prefixed date. */
    fun dateLabel(instant: Instant, zone: TimeZone): String {
        val ldt = instant.toLocalDateTime(zone)
        return "${weekdayShort(ldt.dayOfWeek)}, ${monthShort(ldt.month.ordinal + 1)} ${ldt.day}"
    }

    /**
     * Heading for a day relative to [now]: `Today` / `Tomorrow` / `Yesterday`,
     * else a `Mon, Jun 24` date label.
     */
    fun dayHeading(instant: Instant, zone: TimeZone, now: Instant): String {
        val day = instant.toLocalDateTime(zone).date
        val today = now.toLocalDateTime(zone).date
        val delta = (day.toEpochDays() - today.toEpochDays()).toInt()
        return when (delta) {
            0 -> "Today"
            1 -> "Tomorrow"
            -1 -> "Yesterday"
            else -> dateLabel(instant, zone)
        }
    }

    /**
     * Compact signed countdown between [now] and [target]: `in 2h 14m`,
     * `5m ago`, `in 3d`, `now`. Shows at most two units.
     */
    fun relative(now: Instant, target: Instant): String {
        val totalSeconds = (target - now).inWholeSeconds
        if (abs(totalSeconds) < 45) return "now"
        val future = totalSeconds > 0
        var seconds = abs(totalSeconds)
        val days = seconds / 86_400; seconds %= 86_400
        val hours = seconds / 3_600; seconds %= 3_600
        val minutes = seconds / 60
        val parts = buildList {
            if (days > 0) add("${days}d")
            if (hours > 0) add("${hours}h")
            if (minutes > 0 && days == 0L) add("${minutes}m")
        }.take(2)
        val body = if (parts.isEmpty()) "<1m" else parts.joinToString(" ")
        return if (future) "in $body" else "$body ago"
    }

    /** `1m 12s`, `340ms`, `2h 5m` — a finished run's duration. */
    fun duration(millis: Long): String {
        if (millis < 1_000) return "${millis}ms"
        var seconds = millis / 1_000
        val hours = seconds / 3_600; seconds %= 3_600
        val minutes = seconds / 60; seconds %= 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}
