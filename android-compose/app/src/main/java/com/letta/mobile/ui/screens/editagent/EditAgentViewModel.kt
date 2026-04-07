package com.letta.mobile.ui.screens.editagent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class EditAgentUiState(
    val agent: Agent? = null,
    val name: String = "",
    val description: String = "",
    val model: String = "",
    val personaBlock: String = "",
    val humanBlock: String = "",
    val systemPrompt: String = "",
    val tags: List<String> = emptyList()
)

@HiltViewModel
class EditAgentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val agentRepository: AgentRepository,
    private val blockRepository: BlockRepository,
) : ViewModel() {

    private val agentId: String = savedStateHandle.get<String>("agentId") ?: ""

    private val _uiState = MutableStateFlow<UiState<EditAgentUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<EditAgentUiState>> = _uiState.asStateFlow()

    private var originalPersonaBlock: String = ""
    private var originalHumanBlock: String = ""

    init {
        loadAgent()
    }

    fun loadAgent() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val agent = agentRepository.getAgent(agentId).first()
                val persona = agent.blocks?.find { it.label == "persona" }?.value ?: ""
                val human = agent.blocks?.find { it.label == "human" }?.value ?: ""
                originalPersonaBlock = persona
                originalHumanBlock = human
                _uiState.value = UiState.Success(
                    EditAgentUiState(
                        agent = agent,
                        name = agent.name,
                        description = agent.description ?: "",
                        model = agent.model ?: "",
                        personaBlock = persona,
                        humanBlock = human,
                        systemPrompt = agent.system ?: "",
                        tags = agent.tags ?: emptyList()
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load agent")
            }
        }
    }

    fun updateName(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(name = value))
        }
    }

    fun updateDescription(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(description = value))
        }
    }

    fun updateModel(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(model = value))
        }
    }

    fun updatePersonaBlock(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(personaBlock = value))
        }
    }

    fun updateHumanBlock(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(humanBlock = value))
        }
    }

    fun updateSystemPrompt(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(systemPrompt = value))
        }
    }

    fun saveAgent(onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                val state = (_uiState.value as? UiState.Success)?.data ?: return@launch
                agentRepository.updateAgent(
                    agentId,
                    AgentUpdateParams(
                        name = state.name,
                        description = state.description,
                        model = state.model,
                        system = state.systemPrompt,
                        tags = state.tags
                    )
                )
                if (state.personaBlock != originalPersonaBlock) {
                    blockRepository.updateBlock(agentId, "persona", state.personaBlock)
                }
                if (state.humanBlock != originalHumanBlock) {
                    blockRepository.updateBlock(agentId, "human", state.humanBlock)
                }
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to save agent")
            }
        }
    }
}
