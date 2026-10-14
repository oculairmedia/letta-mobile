package com.letta.mobile.ui.screens.schedules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.schedules.ScheduleCreateRequest
import com.letta.mobile.data.schedules.ScheduleLibraryController
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ScheduleListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    agentRepository: IAgentRepository,
    scheduleRepository: IScheduleRepository,
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
    )

    /** The shared Schedules page's library state (letta-mobile-ckmub.3.16). */
    val state: StateFlow<ScheduleLibraryState> = controller.state

    init {
        controller.start()
    }

    fun loadData() {
        controller.loadData()
    }

    fun selectAgent(agentId: String) {
        controller.selectAgent(agentId)
    }

    /** The page's "New schedule": a recurring schedule through the native schedule route. */
    fun create(request: ScheduleCreateRequest) {
        controller.createRecurringSchedule(request)
    }

    fun deleteSchedule(scheduledMessageId: String) {
        controller.deleteSchedule(scheduledMessageId)
    }

    override fun onCleared() {
        controller.close()
    }
}

/** letta-mobile-ckmub.4: schedules have a route only through an Iroh host's `schedule.*` relay. */
private fun Throwable.isScheduleAdminUnavailable(): Boolean = this is AppServerRouteUnavailable
