package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleListResponse
import com.letta.mobile.data.model.ScheduledMessage

/**
 * Remote HTTP (or equivalent) schedule admin surface used by
 * [com.letta.mobile.data.repository.CachedScheduleRepository].
 * Platform modules supply Ktor/[ScheduleApi] bindings.
 *
 * Desktop Iroh schedules continue to use
 * [com.letta.mobile.data.repository.iroh.IrohScheduleRepository].
 */
interface ScheduleRemoteSource {
    suspend fun listSchedules(
        agentId: String,
        limit: Int? = null,
        after: String? = null,
    ): ScheduleListResponse

    suspend fun retrieveSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage
    suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage
    suspend fun deleteSchedule(agentId: String, scheduledMessageId: String)
}
