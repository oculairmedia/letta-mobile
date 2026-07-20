package com.letta.mobile.ui.screens.memory

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.memory.MemoryParityController
import com.letta.mobile.data.memory.MemoryParityControllerState
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class MemoryOverviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    sessionManager: SessionManager,
) : ViewModel() {
    private val initialAgentId: String? = savedStateHandle.get<String>("agentId")
        ?.takeIf { it.isNotBlank() }

    private val controller = MemoryParityController(
        sessionGraphProvider = sessionManager,
        scope = viewModelScope,
        errorMessageMapper = { throwable -> throwable.toMemoryOverviewMessage() },
    )

    val state: StateFlow<MemoryParityControllerState> = controller.state

    init {
        if (initialAgentId != null) {
            controller.selectAgent(initialAgentId)
        } else {
            controller.start()
        }
    }

    fun refresh() {
        controller.reload()
    }

    fun selectAgent(agentId: String) {
        controller.selectAgent(agentId)
    }

    override fun onCleared() {
        controller.close()
    }

    private fun Throwable.toMemoryOverviewMessage(): String =
        (this as? Exception)?.let { exception ->
            mapErrorToUserMessage(exception, "Failed to load memory")
        } ?: message ?: "Failed to load memory"
}
