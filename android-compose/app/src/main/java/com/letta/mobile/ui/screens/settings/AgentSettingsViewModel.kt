package com.letta.mobile.ui.screens.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.ModelSettings
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class AgentSettingsUiState(
    val agent: Agent? = null,
    val agentType: String = "",
    val contextWindow: Int = 0,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2000,
    val parallelToolCalls: Boolean = true,
    val personaBlock: String = "",
    val humanBlock: String = "",
    val systemPrompt: String = "",
    val enableSleeptime: Boolean = false,
    val tools: List<Tool> = emptyList(),
    val secrets: Map<String, String> = emptyMap(),
)

@HiltViewModel
class AgentSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val agentRepository: AgentRepository,
    private val blockRepository: BlockRepository,
    private val messageRepository: MessageRepository,
) : ViewModel() {

    private val agentId: String = savedStateHandle.get<String>("agentId") ?: ""

    private val _uiState = MutableStateFlow<UiState<AgentSettingsUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<AgentSettingsUiState>> = _uiState.asStateFlow()

    @Volatile private var originalPersonaBlock: String = ""
    @Volatile private var originalHumanBlock: String = ""

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val agent = agentRepository.getAgent(agentId).last()
                val persona = agent.blocks.find { it.label == "persona" }?.value ?: ""
                val human = agent.blocks.find { it.label == "human" }?.value ?: ""
                originalPersonaBlock = persona
                originalHumanBlock = human
                _uiState.value = UiState.Success(
                    AgentSettingsUiState(
                        agent = agent,
                        agentType = agent.agentType ?: "",
                        contextWindow = agent.contextWindowLimit ?: agent.llmConfig?.contextWindow ?: 0,
                        temperature = agent.modelSettings?.temperature?.toFloat() ?: agent.llmConfig?.temperature?.toFloat() ?: 0.7f,
                        maxTokens = agent.modelSettings?.maxOutputTokens ?: agent.llmConfig?.maxTokens ?: 2000,
                        parallelToolCalls = agent.modelSettings?.parallelToolCalls ?: agent.llmConfig?.parallelToolCalls ?: true,
                        personaBlock = persona,
                        humanBlock = human,
                        systemPrompt = agent.system ?: "",
                        enableSleeptime = agent.enableSleeptime ?: false,
                        tools = agent.tools,
                        secrets = agent.secrets.associate { it.key to (it.value ?: "") },
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

    fun saveSettings(onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val state = (_uiState.value as? UiState.Success)?.data ?: return@launch
                val updatedAgent = agentRepository.updateAgent(
                    agentId,
                    AgentUpdateParams(
                        system = state.systemPrompt,
                        enableSleeptime = state.enableSleeptime,
                        modelSettings = ModelSettings(
                            temperature = state.temperature.toDouble(),
                            maxOutputTokens = state.maxTokens,
                            parallelToolCalls = state.parallelToolCalls,
                            providerType = state.agent?.modelSettings?.providerType,
                        ),
                    )
                )
                if (state.personaBlock != originalPersonaBlock) {
                    blockRepository.updateAgentBlock(agentId, "persona", BlockUpdateParams(value = state.personaBlock))
                    originalPersonaBlock = state.personaBlock
                }
                if (state.humanBlock != originalHumanBlock) {
                    blockRepository.updateAgentBlock(agentId, "human", BlockUpdateParams(value = state.humanBlock))
                    originalHumanBlock = state.humanBlock
                }
                _uiState.value = UiState.Success(state.copy(agent = updatedAgent))
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to save settings")
            }
        }
    }

    fun resetMessages(onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                messageRepository.resetMessages(agentId)
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to reset messages")
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
