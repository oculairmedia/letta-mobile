package com.letta.mobile.ui.screens.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentTemplate(
    val id: String,
    val name: String,
    val description: String,
    val icon: String
)

data class TemplatesUiState(
    val templates: List<AgentTemplate> = emptyList()
)

@HiltViewModel
class TemplatesViewModel @Inject constructor() : ViewModel() {
    
    private val _uiState = MutableStateFlow<UiState<TemplatesUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<TemplatesUiState>> = _uiState.asStateFlow()

    init {
        loadTemplates()
    }

    fun loadTemplates() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                TODO("Wire to repository")
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load templates")
            }
        }
    }

    fun createFromTemplate(templateId: String, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            try {
                TODO("Wire to repository")
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to create agent")
            }
        }
    }
}
