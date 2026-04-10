package com.letta.mobile.ui.screens.editagent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.ModelRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class EditableBlock(
    val id: String,
    val label: String,
    val value: String,
    val description: String? = null,
    val limit: Int? = null,
    val isTemplate: Boolean = false,
    val readOnly: Boolean = false,
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
    val attachedTools: List<Tool> = emptyList(),
    val availableTools: List<Tool> = emptyList(),
    val providerType: String = "",
    val temperature: Float = 1.0f,
    val maxOutputTokens: Int = 4096,
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
    private val toolRepository: ToolRepository,
) : ViewModel() {

    private val agentId: String = savedStateHandle.get<String>("agentId") ?: ""

    private val _uiState = MutableStateFlow<UiState<EditAgentUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<EditAgentUiState>> = _uiState.asStateFlow()

    val llmModels: StateFlow<List<LlmModel>> = modelRepository.llmModels
    val embeddingModels: StateFlow<List<com.letta.mobile.data.model.EmbeddingModel>> = modelRepository.embeddingModels

    @Volatile private var originalBlocks: Map<String, EditableBlock> = emptyMap()
    @Volatile private var originalEmbedding: String = ""

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
                val agent = agentRepository.getAgent(agentId).last()
                val editableBlocks = agent.blocks.map { block ->
                    EditableBlock(
                        id = block.id,
                        label = block.label ?: "",
                        value = block.value ?: "",
                        description = block.description,
                        limit = block.limit,
                        isTemplate = block.isTemplate ?: false,
                        readOnly = block.readOnly ?: false,
                    )
                }
                toolRepository.refreshTools()
                val availableTools = toolRepository.getTools().first()
                originalBlocks = editableBlocks.associateBy { it.label }
                val resolvedEmbedding = agent.embedding
                    ?: agent.embeddingConfig?.handle
                    ?: agent.embeddingConfig?.embeddingModel
                    ?: ""
                originalEmbedding = resolvedEmbedding
                _uiState.value = UiState.Success(
                    EditAgentUiState(
                        agent = agent,
                        agentId = agent.id,
                        name = agent.name,
                        description = agent.description ?: "",
                        model = agent.model ?: "",
                        embedding = resolvedEmbedding,
                        blocks = editableBlocks,
                        systemPrompt = agent.system ?: "",
                        tags = agent.tags,
                        attachedTools = agent.tools,
                        availableTools = availableTools,
                        providerType = agent.modelSettings?.providerType ?: agent.llmConfig?.modelEndpointType ?: "",
                        temperature = agent.modelSettings?.temperature?.toFloat() ?: agent.llmConfig?.temperature?.toFloat() ?: 1.0f,
                        maxOutputTokens = agent.modelSettings?.maxOutputTokens ?: agent.llmConfig?.maxTokens ?: 4096,
                        parallelToolCalls = agent.modelSettings?.parallelToolCalls ?: agent.llmConfig?.parallelToolCalls ?: true,
                        contextWindow = agent.contextWindowLimit ?: agent.llmConfig?.contextWindow ?: 0,
                        enableSleeptime = agent.enableSleeptime ?: false,
                        agentType = agent.agentType ?: "",
                        embeddingDim = agent.embeddingConfig?.embeddingDim,
                        embeddingChunkSize = agent.embeddingConfig?.embeddingChunkSize,
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

    fun updateBlockDescription(blockLabel: String, description: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(
            blocks = currentState.blocks.map {
                if (it.label == blockLabel) it.copy(description = description) else it
            }
        ))
    }

    fun updateBlockLimit(blockLabel: String, limit: Int?) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(
            blocks = currentState.blocks.map {
                if (it.label == blockLabel) it.copy(limit = limit) else it
            }
        ))
    }

    fun addBlock(label: String, value: String, description: String, limit: Int?) {
        viewModelScope.launch {
            try {
                val block = blockRepository.createBlock(
                    com.letta.mobile.data.model.BlockCreateParams(
                        label = label,
                        value = value,
                        description = description.ifBlank { null },
                        limit = limit,
                    )
                )
                blockRepository.attachBlock(agentId, block.id)
                loadAgent()
            } catch (e: Exception) {
                android.util.Log.w("EditAgentVM", "Failed to create block", e)
            }
        }
    }

    fun attachExistingBlock(blockId: String) {
        viewModelScope.launch {
            try {
                blockRepository.attachBlock(agentId, blockId)
                loadAgent()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to attach block")
            }
        }
    }

    fun deleteBlock(blockId: String) {
        viewModelScope.launch {
            try {
                blockRepository.detachBlock(agentId, blockId)
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

    fun updateProviderType(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(providerType = value))
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

    fun attachTool(toolId: String) {
        viewModelScope.launch {
            try {
                toolRepository.attachTool(agentId, toolId)
                loadAgent()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to attach tool")
            }
        }
    }

    fun detachTool(toolId: String) {
        viewModelScope.launch {
            try {
                toolRepository.detachTool(agentId, toolId)
                loadAgent()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to detach tool")
            }
        }
    }

    fun saveAgent(onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                val state = (_uiState.value as? UiState.Success)?.data ?: return@launch
                val embeddingChanged = state.embedding != originalEmbedding
                agentRepository.updateAgent(
                    agentId,
                    AgentUpdateParams(
                        name = state.name,
                        description = state.description,
                        model = state.model,
                        embedding = if (embeddingChanged) state.embedding.ifBlank { null } else null,
                        system = state.systemPrompt,
                        tags = state.tags,
                        enableSleeptime = state.enableSleeptime,
                        modelSettings = com.letta.mobile.data.model.ModelSettings(
                            providerType = state.providerType.ifBlank { null },
                            temperature = state.temperature.toDouble(),
                            maxOutputTokens = state.maxOutputTokens,
                            parallelToolCalls = state.parallelToolCalls,
                        ),
                    )
                )
                state.blocks.forEach { block ->
                    val original = originalBlocks[block.label]
                    val normalizedDescription = block.description?.ifBlank { null }
                    if (original == null ||
                        block.value != original.value ||
                        normalizedDescription != original.description?.ifBlank { null } ||
                        block.limit != original.limit
                    ) {
                        blockRepository.updateAgentBlock(
                            agentId,
                            block.label,
                            BlockUpdateParams(
                                value = block.value,
                                description = normalizedDescription,
                                limit = block.limit,
                            )
                        )
                    }
                }
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to save agent")
            }
        }
    }
}
