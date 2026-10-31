package com.letta.mobile.data.schedules

import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlin.math.abs
import kotlin.time.Instant

/** The calendar views' own labels: the time grid's hour gutter and the zone the times are read in. */
object CalendarLabels {
    /** Compact hour label for a time grid's gutter, e.g. `9 AM`, `12 PM`. */
    fun hourLabel(hour: Int): String {
        val period = if (hour < 12) "AM" else "PM"
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "$h12 $period"
    }

    /**
     * The zone times are shown in, as an offset and its id: `UTC−4 · America/New_York`, or `UTC`.
     * The offset is the one in force at [now], so it changes across a DST switch.
     */
    fun zoneLabel(zone: TimeZone, now: Instant): String {
        val seconds = zone.offsetAt(now).totalSeconds
        val sign = if (seconds < 0) "−" else "+"
        val minutes = abs(seconds) / 60
        val offset = when {
            seconds == 0 -> "UTC"
            minutes % 60 == 0 -> "UTC$sign${minutes / 60}"
            else -> "UTC$sign${minutes / 60}:${ScheduleFormat.pad2(minutes % 60)}"
        }
        return if (isUtc(zone)) offset else "$offset · ${zone.id}"
    }

    private val UTC_IDS = setOf("UTC", "Z")

    /** UTC itself, under any of its names: its label is the offset alone. */
    private fun isUtc(zone: TimeZone): Boolean = zone == TimeZone.UTC || zone.id in UTC_IDS
}
