package com.letta.mobile.ui.screens.editagent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.ModelRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.util.mapErrorToUserMessage
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.screens.settings.ClientModeConnectionState
import com.letta.mobile.ui.screens.settings.ClientModeConnectionTester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.last
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
    val blocks: ImmutableList<EditableBlock> = persistentListOf(),
    val systemPrompt: String = "",
    val tags: ImmutableList<String> = persistentListOf(),
    val attachedTools: ImmutableList<Tool> = persistentListOf(),
    val availableTools: ImmutableList<Tool> = persistentListOf(),
    val providerType: String = "",
    val temperature: Float = 1.0f,
    val maxOutputTokens: Int = 4096,
    val parallelToolCalls: Boolean = true,
    val contextWindow: Int = 0,
    val enableSleeptime: Boolean = false,
    val agentType: String = "",
    val embeddingDim: Int? = null,
    val embeddingChunkSize: Int? = null,
    val isCloning: Boolean = false,
    val clientModeEnabled: Boolean = false,
    val clientModeBaseUrl: String = "",
    val clientModeApiKey: String = "",
    val clientModeConnectionState: ClientModeConnectionState = ClientModeConnectionState.Idle,
) {
    typealias BlockState = EditableBlock
}

@HiltViewModel
class EditAgentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val agentRepository: AgentRepository,
    private val blockRepository: BlockRepository,
    private val messageRepository: MessageRepository,
    private val modelRepository: ModelRepository,
    private val toolRepository: ToolRepository,
    private val settingsRepository: SettingsRepository,
    private val clientModeConnectionTester: ClientModeConnectionTester,
) : ViewModel() {

    companion object {
        private val supportedModelSettingsProviderTypes = setOf(
            "openai",
            "sglang",
            "anthropic",
            "google_ai",
            "google_vertex",
            "azure",
            "xai",
            "zai",
            "groq",
            "deepseek",
            "together",
            "bedrock",
            "baseten",
            "openrouter",
            "chatgpt_oauth",
        )

        private fun normalizeModelSettingsProviderType(providerType: String?, modelHandle: String?): String? {
            val normalizedProviderType = providerType?.trim()?.lowercase().orEmpty()
            if (normalizedProviderType in supportedModelSettingsProviderTypes) {
                return normalizedProviderType
            }

            val handleProvider = modelHandle
                ?.substringBefore('/', missingDelimiterValue = "")
                ?.trim()
                ?.lowercase()
                .orEmpty()

            return handleProvider.takeIf { it in supportedModelSettingsProviderTypes }
        }
    }

    private val agentId: String = savedStateHandle.get<String>("agentId")!!

    private val _uiState = MutableStateFlow<UiState<EditAgentUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<EditAgentUiState>> = _uiState.asStateFlow()

    val llmModels: StateFlow<List<LlmModel>> = modelRepository.llmModels
    val embeddingModels: StateFlow<List<com.letta.mobile.data.model.EmbeddingModel>> = modelRepository.embeddingModels

    @Volatile private var originalBlocks: Map<String, EditableBlock> = emptyMap()
    @Volatile private var originalEmbedding: String = ""
    @Volatile private var originalProviderType: String = ""

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
                val availableTools = toolRepository.getTools().value
                originalBlocks = editableBlocks.associateBy { it.label }
                val resolvedEmbedding = agent.embedding
                    ?: agent.embeddingConfig?.handle
                    ?: agent.embeddingConfig?.embeddingModel
                    ?: ""
                originalEmbedding = resolvedEmbedding
                val resolvedProviderType = agent.modelSettings?.providerType
                    ?: agent.llmConfig?.modelEndpointType
                    ?: agent.llmConfig?.providerName
                    ?: ""
                val normalizedProviderType = normalizeModelSettingsProviderType(
                    providerType = resolvedProviderType,
                    modelHandle = agent.model ?: agent.llmConfig?.handle ?: agent.llmConfig?.model,
                ).orEmpty()
                val clientModeEnabled = settingsRepository.observeClientModeEnabled().first()
                val clientModeBaseUrl = settingsRepository.observeClientModeBaseUrl().first()
                val clientModeApiKey = settingsRepository.getClientModeApiKey().orEmpty()
                originalProviderType = normalizedProviderType
                _uiState.value = UiState.Success(
                    EditAgentUiState(
                        agent = agent,
                        agentId = agent.id,
                        name = agent.name,
                        description = agent.description ?: "",
                        model = agent.model ?: "",
                        embedding = resolvedEmbedding,
                        blocks = editableBlocks.toImmutableList(),
                        systemPrompt = agent.system ?: "",
                        tags = agent.tags.toImmutableList(),
                        attachedTools = agent.tools.toImmutableList(),
                        availableTools = availableTools.toImmutableList(),
                        providerType = normalizedProviderType,
                        temperature = agent.modelSettings?.temperature?.toFloat() ?: agent.llmConfig?.temperature?.toFloat() ?: 1.0f,
                        maxOutputTokens = agent.modelSettings?.maxOutputTokens ?: agent.llmConfig?.maxTokens ?: 4096,
                        parallelToolCalls = agent.modelSettings?.parallelToolCalls ?: agent.llmConfig?.parallelToolCalls ?: true,
                        contextWindow = agent.contextWindowLimit ?: agent.llmConfig?.contextWindow ?: 0,
                        enableSleeptime = agent.enableSleeptime ?: false,
                        agentType = agent.agentType ?: "",
                        embeddingDim = agent.embeddingConfig?.embeddingDim,
                        embeddingChunkSize = agent.embeddingConfig?.embeddingChunkSize,
                        clientModeEnabled = clientModeEnabled,
                        clientModeBaseUrl = clientModeBaseUrl,
                        clientModeApiKey = clientModeApiKey,
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
            val selectedModel = llmModels.value.firstOrNull { model ->
                model.handle.equals(value, ignoreCase = true) ||
                    model.name.equals(value, ignoreCase = true) ||
                    model.displayName.equals(value, ignoreCase = true)
            }
            val normalizedProviderType = selectedModel?.let { model ->
                normalizeModelSettingsProviderType(
                    providerType = model.providerType,
                    modelHandle = model.handle ?: value,
                )
            }
            val selectedContextWindow = selectedModel?.contextWindow?.takeIf { it > 0 }
            _uiState.value = UiState.Success(
                currentState.copy(
                    model = selectedModel?.handle ?: value,
                    providerType = normalizedProviderType.orEmpty(),
                    contextWindow = selectedContextWindow
                        ?.let { maxContextWindow -> currentState.contextWindow.coerceIn(0, maxContextWindow) }
                        ?: currentState.contextWindow,
                )
            )
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
            }.toImmutableList()
        ))
    }

    fun updateBlockDescription(blockLabel: String, description: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(
            blocks = currentState.blocks.map {
                if (it.label == blockLabel) it.copy(description = description) else it
            }.toImmutableList()
        ))
    }

    fun updateBlockLimit(blockLabel: String, limit: Int?) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(
            blocks = currentState.blocks.map {
                if (it.label == blockLabel) it.copy(limit = limit) else it
            }.toImmutableList()
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

    fun attachExistingBlocks(blockIds: List<String>) {
        viewModelScope.launch {
            try {
                blockIds.forEach { blockRepository.attachBlock(agentId, it) }
                loadAgent()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to attach blocks")
            }
        }
    }

    fun deleteBlock(blockId: String) {
        viewModelScope.launch {
            try {
                blockRepository.detachBlock(agentId, blockId)
                val currentState = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(currentState.copy(
                    blocks = currentState.blocks.filter { it.id != blockId }.toImmutableList()
                ))
            } catch (e: Exception) {
                android.util.Log.w("EditAgentVM", "Failed to delete block", e)
            }
        }
    }

    fun addTag(tag: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        if (tag.isBlank() || currentState.tags.contains(tag)) return
        _uiState.value = UiState.Success(currentState.copy(tags = (currentState.tags + tag).toImmutableList()))
    }

    fun removeTag(tag: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(tags = (currentState.tags - tag).toImmutableList()))
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

    fun updateContextWindow(value: Int) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(contextWindow = value.coerceAtLeast(0)))
        }
    }

    fun updateEnableSleeptime(value: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(enableSleeptime = value))
        }
    }

    fun updateClientModeEnabled(value: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            currentState.copy(
                clientModeEnabled = value,
                clientModeConnectionState = ClientModeConnectionState.Idle,
            )
        )
    }

    fun updateClientModeBaseUrl(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            currentState.copy(
                clientModeBaseUrl = value,
                clientModeConnectionState = ClientModeConnectionState.Idle,
            )
        )
    }

    fun updateClientModeApiKey(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            currentState.copy(
                clientModeApiKey = value,
                clientModeConnectionState = ClientModeConnectionState.Idle,
            )
        )
    }

    fun testClientModeConnection() {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        val baseUrl = currentState.clientModeBaseUrl.trim()
        val apiKey = currentState.clientModeApiKey.trim().ifBlank { null }

        if (baseUrl.isBlank()) {
            _uiState.value = UiState.Success(
                currentState.copy(
                    clientModeConnectionState = ClientModeConnectionState.Failure(
                        message = "Enter a server URL first",
                        testedAtMillis = System.currentTimeMillis(),
                    )
                )
            )
            return
        }

        viewModelScope.launch {
            val startingState = (_uiState.value as? UiState.Success)?.data ?: return@launch
            _uiState.value = UiState.Success(
                startingState.copy(clientModeConnectionState = ClientModeConnectionState.Testing)
            )

            val result = clientModeConnectionTester.test(baseUrl = baseUrl, apiKey = apiKey)
            val finishedState = (_uiState.value as? UiState.Success)?.data ?: return@launch
            val timestamp = System.currentTimeMillis()
            _uiState.value = UiState.Success(
                finishedState.copy(
                    clientModeConnectionState = result.fold(
                        onSuccess = { ClientModeConnectionState.Success(timestamp) },
                        onFailure = {
                            val error = it as? Exception ?: RuntimeException(it.message ?: "Connection test failed", it)
                            ClientModeConnectionState.Failure(
                                message = mapErrorToUserMessage(error, "Connection test failed"),
                                testedAtMillis = timestamp,
                            )
                        },
                    )
                )
            )

            delay(5_000)

            val latestState = (_uiState.value as? UiState.Success)?.data ?: return@launch
            if (latestState.clientModeConnectionState !is ClientModeConnectionState.Testing) {
                _uiState.value = UiState.Success(
                    latestState.copy(clientModeConnectionState = ClientModeConnectionState.Idle)
                )
            }
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

    fun attachTools(toolIds: List<String>) {
        viewModelScope.launch {
            try {
                toolIds.forEach { toolRepository.attachTool(agentId, it) }
                loadAgent()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to attach tools")
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
                val resolvedProviderType = normalizeModelSettingsProviderType(
                    providerType = state.providerType,
                    modelHandle = state.model,
                )
                    ?: normalizeModelSettingsProviderType(
                        providerType = originalProviderType,
                        modelHandle = state.model,
                    )

                if (resolvedProviderType == null) {
                    _uiState.value = UiState.Error(
                        "Couldn't determine a supported provider type for the selected model. Please re-select the model and try again."
                    )
                    return@launch
                }
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
                        contextWindowLimit = state.contextWindow.takeIf { it > 0 },
                        modelSettings = com.letta.mobile.data.model.ModelSettings(
                            providerType = resolvedProviderType,
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
                settingsRepository.setClientModeEnabled(state.clientModeEnabled)
                settingsRepository.setClientModeBaseUrl(state.clientModeBaseUrl.trim())
                settingsRepository.setClientModeApiKey(state.clientModeApiKey.trim().ifBlank { null })
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to save agent")
            }
        }
    }

    fun exportAgent(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val data = agentRepository.exportAgent(agentId)
                onResult(data)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to export agent"))
            }
        }
    }

    fun cloneAgent(
        cloneName: String?,
        overrideExistingTools: Boolean,
        stripMessages: Boolean,
        onSuccess: (ImportedAgentsResponse) -> Unit,
    ) {
        viewModelScope.launch {
            val state = (_uiState.value as? UiState.Success)?.data ?: return@launch
            _uiState.value = UiState.Success(state.copy(isCloning = true))
            try {
                val exportData = agentRepository.exportAgent(agentId)
                val response = agentRepository.importAgent(
                    fileName = "${state.name.ifBlank { "agent" }}.json",
                    fileBytes = exportData.encodeToByteArray(),
                    overrideName = cloneName?.takeIf { it.isNotBlank() },
                    overrideExistingTools = overrideExistingTools,
                    stripMessages = stripMessages,
                )
                _uiState.value = UiState.Success(state.copy(isCloning = false))
                onSuccess(response)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to clone agent"))
            }
        }
    }

    fun resetMessages(onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                messageRepository.resetMessages(agentId)
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to reset messages"))
            }
        }
    }

    fun deleteAgent(onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                agentRepository.deleteAgent(agentId)
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to delete agent"))
            }
        }
    }
}
