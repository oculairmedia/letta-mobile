package com.letta.mobile.ui.screens.blocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class BlockLibraryUiState(
    val blocks: List<Block> = emptyList(),
    val searchQuery: String = "",
    val filterLabel: String? = null,
    val filterTemplate: Boolean? = null,
    val operationError: String? = null,
)

@HiltViewModel
class BlockLibraryViewModel @Inject constructor(
    private val blockRepository: IBlockRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<BlockLibraryUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<BlockLibraryUiState>> = _uiState.asStateFlow()

    private var filterLabel: String? = null
    private var filterTemplate: Boolean? = null
    private var searchQuery: String = ""

    init {
        loadBlocks()
    }

    fun loadBlocks() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val blocks = blockRepository.listAllBlocks(
                    label = filterLabel,
                    isTemplate = filterTemplate,
                )
                _uiState.value = UiState.Success(
                    BlockLibraryUiState(
                        blocks = blocks,
                        searchQuery = searchQuery,
                        filterLabel = filterLabel,
                        filterTemplate = filterTemplate,
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to load blocks")
                )
            }
        }
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
