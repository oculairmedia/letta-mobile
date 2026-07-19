package com.letta.mobile.desktop.data

import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class DesktopIrohScheduleRepository(
    private val directoryProvider: () -> IrohAdminRpcAgentDirectory?,
) : IScheduleRepository {
    private val schedulesByAgentFlow = MutableStateFlow<Map<String, List<ScheduledMessage>>>(emptyMap())

    override fun getSchedules(agentId: String): Flow<List<ScheduledMessage>> =
        schedulesByAgentFlow.map { it[agentId].orEmpty() }

    override suspend fun refreshSchedules(agentId: String, limit: Int?, after: String?) {
        val directory = directoryProvider()
            ?: throw DesktopRepositoryUnavailableException("IrohAdminRpcAgentDirectory", "refreshSchedules")
        val schedules = try {
            directory.listSchedules(agentId)
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
        val directory = directoryProvider()
            ?: throw DesktopRepositoryUnavailableException("IrohAdminRpcAgentDirectory", "getSchedule")
        return directory.getSchedule(scheduledMessageId, agentId)
            ?: throw NoSuchElementException("Schedule $scheduledMessageId not found over iroh admin_rpc")
    }

    override suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage =
        throw DesktopRepositoryUnavailableException("DesktopIrohScheduleRepository", "createSchedule")

    override suspend fun deleteSchedule(agentId: String, scheduledMessageId: String) {
        val directory = directoryProvider()
            ?: throw DesktopRepositoryUnavailableException("IrohAdminRpcAgentDirectory", "deleteSchedule")
        directory.deleteSchedule(scheduledMessageId, agentId)
        schedulesByAgentFlow.update { current ->
            current.toMutableMap().apply {
                put(agentId, get(agentId).orEmpty().filterNot { it.id == scheduledMessageId })
            }
        }
    }
}
