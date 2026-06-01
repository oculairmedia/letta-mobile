package com.letta.mobile.feature.editagent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import javax.inject.Inject

@HiltViewModel
internal class EditAgentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val agentRepository: IAgentRepository,
    private val blockRepository: IBlockRepository,
    private val messageRepository: MessageRepository,
    private val modelRepository: IModelRepository,
    private val toolRepository: IToolRepository,
    private val settingsRepository: ISettingsRepository,
) : ViewModel() {

    private val agentId: String = requireNotNull(savedStateHandle.get<String>("agentId")) {
        "Missing agentId in EditAgentViewModel navigation arguments"
    }

    private val _uiState = MutableStateFlow<UiState<EditAgentUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<EditAgentUiState>> = _uiState.asStateFlow()

    val llmModels: StateFlow<List<LlmModel>> = modelRepository.llmModels
    val embeddingModels: StateFlow<List<com.letta.mobile.data.model.EmbeddingModel>> = modelRepository.embeddingModels

    @Volatile private var originalBlocks: Map<String, EditableBlock> = emptyMap()
    @Volatile private var originalEmbedding: String = ""
    @Volatile private var originalProviderType: String = ""

    // letta-mobile-rnyg: nullable rather than lateinit — the loadAgent flow
    // assigns this on its success path, so any delegated action invoked
    // before initial load completes (or while a retry is in flight) would
    // otherwise throw UninitializedPropertyAccessException. Surface a UI
    // error instead.
    @Volatile private var useCases: EditAgentUseCases? = null

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
                val agent = agentRepository.getAgent(AgentId(agentId)).first()
                val editableBlocks = agent.blocks.map { block ->
                    EditableBlock(
                        id = block.id.value,
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
                val availableBlocks = runCatching { blockRepository.listAllBlocks() }
                    .onFailure { android.util.Log.w("EditAgentVM", "Failed to load available blocks", it) }
                    .getOrDefault(emptyList())
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
                val normalizedProviderType = EditAgentUseCases.normalizeModelSettingsProviderType(
                    providerType = resolvedProviderType,
                    modelHandle = agent.model ?: agent.llmConfig?.handle ?: agent.llmConfig?.model,
                ).orEmpty()
                val compactionSettings = agent.compactionSettings
                val modelSettings = agent.modelSettings
                originalProviderType = normalizedProviderType
                useCases = EditAgentUseCases(
                    agentId = agentId,
                    agentRepository = agentRepository,
                    blockRepository = blockRepository,
                    messageRepository = messageRepository,
                    settingsRepository = settingsRepository,
                    uiState = _uiState,
                    originalBlocks = originalBlocks,
                    originalEmbedding = originalEmbedding,
                    originalProviderType = originalProviderType,
                )
                _uiState.value = UiState.Success(
                    EditAgentUiState(
                        agent = agent,
                        agentId = agent.id.value,
                        name = agent.name,
                        description = agent.description ?: "",
                        model = agent.model ?: "",
                        embedding = resolvedEmbedding,
                        blocks = editableBlocks.toImmutableList(),
                        systemPrompt = agent.system ?: "",
                        tags = agent.tags.toImmutableList(),
                        attachedTools = agent.tools.toImmutableList(),
                        availableTools = availableTools.toImmutableList(),
                        availableBlocks = availableBlocks.toImmutableList(),
                        toolRulesJson = agent.toolRules
                            .takeIf { it.isNotEmpty() }
                            ?.let { JsonArray(it).toSettingsJson() }
                            .orEmpty(),
                        agentSecrets = agent.secrets.toEditableEnvironmentVariables(),
                        toolEnvironmentVariables = agent.toolExecEnvironmentVariables.toEditableEnvironmentVariables(),
                        providerType = normalizedProviderType,
                        temperature = modelSettings?.temperature?.toFloat() ?: agent.llmConfig?.temperature?.toFloat() ?: 1.0f,
                        maxOutputTokens = modelSettings?.maxOutputTokens ?: agent.llmConfig?.maxTokens ?: 4096,
                        parallelToolCalls = modelSettings?.parallelToolCalls ?: agent.llmConfig?.parallelToolCalls ?: true,
                        modelProviderName = modelSettings?.providerName ?: agent.llmConfig?.providerName.orEmpty(),
                        modelProviderCategory = modelSettings?.providerCategory ?: agent.llmConfig?.providerCategory.orEmpty(),
                        modelEnableReasoner = modelSettings?.enableReasoner ?: agent.llmConfig?.enableReasoner ?: false,
                        modelReasoningEffort = modelSettings?.reasoningEffort ?: agent.llmConfig?.reasoningEffort.orEmpty(),
                        modelMaxReasoningTokens = (modelSettings?.maxReasoningTokens ?: agent.llmConfig?.maxReasoningTokens)
                            ?.toString()
                            .orEmpty(),
                        modelReasoningJson = modelSettings?.reasoning?.toSettingsJson().orEmpty(),
                        modelFrequencyPenalty = (modelSettings?.frequencyPenalty ?: agent.llmConfig?.frequencyPenalty)
                            ?.toString()
                            .orEmpty(),
                        modelVerbosity = modelSettings?.verbosity ?: agent.llmConfig?.verbosity.orEmpty(),
                        modelStrictToolCalling = modelSettings?.strict ?: false,
                        modelResponseFormatJson = (modelSettings?.responseFormat ?: agent.responseFormat)
                            ?.toSettingsJson()
                            .orEmpty(),
                        modelResponseSchemaJson = modelSettings?.responseSchema?.toSettingsJson().orEmpty(),
                        modelThinkingConfigJson = modelSettings?.thinkingConfig?.toSettingsJson().orEmpty(),
                        modelPutInnerThoughtsInKwargs = modelSettings?.putInnerThoughtsInKwargs
                            ?: agent.llmConfig?.putInnerThoughtsInKwargs
                            ?: false,
                        modelToolCallParser = modelSettings?.toolCallParser.orEmpty(),
                        modelAnthropicEffort = modelSettings?.effort.orEmpty(),
                        contextWindow = agent.contextWindowLimit ?: agent.llmConfig?.contextWindow ?: 0,
                        enableSleeptime = agent.enableSleeptime ?: false,
                        agentType = agent.agentType ?: "",
                        embeddingDim = agent.embeddingConfig?.embeddingDim,
                        embeddingChunkSize = agent.embeddingConfig?.embeddingChunkSize,
                        summarizationPrompt = compactionSettings?.prompt.orEmpty(),
                        compactionClipChars = compactionSettings?.clipChars ?: 50_000,
                        slidingWindowPercentage = compactionSettings
                            ?.slidingWindowPercentage
                            ?.toFloat()
                            ?.coerceIn(0f, 1f)
                            ?: 0.3f,
                        promptAcknowledgement = compactionSettings?.promptAcknowledgement ?: false,
                        compactionMode = compactionSettings?.mode ?: "sliding_window",
                        compactionModel = compactionSettings?.model.orEmpty(),
                        compactionModelSettingsJson = compactionSettings
                            ?.modelSettings
                            ?.toSettingsJson()
                            .orEmpty(),
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
                EditAgentUseCases.normalizeModelSettingsProviderType(
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
                blockRepository.attachBlock(agentId, block.id.value)
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

    fun updateModelProviderName(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelProviderName = value))
    }

    fun updateModelProviderCategory(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelProviderCategory = value))
    }

    fun updateModelEnableReasoner(value: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelEnableReasoner = value))
    }

    fun updateModelReasoningEffort(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelReasoningEffort = value))
    }

    fun updateModelMaxReasoningTokens(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelMaxReasoningTokens = value))
    }

    fun updateModelReasoningJson(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelReasoningJson = value))
    }

    fun updateModelFrequencyPenalty(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelFrequencyPenalty = value))
    }

    fun updateModelVerbosity(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelVerbosity = value))
    }

    fun updateModelStrictToolCalling(value: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelStrictToolCalling = value))
    }

    fun updateModelResponseFormatJson(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelResponseFormatJson = value))
    }

    fun updateModelResponseSchemaJson(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelResponseSchemaJson = value))
    }

    fun updateModelThinkingConfigJson(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelThinkingConfigJson = value))
    }

    fun updateModelPutInnerThoughtsInKwargs(value: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelPutInnerThoughtsInKwargs = value))
    }

    fun updateModelToolCallParser(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelToolCallParser = value))
    }

    fun updateModelAnthropicEffort(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(modelAnthropicEffort = value))
    }

    fun addAgentSecret() {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            currentState.copy(agentSecrets = (currentState.agentSecrets + EditableAgentEnvironmentVariable()).toImmutableList())
        )
    }

    fun updateAgentSecretKey(index: Int, value: String) {
        updateAgentSecret(index) { it.copy(key = value) }
    }

    fun updateAgentSecretValue(index: Int, value: String) {
        updateAgentSecret(index) { it.copy(value = value) }
    }

    fun removeAgentSecret(index: Int) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        if (index !in currentState.agentSecrets.indices) return
        _uiState.value = UiState.Success(
            currentState.copy(agentSecrets = currentState.agentSecrets.filterIndexed { i, _ -> i != index }.toImmutableList())
        )
    }

    private fun updateAgentSecret(index: Int, transform: (EditableAgentEnvironmentVariable) -> EditableAgentEnvironmentVariable) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        if (index !in currentState.agentSecrets.indices) return
        _uiState.value = UiState.Success(
            currentState.copy(
                agentSecrets = currentState.agentSecrets
                    .mapIndexed { i, variable -> if (i == index) transform(variable) else variable }
                    .toImmutableList()
            )
        )
    }

    fun addToolEnvironmentVariable() {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            currentState.copy(toolEnvironmentVariables = (currentState.toolEnvironmentVariables + EditableAgentEnvironmentVariable()).toImmutableList())
        )
    }

    fun updateToolEnvironmentVariableKey(index: Int, value: String) {
        updateToolEnvironmentVariable(index) { it.copy(key = value) }
    }

    fun updateToolEnvironmentVariableValue(index: Int, value: String) {
        updateToolEnvironmentVariable(index) { it.copy(value = value) }
    }

    fun removeToolEnvironmentVariable(index: Int) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        if (index !in currentState.toolEnvironmentVariables.indices) return
        _uiState.value = UiState.Success(
            currentState.copy(toolEnvironmentVariables = currentState.toolEnvironmentVariables.filterIndexed { i, _ -> i != index }.toImmutableList())
        )
    }

    private fun updateToolEnvironmentVariable(index: Int, transform: (EditableAgentEnvironmentVariable) -> EditableAgentEnvironmentVariable) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        if (index !in currentState.toolEnvironmentVariables.indices) return
        _uiState.value = UiState.Success(
            currentState.copy(
                toolEnvironmentVariables = currentState.toolEnvironmentVariables
                    .mapIndexed { i, variable -> if (i == index) transform(variable) else variable }
                    .toImmutableList()
            )
        )
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

    fun updateSummarizationPrompt(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(summarizationPrompt = value))
    }

    fun updateCompactionClipChars(value: Int) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(compactionClipChars = value.coerceAtLeast(1)))
    }

    fun updateSlidingWindowPercentage(value: Float) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(slidingWindowPercentage = value.coerceIn(0f, 1f)))
    }

    fun updatePromptAcknowledgement(value: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(promptAcknowledgement = value))
    }

    fun updateCompactionMode(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(compactionMode = value.ifBlank { "sliding_window" }))
    }

    fun updateCompactionModel(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(compactionModel = value))
    }

    fun updateCompactionModelSettingsJson(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(compactionModelSettingsJson = value))
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

    fun updateToolRulesJson(value: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(toolRulesJson = value))
    }

    private fun requireUseCasesOrError(): EditAgentUseCases? {
        val cases = useCases
        if (cases == null) {
            _uiState.value = UiState.Error("Agent is still loading. Please try again.")
        }
        return cases
    }

    fun saveAgent(onSuccess: () -> Unit) {
        viewModelScope.launch {
            requireUseCasesOrError()?.saveAgent(onSuccess)
        }
    }

    fun exportAgent(onResult: (String) -> Unit) {
        viewModelScope.launch {
            requireUseCasesOrError()?.exportAgent(onResult)
        }
    }

    fun cloneAgent(
        cloneName: String?,
        overrideExistingTools: Boolean,
        stripMessages: Boolean,
        onSuccess: (ImportedAgentsResponse) -> Unit,
    ) {
        viewModelScope.launch {
            requireUseCasesOrError()?.cloneAgent(cloneName, overrideExistingTools, stripMessages, onSuccess)
        }
    }

    fun resetMessages(onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            requireUseCasesOrError()?.resetMessages(onSuccess)
        }
    }

    fun deleteAgent(onSuccess: () -> Unit) {
        viewModelScope.launch {
            requireUseCasesOrError()?.deleteAgent(onSuccess)
        }
    }

    private fun JsonElement.toSettingsJson(): String {
        return kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), this)
    }
}
