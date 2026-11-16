package com.letta.mobile.data.schedules

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.schedules.control.ScheduleIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Repository reads and mutation results, independent of controller jobs and publication. */
internal class ScheduleLibraryOperations(
    private val agentRepository: IAgentRepository,
    private val scheduleRepository: IScheduleRepository,
    private val cronSource: CronScheduleSource?,
    private val errorMessageMapper: (Throwable, String) -> String,
    private val scheduleAdminUnavailableMatcher: (Throwable) -> Boolean,
) {
    private val cronRepository = cronSource?.repository

    suspend fun loadAgentRoster(previous: () -> List<AgentSummary>): Pair<List<AgentSummary>, Throwable?> =
        runCatching { agentRepository.listAgentSummaries() }.fold(
            onSuccess = { it to null },
            onFailure = {
                if (it is CancellationException) throw it
                previous() to it
            },
        )

    suspend fun loadTargetSchedules(target: ScheduleLibraryState): List<ScheduledMessage> =
        target.selectedAgentId?.let { loadSchedulesForAgent(AgentId(it)) }.orEmpty()

    suspend fun loadControlCrons(agentId: AgentId?): List<CronTask>? =
        if (cronRepository != null) cronSource?.listCrons(agentId) else null

    suspend fun deleteAndReload(
        current: ScheduleLibraryState,
        agent: AgentId,
        identity: ScheduleIdentity,
    ): List<ScheduledMessage> {
        val scheduledMessageId = identity.value
        val agentId = agent.value
        val cron = current.crons.any { it.id == scheduledMessageId && (it.agentId == null || it.agentId == agentId) }
        return if (cron && cronRepository != null) {
            cronRepository.deleteSchedule(agentId, scheduledMessageId).getOrThrow()
            loadSchedulesForAgent(agent)
        } else {
            scheduleRepository.deleteSchedule(agentId, scheduledMessageId)
            scheduleRepository.getSchedules(agentId).first()
        }
    }

    suspend fun loadSchedulesForAgent(agent: AgentId): List<ScheduledMessage> {
        scheduleRepository.refreshSchedules(agent.value)
        return scheduleRepository.getSchedules(agent.value).first()
    }

    fun mutationFailure(error: Throwable, current: ScheduleLibraryState, fallback: String): ScheduleLibraryState =
        if (scheduleAdminUnavailableMatcher(error)) current.adminUnavailable()
        else current.copy(isLoading = false, errorMessage = errorMessageMapper(error, fallback))
}

internal fun ScheduleLibraryState.canControlCron(candidate: CronTask, resume: Boolean): Boolean {
    val task = crons.firstOrNull { it.id == candidate.id } ?: return false
    val ownerMatches = task.agentId == null || task.agentId == selectedAgentId || (fleet && selectedAgentId == null)
    val controllable = task.status in setOf(null, "active", "paused")
    val stateMatches = (task.status == "paused") == resume
    return ownerMatches && controllable && stateMatches
}
