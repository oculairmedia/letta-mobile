package com.letta.mobile.data.schedules

/**
 * Plain-English recurrence for a parsed [CronExpr]: "Every weekday at 9:00 AM", "Every 15 minutes,
 * 9:00 AM to 5:45 PM on weekdays", "Every 2 hours", "Monthly on the 1st at 9:00 AM". A shape it
 * cannot phrase faithfully falls back to the raw expression rather than a wrong sentence.
 */
internal object CronPhrase {
    private const val MAX_LISTED_TIMES = 3
    private const val HOURS_PER_DAY = 24
    private const val MINUTES_PER_HOUR = 60
    private val WEEKDAYS = listOf(1, 2, 3, 4, 5)
    private val WEEKEND = listOf(6, 7)
    private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    fun describe(expr: CronExpr): String {
        val days = dayPhrase(expr) ?: return expr.raw
        val minutes = expr.minutes.sorted()
        val hours = expr.hours.sorted()
        if (minutes.size * hours.size <= MAX_LISTED_TIMES) {
            val times = hours.flatMap { h -> minutes.map { m -> ScheduleFormat.clockLabel(h, m) } }
            return "${days.lead} at ${joinAnd(times)}"
        }
        val cadence = cadence(minutes, hours) ?: return expr.raw
        return cadence + days.suffix
    }

    /** How the day part reads before a time ([lead]) and after a cadence ([suffix]). */
    private class DayPhrase(val lead: String, val suffix: String)

    private fun dayPhrase(expr: CronExpr): DayPhrase? {
        val allMonths = expr.months.size == 12
        val anyDow = !expr.dowRestricted || expr.isoDaysOfWeek.size == 7
        return when {
            expr.domRestricted && expr.dowRestricted -> null
            !allMonths -> yearlyPhrase(expr)
            expr.domRestricted -> monthlyPhrase(expr.daysOfMonth.sorted())
            anyDow -> DayPhrase("Every day", "")
            else -> weekdayPhrase(expr.isoDaysOfWeek.sorted())
        }
    }

    private fun yearlyPhrase(expr: CronExpr): DayPhrase? {
        val month = expr.months.singleOrNull() ?: return null
        val day = expr.daysOfMonth.singleOrNull()?.takeIf { expr.domRestricted && !expr.dowRestricted } ?: return null
        val date = "${ScheduleFormat.monthShort(month)} $day"
        return DayPhrase("Every year on $date", " on $date")
    }

    private fun monthlyPhrase(days: List<Int>): DayPhrase {
        val list = joinAnd(days.map(::ordinal))
        return DayPhrase("Monthly on the $list", " on the $list of the month")
    }

    private fun weekdayPhrase(days: List<Int>): DayPhrase = when (days) {
        WEEKDAYS -> DayPhrase("Every weekday", " on weekdays")
        WEEKEND -> DayPhrase("Every Saturday and Sunday", " on weekends")
        else -> if (days.size == 1) {
            val name = DAY_NAMES[days.single() - 1]
            DayPhrase("Every $name", " on ${name}s")
        } else {
            val names = joinAnd(days.map(ScheduleFormat::weekdayShort))
            DayPhrase("Every $names", " on $names")
        }
    }

    private fun cadence(minutes: List<Int>, hours: List<Int>): String? {
        val singleMinute = minutes.singleOrNull()
        if (singleMinute != null) return hourlyCadence(singleMinute, hours)
        val step = when {
            minutes.size == MINUTES_PER_HOUR -> 1
            else -> fullStep(minutes, MINUTES_PER_HOUR) ?: return null
        }
        val base = if (step == 1) "Every minute" else "Every $step minutes"
        return base + window(hours, minutes.last())
    }

    private fun hourlyCadence(minute: Int, hours: List<Int>): String? {
        val at = if (minute == 0) "" else " at :${ScheduleFormat.pad2(minute)}"
        if (hours.size == HOURS_PER_DAY) return "Every hour$at"
        fullStep(hours, HOURS_PER_DAY)?.let { return "Every $it hours$at" }
        val step = stepOf(hours) ?: return null
        val base = if (step == 1) "Every hour$at" else "Every $step hours$at"
        return "$base, ${ScheduleFormat.clockLabel(hours.first(), minute)} to ${ScheduleFormat.clockLabel(hours.last(), minute)}"
    }

    /** ", 9:00 AM to 5:45 PM" when [hours] is one contiguous window short of the whole day. */
    private fun window(hours: List<Int>, lastMinute: Int): String {
        if (hours.size == HOURS_PER_DAY) return ""
        val first = hours.first()
        val last = hours.last()
        if (stepOf(hours) != 1 && hours.size > 1) return ", during ${hours.size} hours of the day"
        return ", ${ScheduleFormat.clockLabel(first, 0)} to ${ScheduleFormat.clockLabel(last, lastMinute)}"
    }

    /** The step of a true `*`/step field: starts at 0, evenly spaced, and runs to the end of [size]. */
    private fun fullStep(values: List<Int>, size: Int): Int? {
        if (values.first() != 0) return null
        val step = stepOf(values) ?: return null
        return step.takeIf { values.last() + step >= size && step > 1 }
    }

    /** The common difference of [values] when evenly spaced, else null. */
    private fun stepOf(values: List<Int>): Int? {
        if (values.size < 2) return null
        val step = values[1] - values[0]
        return step.takeIf { values.zipWithNext().all { (a, b) -> b - a == step } }
    }

}

/** "1st", "2nd", "11th", "23rd". */
private fun ordinal(day: Int): String {
    val suffix = when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}

/** "a", "a and b", "a, b and c". */
private fun joinAnd(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items.single()
    else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
}
