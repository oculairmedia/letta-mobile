package com.letta.mobile.ui.screens.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ModelExposureController
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class ModelBrowserUiState(
    val models: ImmutableList<LlmModel> = persistentListOf(),
    val embeddingModels: ImmutableList<EmbeddingModel> = persistentListOf(),
    val searchQuery: String = "",
    val selectedProvider: String? = null,
    val selectedTab: ModelTab = ModelTab.LLM,
    val selectedLlmModel: LlmModel? = null,
    val selectedEmbeddingModel: EmbeddingModel? = null,
)

/** EXPOSURE: which host models the pickers show (letta-mobile-w4q4p). */
enum class ModelTab { LLM, EMBEDDING, EXPOSURE }

@HiltViewModel
class ModelBrowserViewModel @Inject constructor(
    private val modelRepository: IModelRepository,
    modelCatalog: ModelCatalogRepository,
) : ViewModel() {
    /** Shared presenter behind the EXPOSURE tab; logic lives in sharedLogic. */
    val exposure = ModelExposureController(viewModelScope, modelCatalog)

    private val _uiState = MutableStateFlow<UiState<ModelBrowserUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ModelBrowserUiState>> = _uiState.asStateFlow()

    init {
        loadModels()
    }

    fun loadModels() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                modelRepository.refreshLlmModels()
                modelRepository.refreshEmbeddingModels()
                _uiState.value = UiState.Success(
                    ModelBrowserUiState(
                        models = modelRepository.llmModels.value.toImmutableList(),
                        embeddingModels = modelRepository.embeddingModels.value.toImmutableList(),
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load models")
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(searchQuery = query))
    }

    fun selectProvider(provider: String?) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedProvider = provider))
    }

    fun selectTab(tab: ModelTab) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedTab = tab, selectedProvider = null))
        if (tab == ModelTab.EXPOSURE) exposure.refresh()
    }

    fun selectLlmModel(model: LlmModel) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedLlmModel = model))
    }

    fun clearSelectedLlmModel() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedLlmModel = null))
    }

    fun selectEmbeddingModel(model: EmbeddingModel) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedEmbeddingModel = model))
    }

    fun clearSelectedEmbeddingModel() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedEmbeddingModel = null))
    }

    fun getFilteredModels(): List<LlmModel> {
        val state = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        return state.models.filter { model ->
            val matchesSearch = state.searchQuery.isBlank() ||
                model.name.contains(state.searchQuery, ignoreCase = true) ||
                model.providerType.contains(state.searchQuery, ignoreCase = true) ||
                (model.handle?.contains(state.searchQuery, ignoreCase = true) == true)
            val matchesProvider = state.selectedProvider == null ||
                model.providerType == state.selectedProvider
            matchesSearch && matchesProvider
        }
    }

    fun getFilteredEmbeddingModels(): List<EmbeddingModel> {
        val state = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        return state.embeddingModels.filter { model ->
            val matchesSearch = state.searchQuery.isBlank() ||
                model.name.contains(state.searchQuery, ignoreCase = true) ||
                model.providerType.contains(state.searchQuery, ignoreCase = true) ||
                (model.handle?.contains(state.searchQuery, ignoreCase = true) == true)
            val matchesProvider = state.selectedProvider == null ||
                model.providerType == state.selectedProvider
            matchesSearch && matchesProvider
        }
    }

    fun getProviders(): List<String> {
        val state = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        return when (state.selectedTab) {
            ModelTab.LLM -> state.models.map { it.providerType }.distinct().sorted()
            ModelTab.EMBEDDING -> state.embeddingModels.map { it.providerType }.distinct().sorted()
            ModelTab.EXPOSURE -> emptyList()
        }
    }
}
