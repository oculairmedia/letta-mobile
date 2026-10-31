package com.letta.mobile.data.schedules

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * A parsed standard 5-field cron expression: `minute hour day-of-month month
 * day-of-week`. Each field is expanded into the concrete set of values it
 * allows so matching is a cheap membership test.
 *
 * Platform-neutral (commonMain) so the same next-run math backs the desktop
 * Schedule surface today and the mobile one once it ports (Phase 7). Built on
 * `kotlinx-datetime` because calendar-aware stepping (months of varying
 * length, day-of-week, time zones) can't be done with `java.time` here.
 */
data class CronExpr internal constructor(
    val minutes: Set<Int>,
    val hours: Set<Int>,
    val daysOfMonth: Set<Int>,
    val months: Set<Int>,
    /** ISO day-of-week numbers (Mon=1 .. Sun=7). */
    val isoDaysOfWeek: Set<Int>,
    /** Whether the day-of-month field was restricted (not `*`). */
    val domRestricted: Boolean,
    /** Whether the day-of-week field was restricted (not `*`). */
    val dowRestricted: Boolean,
    val raw: String,
)

object CronSchedule {

    /** Parse a 5-field cron expression, or `null` if it is malformed. */
    fun parse(expression: String): CronExpr? {
        val parts = expression.trim().split(WHITESPACE)
        if (parts.size != 5) return null
        val minutes = parseField(parts[0], MIN_MINUTE, MAX_MINUTE) ?: return null
        val hours = parseField(parts[1], MIN_HOUR, MAX_HOUR) ?: return null
        val dom = parseField(parts[2], MIN_DOM, MAX_DOM) ?: return null
        val months = parseField(parts[3], MIN_MONTH, MAX_MONTH) ?: return null
        val dowRaw = parseField(parts[4], MIN_DOW, MAX_DOW) ?: return null
        // Cron day-of-week: 0 and 7 both mean Sunday. Normalize to ISO (Sun=7).
        val iso = dowRaw.map { if (it == 0) 7 else it }.toSet()
        return CronExpr(
            minutes = minutes,
            hours = hours,
            daysOfMonth = dom,
            months = months,
            isoDaysOfWeek = iso,
            domRestricted = parts[2] != "*",
            dowRestricted = parts[4] != "*",
            raw = expression.trim(),
        )
    }

    /** Convenience: `true` when [expression] is a parseable 5-field cron. */
    fun isValid(expression: String): Boolean = parse(expression) != null

    /**
     * The next [count] fire times strictly after [from], in [timeZone], in
     * chronological order. Bounded by [maxDays] of look-ahead so a never-firing
     * expression (e.g. Feb 30) terminates instead of spinning.
     */
    fun nextRuns(
        expr: CronExpr,
        from: Instant,
        timeZone: TimeZone,
        count: Int,
        maxDays: Int = DEFAULT_LOOKAHEAD_DAYS,
    ): List<Instant> {
        if (count <= 0) return emptyList()
        val result = ArrayList<Instant>(count)
        val sortedHours = expr.hours.sorted()
        val sortedMinutes = expr.minutes.sorted()
        var date = from.toLocalDateTime(timeZone).date
        var days = 0
        while (days <= maxDays && result.size < count) {
            if ((date.month.ordinal + 1) in expr.months && dayMatches(expr, date)) {
                for (h in sortedHours) {
                    for (m in sortedMinutes) {
                        val instant = date.atTime(h, m)
                            .toInstant(timeZone)
                        if (instant > from) {
                            result.add(instant)
                            if (result.size >= count) return result
                        }
                    }
                }
            }
            date = date.plus(1, DateTimeUnit.DAY)
            days++
        }
        return result
    }

    /** The single next fire time strictly after [from], or `null`. */
    fun nextRun(expr: CronExpr, from: Instant, timeZone: TimeZone): Instant? =
        nextRuns(expr, from, timeZone, count = 1).firstOrNull()

    /**
     * Materialized fire times within `[start, end)` (inclusive of any fire at
     * exactly [start]), in [timeZone]. Used by the Agenda/Week views to place
     * runs on a day or grid.
     */
    fun runsBetween(
        expr: CronExpr,
        start: Instant,
        end: Instant,
        timeZone: TimeZone,
    ): List<Instant> {
        if (end <= start) return emptyList()
        val result = ArrayList<Instant>()
        val sortedHours = expr.hours.sorted()
        val sortedMinutes = expr.minutes.sorted()
        var date = start.toLocalDateTime(timeZone).date
        val endDate = end.toLocalDateTime(timeZone).date
        while (date <= endDate) {
            if ((date.month.ordinal + 1) in expr.months && dayMatches(expr, date)) {
                for (h in sortedHours) {
                    for (m in sortedMinutes) {
                        val instant = date.atTime(h, m)
                            .toInstant(timeZone)
                        if (instant in start..<end) result.add(instant)
                    }
                }
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return result
    }

    /**
     * A human-readable summary of [expr] for the cron-builder preview and every schedule row, e.g.
     * "Every 15 minutes", "Every weekday at 9:00 AM", "Every 2 hours, 9 AM to 5 PM on weekdays".
     * Falls back to the raw expression for shapes it can't phrase (see [CronPhrase]).
     */
    fun describe(expr: CronExpr): String = CronPhrase.describe(expr)

    private fun dayMatches(expr: CronExpr, date: LocalDate): Boolean {
        val domOk = date.day in expr.daysOfMonth
        val dowOk = date.dayOfWeek.isoDayNumber in expr.isoDaysOfWeek
        return when {
            // Standard cron: when BOTH are restricted, a match on either fires.
            expr.domRestricted && expr.dowRestricted -> domOk || dowOk
            expr.domRestricted -> domOk
            expr.dowRestricted -> dowOk
            else -> true
        }
    }

    private fun parseField(field: String, min: Int, max: Int): Set<Int>? {
        val out = HashSet<Int>()
        for (token in field.split(',')) {
            if (token.isEmpty()) return null
            val slash = token.split('/')
            if (slash.size > 2) return null
            val rangePart = slash[0]
            val step = if (slash.size == 2) {
                slash[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            } else {
                1
            }
            val (lo, hi) = when {
                rangePart == "*" -> min to max
                rangePart.contains('-') -> {
                    val r = rangePart.split('-')
                    if (r.size != 2) return null
                    val a = r[0].toIntOrNull() ?: return null
                    val b = r[1].toIntOrNull() ?: return null
                    a to b
                }
                else -> {
                    val v = rangePart.toIntOrNull() ?: return null
                    v to v
                }
            }
            if (lo < min || hi > max || lo > hi) return null
            var v = lo
            while (v <= hi) {
                out.add(v)
                v += step
            }
        }
        return if (out.isEmpty()) null else out
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MIN_MINUTE = 0
    private const val MAX_MINUTE = 59
    private const val MIN_HOUR = 0
    private const val MAX_HOUR = 23
    private const val MIN_DOM = 1
    private const val MAX_DOM = 31
    private const val MIN_MONTH = 1
    private const val MAX_MONTH = 12
    private const val MIN_DOW = 0
    private const val MAX_DOW = 7
    // Must exceed the longest gap between valid fires so leap-day schedules
    // (`0 0 29 2 *`) resolve: Feb 29 recurs every 4 years (~1461 days), so a
    // 400-day window returned null for a perfectly valid expression
    // (CodeRabbit review #14). 1500 covers the leap cycle with margin while
    // still bounding a never-firing expression (e.g. Feb 30).
    private const val DEFAULT_LOOKAHEAD_DAYS = 1500
}
