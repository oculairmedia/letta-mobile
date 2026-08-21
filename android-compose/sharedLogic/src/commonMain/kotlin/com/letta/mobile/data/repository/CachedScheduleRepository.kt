package com.letta.mobile.data.repository

import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.repository.api.ScheduleRemoteSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Phase 5d: platform-neutral cached schedule repository. Android supplies HTTP
 * [ScheduleRemoteSource]. Desktop continues to use
 * [com.letta.mobile.data.repository.iroh.IrohScheduleRepository] for iroh://.
 */
open class CachedScheduleRepository(
    private val remote: ScheduleRemoteSource,
) : IScheduleRepository {
    private val _schedules = MutableStateFlow<Map<String, List<ScheduledMessage>>>(emptyMap())

    override fun getSchedules(agentId: String): Flow<List<ScheduledMessage>> {
        return _schedules.asStateFlow().map { current -> current[agentId] ?: emptyList() }
    }

    override suspend fun refreshSchedules(agentId: String, limit: Int?, after: String?) {
        val response = remote.listSchedules(agentId = agentId, limit = limit, after = after)
        val schedules = response.scheduledMessages
        _schedules.update { current ->
            current.toMutableMap().apply { put(agentId, schedules) }
        }
    }

    override suspend fun getSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage {
        return remote.retrieveSchedule(agentId, scheduledMessageId)
    }

    override suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage {
        val schedule = remote.createSchedule(agentId, params)
        refreshSchedules(agentId)
        return schedule
    }

    override suspend fun deleteSchedule(agentId: String, scheduledMessageId: String) {
        remote.deleteSchedule(agentId, scheduledMessageId)
        _schedules.update { current ->
            current.toMutableMap().apply {
                val existing = get(agentId) ?: emptyList()
                put(agentId, existing.filterNot { it.id == scheduledMessageId })
            }
        }
    }
}
