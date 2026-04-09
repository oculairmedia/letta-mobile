package com.letta.mobile.ui.screens.archival

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.repository.PassageRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class ArchivalUiState(
    val passages: List<Passage> = emptyList(),
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val filterHasSource: Boolean = false,
    val filterHasMetadata: Boolean = false,
    val selectedPassage: Passage? = null,
)

@HiltViewModel
class ArchivalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val passageRepository: PassageRepository,
) : ViewModel() {

    private val agentId: String = savedStateHandle.get<String>("agentId") ?: ""

    private val _uiState = MutableStateFlow<UiState<ArchivalUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ArchivalUiState>> = _uiState.asStateFlow()

    init {
        loadPassages()
    }

    fun loadPassages() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                passageRepository.refreshPassages(agentId)
                val passages = passageRepository.getPassages(agentId).value
                _uiState.value = UiState.Success(ArchivalUiState(passages = passages))
            } catch (e: Exception) {
                android.util.Log.w("ArchivalVM", "Failed to load passages", e)
                _uiState.value = UiState.Success(ArchivalUiState(passages = emptyList()))
            }
        }
    }

    fun search(query: String) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            _uiState.value = UiState.Success(current.copy(searchQuery = query, isSearching = true))
            try {
                val results = if (query.isBlank()) {
                    passageRepository.getPassages(agentId).value
                } else {
                    passageRepository.searchArchival(agentId, query)
                }
                _uiState.value = UiState.Success(current.copy(
                    passages = results, searchQuery = query, isSearching = false
                ))
            } catch (e: Exception) {
                _uiState.value = UiState.Success(current.copy(isSearching = false))
            }
        }
    }

    fun addPassage(text: String) {
        viewModelScope.launch {
            try {
                passageRepository.createPassage(agentId, text)
                loadPassages()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(com.letta.mobile.util.mapErrorToUserMessage(e, "Failed to add passage"))
            }
        }
    }

    fun deletePassage(passageId: String) {
        viewModelScope.launch {
            try {
                passageRepository.deletePassage(agentId, passageId)
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(
                        passages = current.passages.filter { it.id != passageId },
                        selectedPassage = if (current.selectedPassage?.id == passageId) null else current.selectedPassage,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(com.letta.mobile.util.mapErrorToUserMessage(e, "Failed to delete passage"))
            }
        }
    }

    fun inspectPassage(passage: Passage) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedPassage = passage))
    }

    fun setFilterHasSource(value: Boolean) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(filterHasSource = value))
    }

    fun setFilterHasMetadata(value: Boolean) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(filterHasMetadata = value))
    }

    fun getFilteredPassages(): List<Passage> {
        val current = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        return current.passages.filter { passage ->
            (!current.filterHasSource || !passage.sourceId.isNullOrBlank()) &&
                (!current.filterHasMetadata || !passage.metadata.isNullOrEmpty())
        }
    }

    fun clearSelectedPassage() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedPassage = null))
    }
}
