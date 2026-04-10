package com.letta.mobile.ui.screens.messagebatches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.BatchMessage
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class MessageBatchMonitorUiState(
    val batches: List<Job> = emptyList(),
    val searchQuery: String = "",
    val activeOnly: Boolean = false,
    val selectedBatch: Job? = null,
    val selectedBatchMessages: List<BatchMessage> = emptyList(),
    val operationError: String? = null,
)

@HiltViewModel
class MessageBatchMonitorViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<MessageBatchMonitorUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<MessageBatchMonitorUiState>> = _uiState.asStateFlow()

    init {
        loadBatches()
    }

    fun loadBatches() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data
            _uiState.value = UiState.Loading
            try {
                val batches = messageRepository.listBatches().sortedByDescending { it.createdAt.orEmpty() }
                val selectedBatch = current?.selectedBatch?.let { selected ->
                    batches.firstOrNull { it.id == selected.id } ?: selected
                }
                _uiState.value = UiState.Success(
                    MessageBatchMonitorUiState(
                        batches = batches,
                        searchQuery = current?.searchQuery.orEmpty(),
                        activeOnly = current?.activeOnly ?: false,
                        selectedBatch = selectedBatch,
                        selectedBatchMessages = if (selectedBatch?.id == current?.selectedBatch?.id) {
                            current?.selectedBatchMessages.orEmpty()
                        } else {
                            emptyList()
                        },
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load message batches"))
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(searchQuery = query))
    }

    fun toggleActiveOnly(value: Boolean) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(activeOnly = value))
    }

    fun getFilteredBatches(): List<Job> {
        val current = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        val base = if (current.activeOnly) {
            current.batches.filterNot { it.isTerminalStatus() }
        } else {
            current.batches
        }
        if (current.searchQuery.isBlank()) return base

        val query = current.searchQuery.trim().lowercase()
        return base.filter { batch ->
            batch.id.lowercase().contains(query) ||
                (batch.status?.lowercase()?.contains(query) == true) ||
                (batch.jobType?.lowercase()?.contains(query) == true) ||
                (batch.stopReason?.lowercase()?.contains(query) == true) ||
                (batch.agentId?.lowercase()?.contains(query) == true) ||
                batch.metadata.entries.any { (key, value) ->
                    key.lowercase().contains(query) || value.toString().lowercase().contains(query)
                }
        }
    }

    fun inspectBatch(batchId: String) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                val batch = messageRepository.retrieveBatch(batchId)
                val messages = messageRepository.listBatchMessages(batchId).messages
                _uiState.value = UiState.Success(
                    current.copy(
                        batches = current.batches.replaceBatch(batch),
                        selectedBatch = batch,
                        selectedBatchMessages = messages,
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to load message batch details"))
            }
        }
    }

    fun cancelBatch(batchId: String) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                messageRepository.cancelBatch(batchId)
                val refreshedBatch = messageRepository.retrieveBatch(batchId)
                val refreshedBatches = messageRepository.listBatches().sortedByDescending { it.createdAt.orEmpty() }
                val refreshedMessages = if (current.selectedBatch?.id == batchId) {
                    messageRepository.listBatchMessages(batchId).messages
                } else {
                    current.selectedBatchMessages
                }
                _uiState.value = UiState.Success(
                    current.copy(
                        batches = refreshedBatches.replaceBatch(refreshedBatch),
                        selectedBatch = if (current.selectedBatch?.id == batchId) refreshedBatch else current.selectedBatch,
                        selectedBatchMessages = refreshedMessages,
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to cancel message batch"))
            }
        }
    }

    fun clearSelectedBatch() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedBatch = null, selectedBatchMessages = emptyList()))
    }

    fun clearOperationError() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(operationError = null))
    }

    private fun setOperationError(message: String) {
        val current = (_uiState.value as? UiState.Success)?.data
        if (current != null) {
            _uiState.value = UiState.Success(current.copy(operationError = message))
        } else {
            _uiState.value = UiState.Error(message)
        }
    }
}

private fun List<Job>.replaceBatch(updated: Job): List<Job> {
    val index = indexOfFirst { it.id == updated.id }
    return if (index >= 0) {
        toMutableList().apply { this[index] = updated }
    } else {
        this + updated
    }
}

private fun Job.isTerminalStatus(): Boolean {
    return status in setOf("completed", "failed", "cancelled", "expired")
}
