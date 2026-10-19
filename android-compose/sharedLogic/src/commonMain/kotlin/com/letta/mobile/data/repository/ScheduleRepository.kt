package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.RemoteRoute
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
 * Per-agent schedules for a backend without the Iroh `schedule.*` relay.
 * [remote] is the seam for a non-App-Server backend; production binds no route (the Letta REST
 * backend was removed), so these calls fail with a no-route error. Iroh hosts use
 * [com.letta.mobile.data.repository.iroh.IrohScheduleRepository].
 */
open class ScheduleRepository(
    private val remote: RemoteRoute<ScheduleRemoteSource> = RemoteRoute.Absent(SessionRoute.SCHEDULES),
) : IScheduleRepository {
    /** A repository over a bound [remote] (tests bind a fake). */
    constructor(remote: ScheduleRemoteSource) : this(remote.bound())

    private val _schedules = MutableStateFlow<Map<String, List<ScheduledMessage>>>(emptyMap())

    override fun getSchedules(agentId: String): Flow<List<ScheduledMessage>> {
        return _schedules.asStateFlow().map { current -> current[agentId] ?: emptyList() }
    }

    override suspend fun refreshSchedules(agentId: String, limit: Int?, after: String?) {
        val response = remote.source().listSchedules(agentId = agentId, limit = limit, after = after)
        val schedules = response.scheduledMessages
        _schedules.update { current ->
            current.toMutableMap().apply { put(agentId, schedules) }
        }
    }

    override suspend fun getSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage {
        return remote.source().retrieveSchedule(agentId, scheduledMessageId)
    }

    override suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage {
        val schedule = remote.source().createSchedule(agentId, params)
        refreshSchedules(agentId)
        return schedule
    }

    override suspend fun deleteSchedule(agentId: String, scheduledMessageId: String) {
        remote.source().deleteSchedule(agentId, scheduledMessageId)
        _schedules.update { current ->
            current.toMutableMap().apply {
                val existing = get(agentId) ?: emptyList()
                put(agentId, existing.filterNot { it.id == scheduledMessageId })
            }
        }
    }
}
