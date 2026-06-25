package com.letta.mobile.data.schedules

/**
 * Utility for building cron expressions for Letta.
 */
object CronBuilder {
    /** 
     * Builds a standard 5-part cron expression for a recurring schedule.
     */
    fun buildRecurring(
        minute: Int? = null,
        hour: Int? = null,
        dayOfMonth: Int? = null,
        month: Int? = null,
        dayOfWeek: Int? = null
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
        month: Int
    ): String {
        require(minute in 0..59) { "Minute must be 0-59" }
        require(hour in 0..23) { "Hour must be 0-23" }
        require(dayOfMonth in 1..31) { "Day of month must be 1-31" }
        require(month in 1..12) { "Month must be 1-12" }
        
        return "$minute $hour $dayOfMonth $month *"
    }
}
