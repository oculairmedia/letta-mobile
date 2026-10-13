package com.letta.mobile.ui.screens.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.templates.AgentTemplatesController
import com.letta.mobile.data.templates.AgentTemplatesState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Android binding of the shared starter templates (letta-mobile-ckmub.2.5). */
@HiltViewModel
class TemplatesViewModel @Inject constructor(agentRepository: IAgentRepository) : ViewModel() {
    val controller = AgentTemplatesController(
        agentRepository = agentRepository,
        scope = viewModelScope,
        errorMessageMapper = ::mapErrorToUserMessage,
    )

    val state: StateFlow<AgentTemplatesState> = controller.state
}
