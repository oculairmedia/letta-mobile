package com.letta.mobile.ui.screens.memory

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.data.memory.graph.MemoryPageController
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Android binding for the shared memory page: owns a [MemoryPageController]
 * over the active session graph for the ViewModel's lifetime. All graph,
 * selection and edit logic lives in sharedLogic.
 */
@HiltViewModel
class MemoryOverviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    sessionManager: SessionManager,
) : ViewModel() {
    private val initialAgentId: String? = savedStateHandle.get<String>("agentId")
        ?.takeIf { it.isNotBlank() }

    private val controller = MemoryPageController.forSession(
        sessionGraphProvider = sessionManager,
        scope = viewModelScope,
        errorMessageMapper = { throwable -> throwable.toMemoryOverviewMessage() },
    )

    val state: StateFlow<MemoryPageState> = controller.state
    val actions: MemoryPageActions = controller

    init {
        if (initialAgentId != null) {
            controller.selectAgent(initialAgentId)
        } else {
            controller.start()
        }
    }

    override fun onCleared() {
        controller.close()
    }

    private fun Throwable.toMemoryOverviewMessage(): String =
        (this as? Exception)?.let { exception ->
            mapErrorToUserMessage(exception, "Failed to load memory")
        } ?: message ?: "Failed to load memory"
}
