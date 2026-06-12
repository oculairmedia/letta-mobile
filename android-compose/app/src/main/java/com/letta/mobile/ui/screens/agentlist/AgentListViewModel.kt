package com.letta.mobile.ui.screens.agentlist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentRuntimeBinding
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.LocalAgentRuntimeMetadata
import com.letta.mobile.data.model.ModelSettings
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.runtime.local.EmbeddedLettaCodeModelSelection
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDownloadState
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

@androidx.compose.runtime.Immutable
enum class AgentCreateRuntimeOption {
    REMOTE,
    LOCAL_LETTACODE,
}

data class LocalLettaCodeCreateReadiness(
    val runtimeEnabled: Boolean = false,
    val modelSelected: Boolean = false,
    val modelDownloaded: Boolean = false,
    val activeConfigIsLocal: Boolean = false,
) {
    val ready: Boolean
        get() = runtimeEnabled && modelSelected && modelDownloaded && activeConfigIsLocal

    val setupMessage: String?
        get() = when {
            !runtimeEnabled -> "This build does not include the embedded Local LettaCode runtime."
            !activeConfigIsLocal -> "Enable Local LettaCode in Settings to create agents that run on this device."
            !modelDownloaded -> "Download or import a model in Settings before creating a local agent."
            !modelSelected -> "Choose which downloaded model Local LettaCode should use."
            else -> null
        }

    val setupActionLabel: String
        get() = when {
            !activeConfigIsLocal -> "Enable Local LettaCode"
            !modelDownloaded -> "Browse local models"
            !modelSelected -> "Choose model"
            else -> "Open Local LettaCode settings"
        }
}

data class AgentListUiState(
    val agents: ImmutableList<Agent> = persistentListOf(),
    val availableTools: ImmutableList<Tool> = persistentListOf(),
    val llmModels: ImmutableList<LlmModel> = persistentListOf(),
    val embeddingModels: ImmutableList<EmbeddingModel> = persistentListOf(),
    val localLettaCodeReadiness: LocalLettaCodeCreateReadiness = LocalLettaCodeCreateReadiness(),
    val favoriteAgentId: AgentId? = null,
    val pinnedAgentIds: Set<AgentId> = emptySet(),
    val searchQuery: String = "",
    val selectedTags: Set<String> = emptySet(),
    val isImporting: Boolean = false,
    val isLoading: Boolean = true,
    val isHydrating: Boolean = false,
    val isRefreshing: Boolean = false,
    val isCreating: Boolean = false,
    val error: String? = null,
    // Dialog state (hoisted from composable)
    val showCreateDialog: Boolean = false,
    val showImportDialog: Boolean = false,
    val isSearchExpanded: Boolean = false,
    val showGrid: Boolean = false,
    val pendingImportName: String? = null,
    val pendingImportOverrideTools: Boolean = true,
    val pendingImportStripMessages: Boolean = false,
)

@HiltViewModel
class AgentListViewModel @Inject constructor(
    private val agentRepository: IAgentRepository,
    private val settingsRepository: ISettingsRepository,
    private val toolRepository: IToolRepository,
    private val modelRepository: IModelRepository,
    private val embeddedRuntimeStatusProvider: EmbeddedLettaCodeRuntimeStatusProvider,
    private val embeddedModelRepository: EmbeddedModelRepository,
) : ViewModel() {
    companion object {
        private const val LIST_CACHE_TTL_MS = 30_000L
    }

        private data class TransientState(
            val searchQuery: String = "",
            val selectedTags: Set<String> = emptySet(),
            val isLoading: Boolean = true,
            val isHydrating: Boolean = false,
            val isRefreshing: Boolean = false,
            val isCreating: Boolean = false,
            val isImporting: Boolean = false,
            val error: String? = null,
            // Dialog state (hoisted from composable)
            val showCreateDialog: Boolean = false,
            val showImportDialog: Boolean = false,
            val isSearchExpanded: Boolean = false,
            val showGrid: Boolean = false,
            val pendingImportName: String? = null,
            val pendingImportOverrideTools: Boolean = true,
            val pendingImportStripMessages: Boolean = false,
            val shareNavigationConsumed: Boolean = false,
        )

    private val _transient = MutableStateFlow(
        TransientState(isLoading = agentRepository.agents.value.isEmpty())
    )

    /** Pre-combine models + transient into a single flow to stay within combine()'s 5-param limit. */
    private data class Overlay(
        val llm: List<LlmModel>,
        val emb: List<EmbeddingModel>,
        val localReadiness: LocalLettaCodeCreateReadiness,
        val activeConfigIsLocalRuntime: Boolean,
        val transient: TransientState,
    )

    private val overlay = combine(
        modelRepository.llmModels,
        modelRepository.embeddingModels,
        settingsRepository.activeConfig,
        embeddedModelRepository.catalog,
        _transient,
    ) { llm, emb, activeConfig, catalog, transient ->
        Overlay(
            llm = llm,
            emb = emb,
            localReadiness = activeConfig.localLettaCodeCreateReadiness(
                runtimeRunnable = embeddedRuntimeStatusProvider.status.runnable,
                downloadedModelHandles = catalog.downloadedModelHandles(),
            ),
            activeConfigIsLocalRuntime = AgentRuntimeBinding.isLocalRuntime(activeConfig),
            transient = transient,
        )
    }

    val uiState: StateFlow<AgentListUiState> = combine(
        agentRepository.agents,
        settingsRepository.favoriteAgentId,
        settingsRepository.getPinnedAgentIds(),
        toolRepository.getTools(),
        overlay,
    ) { agents, favId, pinnedIds, tools, overlay ->
        val displayAgents = if (overlay.activeConfigIsLocalRuntime) {
            agents.filter(AgentRuntimeBinding::isLocalBound)
        } else {
            agents
        }
        AgentListUiState(
            agents = displayAgents.toImmutableList(),
            availableTools = tools.toImmutableList(),
            llmModels = overlay.llm.toImmutableList(),
            embeddingModels = overlay.emb.toImmutableList(),
            localLettaCodeReadiness = overlay.localReadiness,
            favoriteAgentId = favId?.let { AgentId(it) },
            pinnedAgentIds = pinnedIds.map { AgentId(it) }.toSet(),
            searchQuery = overlay.transient.searchQuery,
            selectedTags = overlay.transient.selectedTags,
            isLoading = overlay.transient.isLoading && agents.isEmpty(),
            isHydrating = overlay.transient.isHydrating,
            isRefreshing = overlay.transient.isRefreshing,
            isCreating = overlay.transient.isCreating,
            isImporting = overlay.transient.isImporting,
            error = overlay.transient.error,
            showCreateDialog = overlay.transient.showCreateDialog,
            showImportDialog = overlay.transient.showImportDialog,
            isSearchExpanded = overlay.transient.isSearchExpanded,
            showGrid = overlay.transient.showGrid,
            pendingImportName = overlay.transient.pendingImportName,
            pendingImportOverrideTools = overlay.transient.pendingImportOverrideTools,
            pendingImportStripMessages = overlay.transient.pendingImportStripMessages,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AgentListUiState())

    init {
        loadAgents()
        loadAvailableTools()
        loadAvailableModels()
        // letta-mobile-ze5l: refetch on backend switch.
        viewModelScope.launch {
            settingsRepository.activeConfigChanges.collect { refresh() }
        }
    }

    fun loadAvailableTools() {
        viewModelScope.launch {
            try {
                toolRepository.refreshToolsIfStale(LIST_CACHE_TTL_MS)
            } catch (_: Exception) {
                Log.w("AgentListViewModel", "Failed to load available tools")
            }
        }
    }

    fun loadAvailableModels() {
        viewModelScope.launch {
            try {
                modelRepository.refreshLlmModels()
                modelRepository.refreshEmbeddingModels()
            } catch (_: Exception) {
                Log.w("AgentListViewModel", "Failed to load available models")
            }
        }
    }

    fun loadAgents() {
        viewModelScope.launch {
            if (agentRepository.agents.value.isEmpty()) {
                _transient.update { it.copy(isLoading = true, isHydrating = true, error = null) }
            } else {
                _transient.update { it.copy(isHydrating = true, error = null) }
            }
            try {
                if (settingsRepository.activeConfig.value.isLocalRuntimeConfig()) {
                    _transient.update { it.copy(isLoading = false, isHydrating = false) }
                    return@launch
                }
                val firstPageMarker = launch {
                    agentRepository.agents
                        .drop(if (agentRepository.agents.value.isEmpty()) 0 else 1)
                        .first { it.isNotEmpty() }
                        .let { agents ->
                            _transient.update { it.copy(isLoading = false) }
                            Log.i("AgentListViewModel", "AgentList first-page ready count=${agents.size}")
                            Log.i("AgentListViewModel", "AgentList hydrated count=${agents.size}")
                        }
                }
                agentRepository.refreshAgentsIfStale(LIST_CACHE_TTL_MS)
                firstPageMarker.cancel()
                _transient.update { it.copy(isLoading = false, isHydrating = false) }
                Log.i("AgentListViewModel", "AgentList hydrated count=${agentRepository.agents.value.size}")
            } catch (e: Exception) {
                Log.w("AgentListViewModel", "AgentList hydrate failed", e)
                _transient.update {
                    it.copy(
                        isLoading = false,
                        isHydrating = false,
                        error = if (agentRepository.agents.value.isEmpty()) {
                            e.message ?: "Failed to load agents"
                        } else null,
                    )
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _transient.update { it.copy(isRefreshing = true, isHydrating = true) }
            try {
                if (settingsRepository.activeConfig.value.isLocalRuntimeConfig()) {
                    _transient.update { it.copy(isRefreshing = false, isHydrating = false) }
                    return@launch
                }
                agentRepository.refreshAgents()
                _transient.update { it.copy(isRefreshing = false, isHydrating = false) }
            } catch (e: Exception) {
                _transient.update {
                    it.copy(
                        isRefreshing = false,
                        isHydrating = false,
                        error = e.message ?: "Failed to refresh",
                    )
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _transient.update { it.copy(searchQuery = query) }
    }

    fun getFilteredAgents(): List<Agent> {
        val state = uiState.value
        var result: List<Agent> = state.agents

        if (state.selectedTags.isNotEmpty()) {
            result = result.filter { agent ->
                state.selectedTags.all { tag -> tag in agent.tags }
            }
        }

        if (state.searchQuery.isNotBlank()) {
            val q = state.searchQuery.trim().lowercase()
            result = result.filter { agent ->
                agent.name.lowercase().contains(q) ||
                    (agent.description?.lowercase()?.contains(q) == true) ||
                    (agent.model?.lowercase()?.contains(q) == true) ||
                    agent.tags.any { it.lowercase().contains(q) }
            }
        }

        return result
    }

    fun getAllTags(): List<String> {
        return uiState.value.agents
            .flatMap { it.tags }
            .distinct()
            .sorted()
    }

    fun toggleTag(tag: String) {
        _transient.update { current ->
            val updated = if (tag in current.selectedTags) {
                current.selectedTags - tag
            } else {
                current.selectedTags + tag
            }
            current.copy(selectedTags = updated)
        }
    }

    fun clearTags() {
        _transient.update { it.copy(selectedTags = emptySet()) }
    }

    fun deleteAgent(agentId: AgentId, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                agentRepository.deleteAgent(agentId)
                if (uiState.value.favoriteAgentId == agentId) {
                    settingsRepository.setFavoriteAgentId(null)
                }
                onComplete()
            } catch (e: Exception) {
                _transient.update { it.copy(error = e.message ?: "Failed to delete agent") }
            }
        }
    }

    fun toggleFavorite(agentId: AgentId) {
        val current = uiState.value.favoriteAgentId
        val newFav = if (current == agentId) null else agentId.value
        settingsRepository.setFavoriteAgentId(newFav)
    }

    fun togglePinned(agentId: AgentId) {
        viewModelScope.launch {
            val isPinned = agentId in uiState.value.pinnedAgentIds
            settingsRepository.setAgentPinned(agentId.value, !isPinned)
        }
    }

    fun clearError() {
        _transient.update { it.copy(error = null) }
    }

    fun createAgent(
        params: AgentCreateParams,
        runtimeOption: AgentCreateRuntimeOption = AgentCreateRuntimeOption.REMOTE,
        onSuccess: (AgentId) -> Unit,
    ) {
        viewModelScope.launch {
            _transient.update { it.copy(isCreating = true, error = null) }
            try {
                val localCreate = runtimeOption == AgentCreateRuntimeOption.LOCAL_LETTACODE
                val createParams = when (runtimeOption) {
                    AgentCreateRuntimeOption.REMOTE -> params
                    AgentCreateRuntimeOption.LOCAL_LETTACODE -> {
                        val activeConfig = settingsRepository.activeConfig.value
                        val readiness = activeConfig.localLettaCodeCreateReadiness(
                            runtimeRunnable = embeddedRuntimeStatusProvider.status.runnable,
                            downloadedModelHandles = embeddedModelRepository.catalog.value.downloadedModelHandles(),
                        )
                        val setupMessage = readiness.setupMessage
                        if (setupMessage != null || activeConfig == null) {
                            _transient.update {
                                it.copy(isCreating = false, error = setupMessage ?: "Enable Local LettaCode in Settings before creating a local agent.")
                            }
                            return@launch
                        }
                        params.withLocalLettaCodeRuntimeBinding(activeConfig)
                    }
                }
                val agent = if (localCreate) {
                    agentRepository.createLocalAgent(createParams)
                } else {
                    agentRepository.createAgent(createParams)
                }
                _transient.update { it.copy(isCreating = false) }
                if (!localCreate) {
                    agentRepository.refreshAgents()
                }
                onSuccess(agent.id)
            } catch (e: Exception) {
                _transient.update {
                    it.copy(
                        isCreating = false,
                        error = mapErrorToUserMessage(e, "Failed to create agent"),
                    )
                }
            }
        }
    }

    fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String?,
        overrideExistingTools: Boolean,
        stripMessages: Boolean,
        onSuccess: (ImportedAgentsResponse) -> Unit,
    ) {
        viewModelScope.launch {
            _transient.update { it.copy(isImporting = true, error = null) }
            try {
                val response = agentRepository.importAgent(
                    fileName = fileName,
                    fileBytes = fileBytes,
                    overrideName = overrideName?.takeIf { it.isNotBlank() },
                    overrideExistingTools = overrideExistingTools,
                    stripMessages = stripMessages,
                )
                _transient.update { it.copy(isImporting = false) }
                onSuccess(response)
            } catch (e: Exception) {
                _transient.update {
                    it.copy(
                        isImporting = false,
                        error = mapErrorToUserMessage(e, "Failed to import agent"),
                    )
                }
            }
        }
    }

    // Dialog state management (hoisted from composable)
    fun showCreateDialog() {
        _transient.update { it.copy(showCreateDialog = true) }
    }

    fun hideCreateDialog() {
        _transient.update { it.copy(showCreateDialog = false) }
    }

    fun showImportDialog() {
        _transient.update { it.copy(showImportDialog = true) }
    }

    fun hideImportDialog() {
        _transient.update { it.copy(showImportDialog = false) }
    }

    fun setSearchExpanded(expanded: Boolean) {
        _transient.update { it.copy(isSearchExpanded = expanded) }
    }

    fun setShowGrid(show: Boolean) {
        _transient.update { it.copy(showGrid = show) }
    }

    fun setPendingImportName(name: String?) {
        _transient.update { it.copy(pendingImportName = name) }
    }

    fun setPendingImportOverrideTools(override: Boolean) {
        _transient.update { it.copy(pendingImportOverrideTools = override) }
    }

    fun setPendingImportStripMessages(strip: Boolean) {
        _transient.update { it.copy(pendingImportStripMessages = strip) }
    }

    fun setShareNavigationConsumed(consumed: Boolean) {
        _transient.update { it.copy(shareNavigationConsumed = consumed) }
    }
}
internal fun LettaConfig?.localLettaCodeCreateReadiness(
    runtimeRunnable: Boolean,
    downloadedModelHandles: Set<String>,
): LocalLettaCodeCreateReadiness {
    val config = this
    val activeConfigIsLocal = AgentRuntimeBinding.isLocalRuntime(config)
    val selectedModel = config?.selectedLocalModelHandle()
    val selectedImportedPath = config?.localModelPath?.trim()?.takeIf { it.isNotBlank() }
    val modelDownloaded = selectedImportedPath != null || (selectedModel != null && selectedModel in downloadedModelHandles)
    return LocalLettaCodeCreateReadiness(
        runtimeEnabled = runtimeRunnable,
        modelSelected = selectedModel != null && modelDownloaded,
        modelDownloaded = modelDownloaded,
        activeConfigIsLocal = activeConfigIsLocal,
    )
}

private fun LettaConfig?.isLocalRuntimeConfig(): Boolean = AgentRuntimeBinding.isLocalRuntime(this)

private fun LettaConfig.selectedLocalModelHandle(): String? = localModelHandle
    ?.trim()
    ?.takeIf { it.isNotBlank() && it != EmbeddedLettaCodeModelSelection.DEFAULT_MODEL_HANDLE }

internal fun List<com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogItem>.downloadedModelHandles(): Set<String> =
    mapNotNull { item ->
        item.entry.modelId.takeIf { item.state is EmbeddedModelDownloadState.Downloaded }
    }.toSet()

private fun AgentCreateParams.withLocalLettaCodeRuntimeBinding(config: LettaConfig): AgentCreateParams {
    val selection = EmbeddedLettaCodeModelSelection.from(config)
    val localMetadata = mapOf(
        LocalAgentRuntimeMetadata.RuntimeKey to JsonPrimitive(LocalAgentRuntimeMetadata.LocalLettaCodeRuntime),
        LocalAgentRuntimeMetadata.RuntimeProviderKey to JsonPrimitive(LocalAgentRuntimeMetadata.LocalLettaCodeRuntime),
        LocalAgentRuntimeMetadata.RuntimeIdKey to JsonPrimitive("${LocalAgentRuntimeMetadata.LocalLettaCodeRuntime}:${config.id}"),
        LocalAgentRuntimeMetadata.LocalModelHandleKey to JsonPrimitive(selection.modelHandle),
        LocalAgentRuntimeMetadata.LocalModelRuntimeKey to JsonPrimitive(selection.runtime),
        LocalAgentRuntimeMetadata.LocalModelAcceleratorKey to JsonPrimitive(selection.accelerator),
    )
    return copy(
        model = selection.lettaCodeModelHandle,
        modelSettings = (modelSettings ?: ModelSettings()).copy(
            providerType = LocalAgentRuntimeMetadata.LocalLettaCodeRuntime,
            parallelToolCalls = false,
            maxOutputTokens = selection.maxTokens,
        ),
        metadata = metadata.orEmpty() + localMetadata,
        toolIds = null,
        tools = null,
        enableSleeptime = false,
        includeBaseTools = false,
        includeMultiAgentTools = false,
        includeBaseToolRules = false,
        parallelToolCalls = false,
    )
}
