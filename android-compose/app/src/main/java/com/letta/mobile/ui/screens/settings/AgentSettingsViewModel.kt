package com.letta.mobile.ui.screens.settings

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
data class AgentSettingsUiState(
    val agent: Agent? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2000,
    val parallelToolCalls: Boolean = true,
    val personaBlock: String = "",
    val humanBlock: String = "",
    val systemPrompt: String = "",
    val enableSleeptime: Boolean = false,
    val secrets: Map<String, String> = emptyMap(),
)

@HiltViewModel
class AgentSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val agentRepository: AgentRepository,
    private val blockRepository: BlockRepository,
) : ViewModel() {

    private val agentId: String = savedStateHandle.get<String>("agentId") ?: ""

    private val _uiState = MutableStateFlow<UiState<AgentSettingsUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<AgentSettingsUiState>> = _uiState.asStateFlow()

    private var originalPersonaBlock: String = ""
    private var originalHumanBlock: String = ""

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val agent = agentRepository.getAgent(agentId).first()
                val persona = agent.blocks?.find { it.label == "persona" }?.value ?: ""
                val human = agent.blocks?.find { it.label == "human" }?.value ?: ""
                originalPersonaBlock = persona
                originalHumanBlock = human
                _uiState.value = UiState.Success(
                    AgentSettingsUiState(
                        agent = agent,
                        temperature = agent.modelSettings?.temperature?.toFloat() ?: 0.7f,
                        maxTokens = agent.modelSettings?.maxOutputTokens ?: 2000,
                        parallelToolCalls = agent.modelSettings?.parallelToolCalls ?: true,
                        personaBlock = persona,
                        humanBlock = human,
                        systemPrompt = agent.system ?: "",
                        enableSleeptime = agent.enableSleeptime ?: false,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load settings")
            }
        }
    }

    fun updateTemperature(value: Float) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(temperature = value.coerceIn(0f, 2f)))
        }
    }

    fun updateMaxTokens(value: Int) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(maxTokens = value.coerceAtLeast(1)))
        }
    }

    fun updateParallelToolCalls(value: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(parallelToolCalls = value))
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

    fun updateSleeptime(value: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(enableSleeptime = value))
        }
    }

    fun exportAgent(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val data = agentRepository.exportAgent(agentId)
                onResult(data)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to export agent")
            }
        }
    }

    fun saveSettings() {
        viewModelScope.launch {
            try {
                val state = (_uiState.value as? UiState.Success)?.data ?: return@launch
                agentRepository.updateAgent(
                    agentId,
                    AgentUpdateParams(
                        system = state.systemPrompt,
                        enableSleeptime = state.enableSleeptime,
                    )
                )
                if (state.personaBlock != originalPersonaBlock) {
                    blockRepository.updateBlock(agentId, "persona", state.personaBlock)
                }
                if (state.humanBlock != originalHumanBlock) {
                    blockRepository.updateBlock(agentId, "human", state.humanBlock)
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to save settings")
            }
        }
    }

    fun deleteAgent(onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                agentRepository.deleteAgent(agentId)
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to delete agent")
            }
        }
    }
}
