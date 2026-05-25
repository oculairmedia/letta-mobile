package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduledMessage
import kotlinx.coroutines.flow.Flow

interface IScheduleRepository {
    fun getSchedules(agentId: String): Flow<List<ScheduledMessage>>
    suspend fun refreshSchedules(agentId: String, limit: Int? = 100, after: String? = null)
    suspend fun getSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage
    suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage
    suspend fun deleteSchedule(agentId: String, scheduledMessageId: String)
}
