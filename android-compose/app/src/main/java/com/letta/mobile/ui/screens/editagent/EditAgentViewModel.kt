package com.letta.mobile.ui.screens.editagent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.ModelRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class EditableBlock(
    val id: String,
    val label: String,
    val value: String,
    val description: String? = null,
    val limit: Int? = null,
)

data class EditAgentUiState(
    val agent: Agent? = null,
    val agentId: String = "",
    val name: String = "",
    val description: String = "",
    val model: String = "",
    val embedding: String = "",
    val blocks: List<EditableBlock> = emptyList(),
    val systemPrompt: String = "",
    val tags: List<String> = emptyList(),
    val temperature: Float = 1.0f,
    val maxOutputTokens: Int = 4096,
    val enableMaxOutputTokens: Boolean = false,
    val parallelToolCalls: Boolean = true,
    val contextWindow: Int = 0,
    val enableSleeptime: Boolean = false,
    val agentType: String = "",
    val embeddingDim: Int? = null,
    val embeddingChunkSize: Int? = null,
)

@HiltViewModel
class EditAgentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val agentRepository: AgentRepository,
    private val blockRepository: BlockRepository,
    private val modelRepository: ModelRepository,
) : ViewModel() {

    private val agentId: String = savedStateHandle.get<String>("agentId") ?: ""

    private val _uiState = MutableStateFlow<UiState<EditAgentUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<EditAgentUiState>> = _uiState.asStateFlow()

    val llmModels: StateFlow<List<LlmModel>> = modelRepository.llmModels
    val embeddingModels: StateFlow<List<com.letta.mobile.data.model.EmbeddingModel>> = modelRepository.embeddingModels

    @Volatile private var originalPersonaBlock: String = ""
    @Volatile private var originalHumanBlock: String = ""

    init {
        loadAgent()
        loadModels()
    }

    fun loadModels() {
        viewModelScope.launch {
            try {
                modelRepository.refreshLlmModels()
                modelRepository.refreshEmbeddingModels()
            } catch (e: Exception) {
                android.util.Log.w("EditAgentVM", "Failed to load models", e)
            }
        }
    }

    fun loadAgent() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val agent = agentRepository.getAgent(agentId).first()
                val editableBlocks = agent.blocks?.map { block ->
                    EditableBlock(
                        id = block.id,
                        label = block.label,
                        value = block.value ?: "",
                        description = block.description,
                        limit = block.limit,
                    )
                } ?: emptyList()
                originalPersonaBlock = editableBlocks.find { it.label == "persona" }?.value ?: ""
                originalHumanBlock = editableBlocks.find { it.label == "human" }?.value ?: ""
                _uiState.value = UiState.Success(
                    EditAgentUiState(
                        agent = agent,
                        agentId = agent.id,
                        name = agent.name,
                        description = agent.description ?: "",
                        model = agent.model ?: "",
                        embedding = agent.embedding ?: "",
                        blocks = editableBlocks,
                        systemPrompt = agent.system ?: "",
                        tags = agent.tags ?: emptyList(),
                        temperature = agent.modelSettings?.temperature?.toFloat() ?: 1.0f,
                        maxOutputTokens = agent.modelSettings?.maxOutputTokens ?: 4096,
                        enableMaxOutputTokens = agent.modelSettings?.maxOutputTokens != null,
                        parallelToolCalls = agent.modelSettings?.parallelToolCalls ?: true,
                        contextWindow = agent.contextWindowLimit ?: agent.llmConfig?.contextWindow ?: 0,
                        enableSleeptime = agent.enableSleeptime ?: false,
                        agentType = agent.agentType ?: "",
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

    fun updateBlockValue(blockLabel: String, value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(
            blocks = currentState.blocks.map {
                if (it.label == blockLabel) it.copy(value = value) else it
            }
        ))
    }

    fun addBlock(label: String, value: String) {
        viewModelScope.launch {
            try {
                val block = blockRepository.createBlock(
                    com.letta.mobile.data.model.BlockCreateParams(label = label, value = value)
                )
                blockRepository.attachBlock(agentId, block.id)
                loadAgent()
            } catch (e: Exception) {
                android.util.Log.w("EditAgentVM", "Failed to create block", e)
            }
        }
    }

    fun deleteBlock(blockId: String) {
        viewModelScope.launch {
            try {
                blockRepository.detachBlock(agentId, blockId)
                blockRepository.deleteBlock(blockId)
                val currentState = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(currentState.copy(
                    blocks = currentState.blocks.filter { it.id != blockId }
                ))
            } catch (e: Exception) {
                android.util.Log.w("EditAgentVM", "Failed to delete block", e)
            }
        }
    }

    fun addTag(tag: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        if (tag.isBlank() || currentState.tags.contains(tag)) return
        _uiState.value = UiState.Success(currentState.copy(tags = currentState.tags + tag))
    }

    fun removeTag(tag: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(tags = currentState.tags - tag))
    }

    fun updateEmbedding(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(embedding = value))
        }
    }

    fun updateTemperature(value: Float) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(temperature = value.coerceIn(0f, 2f)))
        }
    }

    fun updateMaxOutputTokens(value: Int) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(maxOutputTokens = value.coerceAtLeast(1)))
        }
    }

    fun updateParallelToolCalls(value: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(parallelToolCalls = value))
        }
    }

    fun updateEnableSleeptime(value: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(enableSleeptime = value))
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
                        embedding = state.embedding.ifBlank { null },
                        system = state.systemPrompt,
                        tags = state.tags,
                        enableSleeptime = state.enableSleeptime,
                        modelSettings = com.letta.mobile.data.model.ModelSettings(
                            temperature = state.temperature.toDouble(),
                            maxOutputTokens = if (state.enableMaxOutputTokens) state.maxOutputTokens else null,
                            parallelToolCalls = state.parallelToolCalls,
                        ),
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
