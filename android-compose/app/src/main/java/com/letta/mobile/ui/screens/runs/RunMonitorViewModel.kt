package com.letta.mobile.ui.screens.runs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.runs.RunMonitorController
import com.letta.mobile.data.runs.RunMonitorState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Android binding of the shared run monitor (letta-mobile-ckmub.2.5): the sharedLogic
 * [RunMonitorController] over the session's run repository, plus the agents' names for its rows.
 */
@HiltViewModel
class RunMonitorViewModel @Inject constructor(
    runRepository: IRunRepository,
    agentRepository: IAgentRepository,
) : ViewModel() {
    val controller = RunMonitorController(
        runRepository = runRepository,
        scope = viewModelScope,
        errorMessageMapper = ::mapErrorToUserMessage,
    )

    val state: StateFlow<RunMonitorState> = controller.state

    val agentNames: StateFlow<Map<String, String>> = agentRepository.agents
        .map { agents -> agents.associate { it.id.value to it.name.ifBlank { it.id.value } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(NAMES_STOP_TIMEOUT_MS), emptyMap())

    init {
        controller.start()
    }
}

/** Keeps the agents' names across a configuration change, then lets them stop. */
private const val NAMES_STOP_TIMEOUT_MS = 5_000L
