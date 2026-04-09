package com.letta.mobile.ui.screens.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import com.letta.mobile.data.repository.JobRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class JobMonitorUiState(
    val jobs: List<Job> = emptyList(),
    val searchQuery: String = "",
    val activeOnly: Boolean = false,
    val selectedJob: Job? = null,
    val operationError: String? = null,
)

@HiltViewModel
class JobMonitorViewModel @Inject constructor(
    private val jobRepository: JobRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<JobMonitorUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<JobMonitorUiState>> = _uiState.asStateFlow()

    init {
        loadJobs()
    }

    fun loadJobs() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data
            val activeOnly = current?.activeOnly ?: false
            val searchQuery = current?.searchQuery.orEmpty()
            _uiState.value = UiState.Loading
            try {
                jobRepository.refreshJobs(JobListParams(active = activeOnly.takeIf { it }, order = "desc"))
                _uiState.value = UiState.Success(
                    JobMonitorUiState(
                        jobs = jobRepository.jobs.value,
                        searchQuery = searchQuery,
                        activeOnly = activeOnly,
                        selectedJob = current?.selectedJob,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load jobs"))
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(searchQuery = query))
    }

    fun toggleActiveOnly(value: Boolean) {
        val current = (_uiState.value as? UiState.Success)?.data ?: JobMonitorUiState()
        _uiState.value = UiState.Success(current.copy(activeOnly = value))
        loadJobs()
    }

    fun getFilteredJobs(): List<Job> {
        val current = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        if (current.searchQuery.isBlank()) return current.jobs
        val q = current.searchQuery.trim().lowercase()
        return current.jobs.filter { job ->
            job.id.lowercase().contains(q) ||
                (job.agentId?.lowercase()?.contains(q) == true) ||
                (job.status?.lowercase()?.contains(q) == true) ||
                (job.jobType?.lowercase()?.contains(q) == true) ||
                (job.stopReason?.lowercase()?.contains(q) == true) ||
                (job.userId?.lowercase()?.contains(q) == true)
        }
    }

    fun inspectJob(jobId: String) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                val job = jobRepository.getJob(jobId)
                _uiState.value = UiState.Success(current.copy(selectedJob = job, operationError = null))
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to load job details"))
            }
        }
    }

    fun cancelJob(jobId: String) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                jobRepository.cancelJob(jobId)
                jobRepository.refreshJobs(JobListParams(active = current.activeOnly.takeIf { it }, order = "desc"))
                val refreshedJobs = jobRepository.jobs.value
                val refreshedSelectedJob = current.selectedJob?.takeIf { it.id == jobId }?.let { previous ->
                    refreshedJobs.firstOrNull { it.id == previous.id }
                } ?: current.selectedJob?.let { previous ->
                    refreshedJobs.firstOrNull { it.id == previous.id } ?: previous
                }
                _uiState.value = UiState.Success(
                    current.copy(
                        jobs = refreshedJobs,
                        selectedJob = refreshedSelectedJob,
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to cancel job"))
            }
        }
    }

    fun deleteJob(jobId: String) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                jobRepository.deleteJob(jobId)
                _uiState.value = UiState.Success(
                    current.copy(
                        jobs = current.jobs.filterNot { it.id == jobId },
                        selectedJob = if (current.selectedJob?.id == jobId) null else current.selectedJob,
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to delete job"))
            }
        }
    }

    fun clearSelectedJob() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedJob = null))
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
