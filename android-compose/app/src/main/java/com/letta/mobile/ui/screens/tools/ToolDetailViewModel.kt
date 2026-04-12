package com.letta.mobile.ui.screens.tools

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolSchemaGenerateParams
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ToolDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val toolApi: ToolApi,
    private val toolRepository: ToolRepository,
    private val agentRepository: AgentRepository,
) : ViewModel() {
    companion object {
        private const val AGENT_ATTACHMENT_CACHE_TTL_MS = 30_000L
    }

    private val toolId: String = savedStateHandle.get<String>("toolId") ?: ""

    private val _uiState = MutableStateFlow<UiState<Tool>>(UiState.Loading)
    val uiState: StateFlow<UiState<Tool>> = _uiState.asStateFlow()

    private val _deleteState = MutableStateFlow<UiState<Unit>?>(null)
    val deleteState: StateFlow<UiState<Unit>?> = _deleteState.asStateFlow()

    private val _agentState = MutableStateFlow<ToolAgentAttachmentUiState>(ToolAgentAttachmentUiState())
    val agentState: StateFlow<ToolAgentAttachmentUiState> = _agentState.asStateFlow()

    init {
        loadTool()
        loadAgentAttachments()
    }

    fun loadTool() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val tool = toolApi.getTool(toolId)
                _uiState.value = UiState.Success(tool)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to load tool")
                )
            }
        }
    }

    fun updateTool(description: String?, sourceCode: String, tags: List<String>?) {
        viewModelScope.launch {
            try {
                val currentTool = (uiState.value as? UiState.Success)?.data
                val sourceType = currentTool?.sourceType ?: "python"
                val jsonSchema = toolApi.generateJsonSchema(
                    ToolSchemaGenerateParams(
                        code = sourceCode,
                        sourceType = sourceType,
                    )
                )
                val updated = toolRepository.updateTool(
                    toolId = toolId,
                    params = ToolUpdateParams(
                        description = description,
                        sourceCode = sourceCode,
                        sourceType = sourceType,
                        jsonSchema = jsonSchema,
                        tags = tags,
                    )
                )
                _uiState.value = UiState.Success(updated)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to update tool")
                )
            }
        }
    }

    fun deleteTool() {
        viewModelScope.launch {
            _deleteState.value = UiState.Loading
            try {
                toolRepository.deleteTool(toolId)
                _deleteState.value = UiState.Success(Unit)
            } catch (e: Exception) {
                _deleteState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to delete tool")
                )
            }
        }
    }

    fun clearDeleteState() {
        _deleteState.value = null
    }

    fun loadAgentAttachments() {
        viewModelScope.launch {
            try {
                agentRepository.refreshAgentsIfStale(AGENT_ATTACHMENT_CACHE_TTL_MS)
                val agents = agentRepository.agents.value
                _agentState.value = ToolAgentAttachmentUiState(
                    attachedAgents = agents.filter { agent -> agent.tools.any { it.id == toolId } },
                    availableAgents = agents.filter { agent -> agent.tools.none { it.id == toolId } },
                )
            } catch (e: Exception) {
                _deleteState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load agent attachments"))
            }
        }
    }

    fun attachToAgent(agentId: String) {
        viewModelScope.launch {
            try {
                toolRepository.attachTool(agentId, toolId)
                loadAgentAttachments()
            } catch (e: Exception) {
                _deleteState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to attach tool to agent"))
            }
        }
    }

    fun detachFromAgent(agentId: String) {
        viewModelScope.launch {
            try {
                toolRepository.detachTool(agentId, toolId)
                loadAgentAttachments()
            } catch (e: Exception) {
                _deleteState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to detach tool from agent"))
            }
        }
    }
}

@androidx.compose.runtime.Immutable
data class ToolAgentAttachmentUiState(
    val attachedAgents: List<Agent> = emptyList(),
    val availableAgents: List<Agent> = emptyList(),
)
