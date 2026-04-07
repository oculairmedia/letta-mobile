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
                _uiState.value = UiState.Error(e.message ?: "Failed to load passages")
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
                _uiState.value = UiState.Error(e.message ?: "Failed to add passage")
            }
        }
    }

    fun deletePassage(passageId: String) {
        viewModelScope.launch {
            try {
                passageRepository.deletePassage(agentId, passageId)
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(passages = current.passages.filter { it.id != passageId })
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to delete passage")
            }
        }
    }
}
