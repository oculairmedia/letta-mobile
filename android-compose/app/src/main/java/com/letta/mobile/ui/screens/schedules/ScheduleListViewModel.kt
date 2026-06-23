package com.letta.mobile.ui.screens.schedules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ScheduleApi
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.schedules.CronScheduleSource
import com.letta.mobile.data.schedules.ScheduleLibraryController
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

typealias ScheduleListUiState = ScheduleLibraryState

@HiltViewModel
class ScheduleListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    agentRepository: IAgentRepository,
    scheduleRepository: IScheduleRepository,
    scheduleApi: ScheduleApi,
) : ViewModel() {
    // When Schedules is opened FROM A CHAT, SchedulesRoute carries the agent
    // the user was chatting with so the Agents dropdown pre-selects it.
    // Other entry points pass SchedulesRoute() (null) and keep the original
    // "no pre-selected agent" behaviour. Mirrors MemoryOverviewViewModel.
    private val initialAgentId: String? = savedStateHandle.get<String>("agentId")
        ?.takeIf { it.isNotBlank() }

    private val controller = ScheduleLibraryController(
        agentRepository = agentRepository,
        scheduleRepository = scheduleRepository,
        scope = viewModelScope,
        initialAgentId = initialAgentId,
        errorMessageMapper = { throwable, fallback ->
            (throwable as? Exception)?.let { mapErrorToUserMessage(it, fallback) }
                ?: throwable.message
                ?: fallback
        },
        scheduleAdminUnavailableMatcher = { throwable -> throwable.isScheduleAdminUnavailable() },
        // Cron-backed fallback: when the native /v1/agents/{id}/schedule
        // route is unavailable (404/405/501), list crons via /v1/crons so
        // mobile reaches parity with the desktop schedules surface instead
        // of dead-ending on "Schedule admin isn't available".
        cronSource = CronScheduleSource { agentId -> scheduleApi.listCrons(agentId) },
    )

    val uiState: StateFlow<UiState<ScheduleListUiState>> = controller.state
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    init {
        controller.start()
    }

    fun loadData() {
        controller.loadData()
    }

    fun selectAgent(agentId: String) {
        controller.selectAgent(agentId)
    }

    fun createSchedule(agentId: String, params: ScheduleCreateParams) {
        controller.createSchedule(agentId, params)
    }

    fun deleteSchedule(scheduledMessageId: String) {
        controller.deleteSchedule(scheduledMessageId)
    }

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}

private fun ScheduleLibraryState.toUiState(): UiState<ScheduleListUiState> {
    val message = errorMessage
    return when {
        isLoading && agents.isEmpty() && schedules.isEmpty() -> UiState.Loading
        message != null -> UiState.Error(message)
        else -> UiState.Success(this)
    }
}

private fun Throwable.isScheduleAdminUnavailable(): Boolean {
    return this is ApiException && code in setOf(404, 405, 501)
}
