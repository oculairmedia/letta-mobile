package com.letta.mobile.data.schedules

import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** The preset cadences the cron builder offers (Phase 7 "New schedule"). */
enum class CronCadence { EveryNMinutes, Hourly, Daily, Weekly, Custom }

/**
 * Editable state for the cron builder. The UI binds dropdowns/toggles to these
 * fields; [CronBuilder.toExpression] turns the state into a cron string and
 * [CronBuilder.fromExpression] best-effort reverses an existing schedule back
 * into the builder for editing. Pure/commonMain so the same builder backs
 * desktop now and mobile later.
 */
data class CronBuilderState(
    val cadence: CronCadence = CronCadence.Daily,
    /** EveryNMinutes interval (2..59). */
    val intervalMinutes: Int = 15,
    /** Minute-of-hour for Hourly/Daily/Weekly (0..59). */
    val minute: Int = 0,
    /** Hour-of-day for Daily/Weekly (0..23). */
    val hour: Int = 9,
    /** ISO weekdays selected for Weekly (Mon=1 .. Sun=7). */
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5),
    /** Raw expression for the Custom escape hatch. */
    val customExpression: String = "",
)

object CronBuilder {

    /**
     * Builds a standard 5-part cron expression for a recurring schedule.
     * Kept as the simple programmatic helper used by shared coverage tests.
     */
    fun buildRecurring(
        minute: Int? = null,
        hour: Int? = null,
        dayOfMonth: Int? = null,
        month: Int? = null,
        dayOfWeek: Int? = null,
    ): String {
        require(minute == null || minute in 0..59) { "Minute must be 0-59" }
        require(hour == null || hour in 0..23) { "Hour must be 0-23" }
        require(dayOfMonth == null || dayOfMonth in 1..31) { "Day of month must be 1-31" }
        require(month == null || month in 1..12) { "Month must be 1-12" }
        require(dayOfWeek == null || dayOfWeek in 0..7) { "Day of week must be 0-7" }

        return "${minute ?: "*"} ${hour ?: "*"} ${dayOfMonth ?: "*"} ${month ?: "*"} ${dayOfWeek ?: "*"}"
    }

    /**
     * Builds a cron expression for a one-off run at a specific date and time.
     */
    fun buildOneOff(
        minute: Int,
        hour: Int,
        dayOfMonth: Int,
        month: Int,
    ): String {
        require(minute in 0..59) { "Minute must be 0-59" }
        require(hour in 0..23) { "Hour must be 0-23" }
        require(dayOfMonth in 1..31) { "Day of month must be 1-31" }
        require(month in 1..12) { "Month must be 1-12" }

        return "$minute $hour $dayOfMonth $month *"
    }

    /** Build a cron expression from [state], or `null` if it is incomplete. */
    fun toExpression(state: CronBuilderState): String? = when (state.cadence) {
        CronCadence.EveryNMinutes -> {
            val n = state.intervalMinutes
            if (n in 2..59) "*/$n * * * *" else null
        }
        CronCadence.Hourly -> {
            if (state.minute in 0..59) "${state.minute} * * * *" else null
        }
        CronCadence.Daily -> {
            if (validTime(state)) "${state.minute} ${state.hour} * * *" else null
        }
        CronCadence.Weekly -> {
            // Reject out-of-range weekdays (ISO Mon=1..Sun=7) so an invalid
            // selection yields null instead of a bad cron string the caller
            // treats as creatable (CodeRabbit review #13).
            if (!validTime(state) || state.daysOfWeek.isEmpty() ||
                state.daysOfWeek.any { it !in 1..7 }
            ) {
                null
            } else {
                val dow = state.daysOfWeek.sorted().joinToString(",")
                "${state.minute} ${state.hour} * * $dow"
            }
        }
        CronCadence.Custom -> state.customExpression.trim()
            .takeIf { CronSchedule.isValid(it) }
    }

    /**
     * Best-effort reverse: map an existing cron [expression] back into builder
     * state so the "Edit" flow pre-fills sensibly. Anything that doesn't map to
     * a known preset lands in [CronCadence.Custom] with the raw expression.
     */
    fun fromExpression(expression: String): CronBuilderState {
        val custom = CronBuilderState(cadence = CronCadence.Custom, customExpression = expression.trim())
        val expr = CronSchedule.parse(expression) ?: return custom
        val parts = expression.trim().split(Regex("\\s+"))
        val minuteField = parts[0]
        val hourField = parts[1]
        val singleMinute = expr.minutes.singleOrNull()
        val singleHour = expr.hours.singleOrNull()
        val anyDay = !expr.domRestricted && !expr.dowRestricted

        // */n minutes.
        val minuteStep = minuteField.removePrefix("*/").toIntOrNull()
        if (minuteField.startsWith("*/") && minuteStep != null && hourField == "*" && anyDay) {
            return CronBuilderState(cadence = CronCadence.EveryNMinutes, intervalMinutes = minuteStep)
        }
        // Hourly at :mm.
        if (singleMinute != null && hourField == "*" && anyDay) {
            return CronBuilderState(cadence = CronCadence.Hourly, minute = singleMinute)
        }
        // Daily at h:mm.
        if (singleMinute != null && singleHour != null && anyDay) {
            return CronBuilderState(cadence = CronCadence.Daily, minute = singleMinute, hour = singleHour)
        }
        // Weekly on selected days at h:mm.
        if (singleMinute != null && singleHour != null && expr.dowRestricted && !expr.domRestricted) {
            return CronBuilderState(
                cadence = CronCadence.Weekly,
                minute = singleMinute,
                hour = singleHour,
                daysOfWeek = expr.isoDaysOfWeek,
            )
        }
        return custom
    }

    /** Human-readable preview of [state], or a hint when it is invalid. */
    fun preview(state: CronBuilderState): String {
        val expression = toExpression(state) ?: return "Incomplete schedule"
        val expr = CronSchedule.parse(expression) ?: return "Invalid expression"
        return CronSchedule.describe(expr)
    }

    /** Next [count] run instants for [state], for the builder's preview list. */
    fun previewRuns(
        state: CronBuilderState,
        from: Instant,
        zone: TimeZone,
        count: Int = 3,
    ): List<Instant> {
        val expression = toExpression(state) ?: return emptyList()
        val expr = CronSchedule.parse(expression) ?: return emptyList()
        return CronSchedule.nextRuns(expr, from, zone, count)
    }

    private fun validTime(state: CronBuilderState): Boolean =
        state.minute in 0..59 && state.hour in 0..23
}
