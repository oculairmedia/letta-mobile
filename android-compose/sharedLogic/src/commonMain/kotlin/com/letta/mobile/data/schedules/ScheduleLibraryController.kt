package com.letta.mobile.data.schedules

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ScheduleLibraryState(
    val agents: List<Agent> = emptyList(),
    val selectedAgentId: String? = null,
    val schedules: List<ScheduledMessage> = emptyList(),
    val scheduleAdminAvailable: Boolean = true,
    val scheduleAdminMessage: String? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
) {
    val selectedAgent: Agent?
        get() = agents.firstOrNull { it.id.value == selectedAgentId }

    val displayItems: List<ScheduleLibraryItem>
        get() = schedules.map { it.toScheduleLibraryItem() }

    val emptyMessage: String
        get() = when {
            !scheduleAdminAvailable -> scheduleAdminMessage ?: SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE
            selectedAgentId == null -> "No agents available."
            else -> "No schedules for this agent."
        }
}

@Immutable
data class ScheduleLibraryItem(
    val schedule: ScheduledMessage,
    val messagePreview: String,
    val timing: ScheduleTiming,
)

@Immutable
sealed interface ScheduleTiming {
    data class Recurring(val cronExpression: String) : ScheduleTiming
    data class OneTime(val displayTime: String) : ScheduleTiming
}

fun ScheduledMessage.toScheduleLibraryItem(): ScheduleLibraryItem =
    ScheduleLibraryItem(
        schedule = this,
        messagePreview = message.messages.firstOrNull()?.content.orEmpty(),
        timing = if (schedule.type == "recurring") {
            ScheduleTiming.Recurring(schedule.cronExpression.orEmpty())
        } else {
            ScheduleTiming.OneTime(nextScheduledTime ?: schedule.scheduledAt?.toString().orEmpty())
        },
    )

class ScheduleLibraryController(
    private val agentRepository: IAgentRepository,
    private val scheduleRepository: IScheduleRepository,
    private val scope: CoroutineScope,
    private val errorMessageMapper: (Throwable, String) -> String = { throwable, fallback ->
        throwable.message ?: fallback
    },
    private val scheduleAdminUnavailableMatcher: (Throwable) -> Boolean = { false },
) : AutoCloseable {
    private val stateFlow = MutableStateFlow(ScheduleLibraryState())
    val state: StateFlow<ScheduleLibraryState> = stateFlow

    private var loadJob: Job? = null
    private var mutationJob: Job? = null

    fun start() {
        if (stateFlow.value.agents.isEmpty() && stateFlow.value.schedules.isEmpty()) {
            loadData()
        }
    }

    fun loadData() {
        loadJob?.cancel()
        loadJob = scope.launch {
            stateFlow.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                agentRepository.refreshAgents()
                val agents = agentRepository.agents.value
                val selectedAgentId = agents.resolveSelection(stateFlow.value.selectedAgentId)
                val schedules = selectedAgentId?.let { loadSchedulesForAgent(it) }.orEmpty()
                stateFlow.value = ScheduleLibraryState(
                    agents = agents,
                    selectedAgentId = selectedAgentId,
                    schedules = schedules,
                    scheduleAdminAvailable = true,
                    scheduleAdminMessage = null,
                    isLoading = false,
                    errorMessage = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (scheduleAdminUnavailableMatcher(t)) {
                    publishUnavailableState(agentRepository.agents.value, stateFlow.value.selectedAgentId)
                } else {
                    stateFlow.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = errorMessageMapper(t, "Failed to load schedules"),
                        )
                    }
                }
            }
        }
    }

    fun selectAgent(agentId: String) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val current = stateFlow.value
            try {
                val schedules = loadSchedulesForAgent(agentId)
                stateFlow.value = current.copy(
                    selectedAgentId = agentId,
                    schedules = schedules,
                    scheduleAdminAvailable = true,
                    scheduleAdminMessage = null,
                    isLoading = false,
                    errorMessage = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (scheduleAdminUnavailableMatcher(t)) {
                    stateFlow.value = current.copy(
                        selectedAgentId = agentId,
                        schedules = emptyList(),
                        scheduleAdminAvailable = false,
                        scheduleAdminMessage = SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE,
                        isLoading = false,
                        errorMessage = null,
                    )
                } else {
                    stateFlow.value = current.copy(
                        isLoading = false,
                        errorMessage = errorMessageMapper(t, "Failed to load schedules"),
                    )
                }
            }
        }
    }

    fun createSchedule(agentId: String, params: ScheduleCreateParams) {
        mutationJob?.cancel()
        mutationJob = scope.launch {
            val current = stateFlow.value
            try {
                scheduleRepository.createSchedule(agentId, params)
                val schedules = scheduleRepository.getSchedules(agentId).first()
                stateFlow.value = current.copy(
                    selectedAgentId = agentId,
                    schedules = schedules,
                    scheduleAdminAvailable = true,
                    scheduleAdminMessage = null,
                    isLoading = false,
                    errorMessage = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (scheduleAdminUnavailableMatcher(t)) {
                    stateFlow.value = current.copy(
                        selectedAgentId = agentId,
                        schedules = emptyList(),
                        scheduleAdminAvailable = false,
                        scheduleAdminMessage = SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE,
                        isLoading = false,
                        errorMessage = null,
                    )
                } else {
                    stateFlow.value = current.copy(
                        isLoading = false,
                        errorMessage = errorMessageMapper(t, "Failed to create schedule"),
                    )
                }
            }
        }
    }

    fun deleteSchedule(scheduledMessageId: String) {
        mutationJob?.cancel()
        mutationJob = scope.launch {
            val current = stateFlow.value
            val agentId = current.selectedAgentId ?: return@launch
            try {
                scheduleRepository.deleteSchedule(agentId, scheduledMessageId)
                val schedules = scheduleRepository.getSchedules(agentId).first()
                stateFlow.value = current.copy(
                    schedules = schedules,
                    scheduleAdminAvailable = true,
                    scheduleAdminMessage = null,
                    isLoading = false,
                    errorMessage = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (scheduleAdminUnavailableMatcher(t)) {
                    stateFlow.value = current.copy(
                        schedules = emptyList(),
                        scheduleAdminAvailable = false,
                        scheduleAdminMessage = SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE,
                        isLoading = false,
                        errorMessage = null,
                    )
                } else {
                    stateFlow.value = current.copy(
                        isLoading = false,
                        errorMessage = errorMessageMapper(t, "Failed to delete schedule"),
                    )
                }
            }
        }
    }

    fun showError(message: String) {
        stateFlow.update {
            it.copy(
                isLoading = false,
                errorMessage = message,
            )
        }
    }

    override fun close() {
        loadJob?.cancel()
        mutationJob?.cancel()
    }

    private suspend fun loadSchedulesForAgent(agentId: String): List<ScheduledMessage> {
        scheduleRepository.refreshSchedules(agentId)
        return scheduleRepository.getSchedules(agentId).first()
    }

    private fun publishUnavailableState(
        fallbackAgents: List<Agent>,
        requestedAgentId: String?,
    ) {
        val selectedAgentId = fallbackAgents.resolveSelection(requestedAgentId)
        stateFlow.value = ScheduleLibraryState(
            agents = fallbackAgents,
            selectedAgentId = selectedAgentId,
            schedules = emptyList(),
            scheduleAdminAvailable = false,
            scheduleAdminMessage = SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE,
            isLoading = false,
            errorMessage = null,
        )
    }

    private fun List<Agent>.resolveSelection(requestedAgentId: String?): String? =
        if (requestedAgentId != null && any { it.id.value == requestedAgentId }) {
            requestedAgentId
        } else {
            firstOrNull()?.id?.value
        }
}

const val SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE = "Schedule admin isn't available on this Letta server."
