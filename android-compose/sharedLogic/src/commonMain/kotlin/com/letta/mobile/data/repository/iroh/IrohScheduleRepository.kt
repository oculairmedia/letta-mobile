package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class IrohScheduleRepository(
    private val directoryProvider: () -> IrohAdminRpcAgentDirectory?,
) : IScheduleRepository {
    private val schedulesByAgentFlow = MutableStateFlow<Map<String, List<ScheduledMessage>>>(emptyMap())

    override fun getSchedules(agentId: String): Flow<List<ScheduledMessage>> =
        schedulesByAgentFlow.map { it[agentId].orEmpty() }

    override suspend fun refreshSchedules(agentId: String, limit: Int?, after: String?) {
        val schedules = try {
            directory().listSchedules(agentId)
        } catch (t: TimelineTransportHttpException) {
            if (t.code == 502 && "HTTP 404" in t.message.orEmpty()) emptyList() else throw t
        }
        schedulesByAgentFlow.update { current ->
            current.toMutableMap().apply { put(agentId, schedules) }
        }
    }

    override suspend fun getSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage {
        schedulesByAgentFlow.value[agentId]
            ?.firstOrNull { it.id == scheduledMessageId }
            ?.let { return it }
        return directory().getSchedule(scheduledMessageId, agentId)
            ?: throw NoSuchElementException("Schedule $scheduledMessageId not found over iroh admin_rpc")
    }

    override suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage {
        val schedule = directory().createSchedule(agentId, params)
        schedulesByAgentFlow.update { current ->
            current.toMutableMap().apply {
                put(agentId, get(agentId).orEmpty() + schedule)
            }
        }
        return schedule
    }

    override suspend fun deleteSchedule(agentId: String, scheduledMessageId: String) {
        directory().deleteSchedule(scheduledMessageId, agentId)
        schedulesByAgentFlow.update { current ->
            current.toMutableMap().apply {
                put(agentId, get(agentId).orEmpty().filterNot { it.id == scheduledMessageId })
            }
        }
    }

    private fun directory(): IrohAdminRpcAgentDirectory =
        directoryProvider() ?: error("Iroh admin RPC directory is unavailable for schedules")
}
