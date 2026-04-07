package com.letta.mobile.ui.screens.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class AgentTemplate(
    val id: String,
    val name: String,
    val description: String,
    val icon: String
)

@androidx.compose.runtime.Immutable
data class TemplatesUiState(
    val templates: List<AgentTemplate> = emptyList()
)

private val BUILTIN_TEMPLATES = listOf(
    AgentTemplate("default", "Default Agent", "A general-purpose conversational agent", "\uD83E\uDD16"),
    AgentTemplate("coder", "Coding Assistant", "An agent specialized in programming tasks", "\uD83D\uDCBB"),
    AgentTemplate("writer", "Writing Assistant", "An agent for creative and professional writing", "\u270D\uFE0F"),
)

@HiltViewModel
class TemplatesViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<TemplatesUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<TemplatesUiState>> = _uiState.asStateFlow()

    init {
        loadTemplates()
    }

    fun loadTemplates() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                _uiState.value = UiState.Success(TemplatesUiState(templates = BUILTIN_TEMPLATES))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load templates")
            }
        }
    }

    fun createFromTemplate(templateId: String, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val template = BUILTIN_TEMPLATES.find { it.id == templateId } ?: return@launch
                val agent = agentRepository.createAgent(
                    AgentCreateParams(name = template.name, description = template.description)
                )
                onSuccess(agent.id)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to create agent")
            }
        }
    }
}
