package com.letta.mobile.ui.screens.blocks

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
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
data class BlockLibraryUiState(
    val blocks: ImmutableList<Block> = persistentListOf(),
    val searchQuery: String = "",
    val filterLabel: String? = null,
    val filterTemplate: Boolean? = null,
    val operationError: String? = null,
    val agentsByBlock: Map<String, List<Agent>> = emptyMap(),
    val allAgents: ImmutableList<Agent> = persistentListOf(),
)

@HiltViewModel
class BlockLibraryViewModel @Inject constructor(
    private val blockRepository: IBlockRepository,
    private val agentRepository: AgentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<BlockLibraryUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<BlockLibraryUiState>> = _uiState.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    private var filterLabel: String? = null
    private var filterTemplate: Boolean? = null
    private var searchQuery: String = ""

    init {
        loadBlocks()
    }

    fun loadBlocks() {
        // Phase 1: Load blocks and render immediately
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val blocks = blockRepository.listAllBlocks(
                    label = filterLabel,
                    isTemplate = filterTemplate,
                )
                _uiState.value = UiState.Success(
                    BlockLibraryUiState(
                        blocks = blocks.toImmutableList(),
                        searchQuery = searchQuery,
                        filterLabel = filterLabel,
                        filterTemplate = filterTemplate,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to load blocks")
                )
            }
        }

        // Phase 2: Load agent relationships in background
        viewModelScope.launch {
            try {
                val cachedAgents = agentRepository.agents.value
                if (cachedAgents.isNotEmpty()) {
                    updateAgentMapping(cachedAgents)
                }
                agentRepository.refreshAgents()
                updateAgentMapping(agentRepository.agents.value)
            } catch (e: Exception) {
                Log.w("BlockLibraryVM", "Failed to load agents for block mapping", e)
            }
        }
    }

    private fun updateAgentMapping(agents: List<Agent>) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        val agentsByBlock = agents
            .flatMap { agent ->
                agent.blocks.map { block -> block.id to agent }
            }
            .groupBy({ it.first }, { it.second })
        _uiState.value = UiState.Success(
            currentState.copy(agentsByBlock = agentsByBlock, allAgents = agents.toImmutableList())
        )
    }

    fun setFilter(label: String?, isTemplate: Boolean?) {
        filterLabel = label
        filterTemplate = isTemplate
        loadBlocks()
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(searchQuery = query))
    }

    fun getFilteredBlocks(): List<Block> {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        if (currentState.searchQuery.isBlank()) return currentState.blocks
        val q = currentState.searchQuery.trim().lowercase()
        return currentState.blocks.filter { block ->
            (block.label?.lowercase()?.contains(q) == true) ||
                (block.description?.lowercase()?.contains(q) == true) ||
                block.value.lowercase().contains(q)
        }
    }

    fun deleteBlock(blockId: String) {
        deleteBlock(blockId) {}
    }

    fun deleteBlock(blockId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                blockRepository.deleteBlock(blockId)
                loadBlocks()
                onSuccess()
            } catch (e: Exception) {
                reportOperationError(mapErrorToUserMessage(e, "Failed to delete block"))
            }
        }
    }

    fun createBlock(label: String, value: String, description: String, limit: Int?) {
        createBlock(label, value, description, limit) {}
    }

    fun createBlock(label: String, value: String, description: String, limit: Int?, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                blockRepository.createBlock(
                    BlockCreateParams(
                        label = label.trim(),
                        value = value,
                        description = description.ifBlank { null },
                        limit = limit,
                    )
                )
                loadBlocks()
                onSuccess()
            } catch (e: Exception) {
                reportOperationError(mapErrorToUserMessage(e, "Failed to create block"))
            }
        }
    }

    fun updateBlock(blockId: String, value: String, description: String, limit: Int?) {
        updateGlobalBlock(blockId, value, description, limit) {}
    }

    fun updateGlobalBlock(
        blockId: String,
        value: String,
        description: String,
        limit: Int?,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                blockRepository.updateGlobalBlock(
                    blockId,
                    BlockUpdateParams(
                        value = value,
                        description = description.ifBlank { null },
                        limit = limit,
                    ),
                    clearDescription = description.isBlank(),
                    clearLimit = limit == null,
                )
                loadBlocks()
                onSuccess()
            } catch (e: Exception) {
                reportOperationError(mapErrorToUserMessage(e, "Failed to update block"))
            }
        }
    }

    fun detachBlockFromAgent(blockId: String, agentId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                blockRepository.detachBlock(agentId, blockId)
                loadBlocks()
                onSuccess()
            } catch (e: Exception) {
                reportOperationError(mapErrorToUserMessage(e, "Failed to detach block"))
            }
        }
    }

    fun attachBlockToAgent(blockId: String, agentId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                blockRepository.attachBlock(agentId, blockId)
                loadBlocks()
                onSuccess()
            } catch (e: Exception) {
                reportOperationError(mapErrorToUserMessage(e, "Failed to attach block"))
            }
        }
    }

    fun toggleSelection(id: String) {
        _selectedIds.value = _selectedIds.value.let { current ->
            if (id in current) current - id else current + id
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun deleteSelected(onComplete: () -> Unit) {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        _selectedIds.value = emptySet()
        viewModelScope.launch {
            for (id in ids) {
                try { blockRepository.deleteBlock(id) } catch (_: Exception) {}
            }
            loadBlocks()
            onComplete()
        }
    }

    fun updateBlockAgents(blockId: String, newAgentIds: Set<String>, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val currentAgentIds = (_uiState.value as? UiState.Success)?.data
                ?.agentsByBlock?.get(blockId)?.map { it.id }?.toSet() ?: emptySet()
            val toAttach = newAgentIds - currentAgentIds
            val toDetach = currentAgentIds - newAgentIds
            try {
                toAttach.forEach { blockRepository.attachBlock(it, blockId) }
                toDetach.forEach { blockRepository.detachBlock(it, blockId) }
                loadBlocks()
                onSuccess()
            } catch (e: Exception) {
                reportOperationError(mapErrorToUserMessage(e, "Failed to update agents"))
            }
        }
    }

    fun clearOperationError() {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(operationError = null))
    }

    private fun reportOperationError(message: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(operationError = message))
        } else {
            _uiState.value = UiState.Error(message)
        }
    }
}
