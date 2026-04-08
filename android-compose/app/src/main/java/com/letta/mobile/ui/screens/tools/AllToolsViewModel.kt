package com.letta.mobile.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AllToolsViewModel @Inject constructor(
    private val toolRepository: ToolRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<List<Tool>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<Tool>>> = _uiState.asStateFlow()

    init {
        loadTools()
    }

    fun loadTools() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                toolRepository.refreshTools()
                val tools = toolRepository.getTools().first()
                _uiState.value = UiState.Success(tools)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load tools")
            }
        }
    }
}
