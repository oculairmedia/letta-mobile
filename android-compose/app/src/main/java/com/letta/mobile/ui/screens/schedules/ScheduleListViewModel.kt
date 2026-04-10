package com.letta.mobile.ui.screens.schedules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ScheduleRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class ScheduleListUiState(
    val agents: List<Agent> = emptyList(),
    val selectedAgentId: String? = null,
    val schedules: List<ScheduledMessage> = emptyList(),
    val scheduleAdminAvailable: Boolean = true,
    val scheduleAdminMessage: String? = null,
)

@HiltViewModel
class ScheduleListViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val scheduleRepository: ScheduleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<ScheduleListUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ScheduleListUiState>> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                agentRepository.refreshAgents()
                val agents = agentRepository.agents.value
                val selectedAgentId = (_uiState.value as? UiState.Success)?.data?.selectedAgentId
                    ?: agents.firstOrNull()?.id
                val schedules = if (selectedAgentId != null) {
                    scheduleRepository.refreshSchedules(selectedAgentId)
                    scheduleRepository.getSchedules(selectedAgentId).first()
                } else {
                    emptyList()
                }
                _uiState.value = UiState.Success(
                    ScheduleListUiState(
                        agents = agents,
                        selectedAgentId = selectedAgentId,
                        schedules = schedules,
                        scheduleAdminAvailable = true,
                        scheduleAdminMessage = null,
                    )
                )
            } catch (e: Exception) {
                if (e.isScheduleAdminUnavailable()) {
                    _uiState.value = UiState.Success(
                        ScheduleListUiState(
                            agents = agentRepository.agents.value,
                            selectedAgentId = agentRepository.agents.value.firstOrNull()?.id,
                            schedules = emptyList(),
                            scheduleAdminAvailable = false,
                            scheduleAdminMessage = SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE,
                        )
                    )
                } else {
                    _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load schedules"))
                }
            }
        }
    }

    fun selectAgent(agentId: String) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                scheduleRepository.refreshSchedules(agentId)
                val schedules = scheduleRepository.getSchedules(agentId).first()
                _uiState.value = UiState.Success(
                    current.copy(
                        selectedAgentId = agentId,
                        schedules = schedules,
                        scheduleAdminAvailable = true,
                        scheduleAdminMessage = null,
                    )
                )
            } catch (e: Exception) {
                if (e.isScheduleAdminUnavailable()) {
                    _uiState.value = UiState.Success(
                        current.copy(
                            selectedAgentId = agentId,
                            schedules = emptyList(),
                            scheduleAdminAvailable = false,
                            scheduleAdminMessage = SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE,
                        )
                    )
                } else {
                    _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load schedules"))
                }
            }
        }
    }

    fun createSchedule(agentId: String, params: ScheduleCreateParams) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                scheduleRepository.createSchedule(agentId, params)
                val schedules = scheduleRepository.getSchedules(agentId).first()
                _uiState.value = UiState.Success(
                    current.copy(
                        selectedAgentId = agentId,
                        schedules = schedules,
                        scheduleAdminAvailable = true,
                        scheduleAdminMessage = null,
                    )
                )
            } catch (e: Exception) {
                if (e.isScheduleAdminUnavailable()) {
                    _uiState.value = UiState.Success(
                        current.copy(
                            selectedAgentId = agentId,
                            schedules = emptyList(),
                            scheduleAdminAvailable = false,
                            scheduleAdminMessage = SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE,
                        )
                    )
                } else {
                    _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to create schedule"))
                }
            }
        }
    }

    fun deleteSchedule(scheduledMessageId: String) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            val agentId = current.selectedAgentId ?: return@launch
            try {
                scheduleRepository.deleteSchedule(agentId, scheduledMessageId)
                val schedules = scheduleRepository.getSchedules(agentId).first()
                _uiState.value = UiState.Success(current.copy(schedules = schedules))
            } catch (e: Exception) {
                if (e.isScheduleAdminUnavailable()) {
                    _uiState.value = UiState.Success(
                        current.copy(
                            schedules = emptyList(),
                            scheduleAdminAvailable = false,
                            scheduleAdminMessage = SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE,
                        )
                    )
                } else {
                    _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to delete schedule"))
                }
            }
        }
    }

    private fun Exception.isScheduleAdminUnavailable(): Boolean {
        return this is ApiException && code in setOf(404, 405, 501)
    }

    private companion object {
        const val SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE = "Schedule admin isn't available on this Letta server."
    }
}
