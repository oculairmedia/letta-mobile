package com.letta.mobile.feature.editagent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val embeddingModels: StateFlow<List<EmbeddingModel>> = modelRepository.embeddingModels

    private val state = EditAgentViewModelState(_uiState)
    private val agentLoader = EditAgentAgentLoader(
        agentId = agentId,
        agentRepository = agentRepository,
        blockRepository = blockRepository,
        toolRepository = toolRepository,
    )
    private val blockEditor = EditAgentBlockEditor(
        deps = EditAgentBlockEditorDeps(
            agentId = agentId,
            blockRepository = blockRepository,
            state = state,
            scope = viewModelScope,
            reload = { loadAgentSnapshot() },
        ),
    )
    private val environmentEditor = EditAgentEnvironmentEditor(state)
    private val toolAttachment = EditAgentToolAttachment(
        agentId = agentId,
        toolRepository = toolRepository,
        state = state,
        scope = viewModelScope,
        reload = { loadAgentSnapshot() },
    )
    private val modelSelection = EditAgentModelSelection(
        state = state,
        llmModels = { llmModels.value },
    )

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
            state.setLoading()
            try {
                applyLoadSnapshot(agentLoader.load(llmModels.value))
            } catch (e: Exception) {
                state.setError(e.message ?: "Failed to load agent")
            }
        }
    }

    private suspend fun loadAgentSnapshot() {
        applyLoadSnapshot(agentLoader.load(llmModels.value))
    }

    private fun applyLoadSnapshot(snapshot: EditAgentLoadSnapshot) {
        originalBlocks = snapshot.originalBlocks
        originalEmbedding = snapshot.originalEmbedding
        originalProviderType = snapshot.originalProviderType
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
            servedModelIds = { llmModels.value.mapNotNull { model -> model.handle ?: model.name.ifBlank { model.id } } },
        )
        state.setSuccess(snapshot.uiState)
    }

    fun updateName(value: String) = state.updateField { copy(name = value) }

    fun updateDescription(value: String) = state.updateField { copy(description = value) }

    fun updateModel(value: String) = modelSelection.updateModel(value)

    fun updateSystemPrompt(value: String) = state.updateField { copy(systemPrompt = value) }

    fun updateBlockValue(blockLabel: String, value: String) =
        blockEditor.updateBlockValue(BlockValueUpdate(EditableBlockLabel(blockLabel), value))

    fun updateBlockDescription(blockLabel: String, description: String) =
        blockEditor.updateBlockDescription(BlockDescriptionUpdate(EditableBlockLabel(blockLabel), description))

    fun updateBlockLimit(blockLabel: String, limit: Int?) =
        blockEditor.updateBlockLimit(BlockLimitUpdate(EditableBlockLabel(blockLabel), limit))

    fun addBlock(draft: NewBlockDraft) = blockEditor.addBlock(draft)

    fun attachExistingBlock(blockId: String) = blockEditor.attachExistingBlock(AttachedBlockId(blockId))

    fun attachExistingBlocks(blockIds: List<String>) =
        blockEditor.attachExistingBlocks(BlockAttachRequest(blockIds.map(::AttachedBlockId)))

    fun deleteBlock(blockId: String) = blockEditor.deleteBlock(AttachedBlockId(blockId))

    fun addTag(tag: String) {
        val currentState = state.successData() ?: return
        if (tag.isBlank() || currentState.tags.contains(tag)) return
        state.updateField { copy(tags = (tags + tag).toImmutableList()) }
    }

    fun removeTag(tag: String) = state.updateField { copy(tags = (tags - tag).toImmutableList()) }

    fun updateEmbedding(value: String) = state.updateField { copy(embedding = value) }

    fun updateTemperature(value: Float) = state.updateField { copy(temperature = value.coerceIn(0f, 2f)) }

    fun updateProviderType(value: String) = state.updateField { copy(providerType = value) }

    fun updateMaxOutputTokens(value: Int) = state.updateField { copy(maxOutputTokens = value.coerceAtLeast(1)) }

    fun updateParallelToolCalls(value: Boolean) = state.updateField { copy(parallelToolCalls = value) }

    fun updateModelProviderName(value: String) = state.updateField { copy(modelProviderName = value) }

    fun updateModelProviderCategory(value: String) = state.updateField { copy(modelProviderCategory = value) }

    fun updateModelEnableReasoner(value: Boolean) = state.updateField { copy(modelEnableReasoner = value) }

    fun updateModelReasoningEffort(value: String) = state.updateField { copy(modelReasoningEffort = value) }

    fun updateModelMaxReasoningTokens(value: String) = state.updateField { copy(modelMaxReasoningTokens = value) }

    fun updateModelReasoningJson(value: String) = state.updateField { copy(modelReasoningJson = value) }

    fun updateModelFrequencyPenalty(value: String) = state.updateField { copy(modelFrequencyPenalty = value) }

    fun updateModelVerbosity(value: String) = state.updateField { copy(modelVerbosity = value) }

    fun updateModelStrictToolCalling(value: Boolean) = state.updateField { copy(modelStrictToolCalling = value) }

    fun updateModelResponseFormatJson(value: String) = state.updateField { copy(modelResponseFormatJson = value) }

    fun updateModelResponseSchemaJson(value: String) = state.updateField { copy(modelResponseSchemaJson = value) }

    fun updateModelThinkingConfigJson(value: String) = state.updateField { copy(modelThinkingConfigJson = value) }

    fun updateModelPutInnerThoughtsInKwargs(value: Boolean) =
        state.updateField { copy(modelPutInnerThoughtsInKwargs = value) }

    fun updateModelToolCallParser(value: String) = state.updateField { copy(modelToolCallParser = value) }

    fun updateModelAnthropicEffort(value: String) = state.updateField { copy(modelAnthropicEffort = value) }

    fun addAgentSecret() = environmentEditor.add(EditAgentEnvironmentVariableTarget.AGENT_SECRETS)

    fun updateAgentSecretKey(index: Int, value: String) =
        environmentEditor.updateKey(EditAgentEnvironmentVariableTarget.AGENT_SECRETS, index, value)

    fun updateAgentSecretValue(index: Int, value: String) =
        environmentEditor.updateValue(EditAgentEnvironmentVariableTarget.AGENT_SECRETS, index, value)

    fun removeAgentSecret(index: Int) =
        environmentEditor.remove(EditAgentEnvironmentVariableTarget.AGENT_SECRETS, index)

    fun addToolEnvironmentVariable() = environmentEditor.add(EditAgentEnvironmentVariableTarget.TOOL_ENVIRONMENT)

    fun updateToolEnvironmentVariableKey(index: Int, value: String) =
        environmentEditor.updateKey(EditAgentEnvironmentVariableTarget.TOOL_ENVIRONMENT, index, value)

    fun updateToolEnvironmentVariableValue(index: Int, value: String) =
        environmentEditor.updateValue(EditAgentEnvironmentVariableTarget.TOOL_ENVIRONMENT, index, value)

    fun removeToolEnvironmentVariable(index: Int) =
        environmentEditor.remove(EditAgentEnvironmentVariableTarget.TOOL_ENVIRONMENT, index)

    fun updateContextWindow(value: Int) = state.updateField { copy(contextWindow = value.coerceAtLeast(0)) }

    fun updateEnableSleeptime(value: Boolean) = state.updateField { copy(enableSleeptime = value) }

    fun updateSummarizationPrompt(value: String) = state.updateField { copy(summarizationPrompt = value) }

    fun updateCompactionClipChars(value: Int) = state.updateField { copy(compactionClipChars = value.coerceAtLeast(1)) }

    fun updateSlidingWindowPercentage(value: Float) =
        state.updateField { copy(slidingWindowPercentage = value.coerceIn(0f, 1f)) }

    fun updatePromptAcknowledgement(value: Boolean) = state.updateField { copy(promptAcknowledgement = value) }

    fun updateCompactionMode(value: String) =
        state.updateField { copy(compactionMode = value.ifBlank { "sliding_window" }) }

    fun updateCompactionModel(value: String) = state.updateField { copy(compactionModel = value) }

    fun updateCompactionModelSettingsJson(value: String) =
        state.updateField { copy(compactionModelSettingsJson = value) }

    fun attachTool(toolId: String) = toolAttachment.attachTool(toolId)

    fun attachTools(toolIds: List<String>) = toolAttachment.attachTools(toolIds)

    fun detachTool(toolId: String) = toolAttachment.detachTool(toolId)

    fun updateToolRulesJson(value: String) = state.updateField { copy(toolRulesJson = value) }

    private fun requireUseCasesOrError(): EditAgentUseCases? {
        val cases = useCases
        if (cases == null) {
            state.setError("Agent is still loading. Please try again.")
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
}
