package com.letta.mobile.data.schedules.fleet

import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.schedules.CronTask

/**
 * One agent's schedules as a fleet read found them: its native schedules (null when its native
 * schedule route is not served) and its cron tasks (null when the session has no cron source).
 */
internal data class AgentScheduleRead(
    val native: List<ScheduledMessage>?,
    val crons: List<CronTask>?,
    val cronFailure: Throwable? = null,
)
