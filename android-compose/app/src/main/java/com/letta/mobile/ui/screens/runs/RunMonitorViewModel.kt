package com.letta.mobile.ui.screens.runs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.data.model.StepMetrics
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.repository.api.IStepRepository
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
data class RunMonitorUiState(
    val runs: ImmutableList<Run> = persistentListOf(),
    val searchQuery: String = "",
    val activeOnly: Boolean = false,
    val selectedRun: Run? = null,
    val selectedRunMessages: ImmutableList<LettaMessage> = persistentListOf(),
    val selectedRunUsage: UsageStatistics? = null,
    val selectedRunMetrics: RunMetrics? = null,
    val selectedRunSteps: ImmutableList<Step> = persistentListOf(),
    val selectedStep: Step? = null,
    val selectedStepMessages: ImmutableList<LettaMessage> = persistentListOf(),
    val selectedStepMetrics: StepMetrics? = null,
    val selectedStepTrace: ProviderTrace? = null,
    val operationError: String? = null,
    /**
     * True when the backend (iroh://) can list runs and steps but not serve run messages, usage,
     * metrics, step detail or cancel/delete. The detail dialog shows a note instead of erroring.
     */
    val runDetailLimited: Boolean = false,
)

@HiltViewModel
class RunMonitorViewModel @Inject constructor(
    private val runRepository: IRunRepository,
    private val stepRepository: IStepRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<RunMonitorUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<RunMonitorUiState>> = _uiState.asStateFlow()

    init {
        loadRuns()
    }

    fun loadRuns() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data
            val activeOnly = current?.activeOnly ?: false
            val searchQuery = current?.searchQuery.orEmpty()
            val selectedRun = current?.selectedRun
            _uiState.value = UiState.Loading
            try {
                runRepository.refreshRuns(RunListParams(active = activeOnly.takeIf { it }))
                _uiState.value = UiState.Success(
                    RunMonitorUiState(
                        runs = runRepository.runs.value.toImmutableList(),
                        searchQuery = searchQuery,
                        activeOnly = activeOnly,
                        selectedRun = selectedRun,
                        selectedRunMessages = current?.selectedRunMessages ?: persistentListOf(),
                        selectedRunUsage = current?.selectedRunUsage,
                        selectedRunMetrics = current?.selectedRunMetrics,
                        selectedRunSteps = current?.selectedRunSteps ?: persistentListOf(),
                        selectedStep = current?.selectedStep,
                        selectedStepMessages = current?.selectedStepMessages ?: persistentListOf(),
                        selectedStepMetrics = current?.selectedStepMetrics,
                        selectedStepTrace = current?.selectedStepTrace,
                        operationError = current?.operationError,
                        runDetailLimited = current?.runDetailLimited ?: false,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load runs"))
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(searchQuery = query))
    }

    fun toggleActiveOnly(value: Boolean) {
        val current = (_uiState.value as? UiState.Success)?.data ?: RunMonitorUiState()
        _uiState.value = UiState.Success(current.copy(activeOnly = value))
        loadRuns()
    }

    fun getFilteredRuns(): List<Run> {
        val current = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        if (current.searchQuery.isBlank()) return current.runs
        val q = current.searchQuery.trim().lowercase()
        return current.runs.filter { run ->
            run.id.lowercase().contains(q) ||
                run.agentId.lowercase().contains(q) ||
                (run.conversationId?.lowercase()?.contains(q) == true) ||
                (run.status?.lowercase()?.contains(q) == true) ||
                (run.stopReason?.lowercase()?.contains(q) == true)
        }
    }

    fun inspectRun(runId: String) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                val run = runRepository.getRun(runId)
                val detailAvailable = runRepository.supportsRunDetail
                val messages = if (detailAvailable) runRepository.getRunMessages(runId) else emptyList()
                val usage = if (detailAvailable) runRepository.getRunUsage(runId) else null
                val metrics = if (detailAvailable) runRepository.getRunMetrics(runId) else null
                val steps = runRepository.getRunSteps(runId)
                _uiState.value = UiState.Success(
                    current.copy(
                        selectedRun = run,
                        selectedRunMessages = messages.toImmutableList(),
                        selectedRunUsage = usage,
                        selectedRunMetrics = metrics,
                        selectedRunSteps = steps.toImmutableList(),
                        selectedStep = null,
                        selectedStepMessages = persistentListOf(),
                        selectedStepMetrics = null,
                        selectedStepTrace = null,
                        operationError = null,
                        runDetailLimited = !detailAvailable,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to load run details"))
            }
        }
    }

    fun inspectStep(stepId: String) {
        if (!stepRepository.supportsStepQueries) return
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                val step = stepRepository.getStep(stepId)
                val stepMessages = stepRepository.getStepMessages(stepId)
                val stepMetrics = stepRepository.getStepMetrics(stepId)
                val stepTrace = stepRepository.getStepTrace(stepId)
                val updatedSteps = current.selectedRunSteps.map { existing -> if (existing.id == step.id) step else existing }
                _uiState.value = UiState.Success(
                    current.copy(
                        selectedRunSteps = updatedSteps.toImmutableList(),
                        selectedStep = step,
                        selectedStepMessages = stepMessages.toImmutableList(),
                        selectedStepMetrics = stepMetrics,
                        selectedStepTrace = stepTrace,
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to load step details"))
            }
        }
    }

    fun updateStepFeedback(stepId: String, feedback: String?) {
        if (!stepRepository.supportsStepQueries) return
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            val targetStep = current.selectedStep?.takeIf { it.id == stepId }
                ?: current.selectedRunSteps.firstOrNull { it.id == stepId }
                ?: return@launch
            try {
                val updatedStep = stepRepository.updateStepFeedback(
                    stepId = stepId,
                    params = StepFeedbackUpdateParams(
                        feedback = feedback,
                        tags = targetStep.tags.takeIf { it.isNotEmpty() },
                    ),
                )
                val refreshedSteps = current.selectedRunSteps.map { existing -> if (existing.id == updatedStep.id) updatedStep else existing }
                _uiState.value = UiState.Success(
                    current.copy(
                        selectedRunSteps = refreshedSteps.toImmutableList(),
                        selectedStep = if (current.selectedStep?.id == updatedStep.id) updatedStep else current.selectedStep,
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to update step feedback"))
            }
        }
    }

    fun cancelRun(runId: String) {
        if (!runRepository.supportsRunDetail) return
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            val selectedRun = current.selectedRun ?: current.runs.firstOrNull { it.id == runId } ?: return@launch
            try {
                val refreshedRun = runRepository.cancelRun(selectedRun)
                val refreshedRuns = current.runs.map { if (it.id == refreshedRun.id) refreshedRun else it }
                    .filterNot { current.activeOnly && it.status !in setOf("created", "running") }
                _uiState.value = UiState.Success(
                    current.copy(
                        runs = refreshedRuns.toImmutableList(),
                        selectedRun = if (current.activeOnly && refreshedRun.status !in setOf("created", "running")) null else refreshedRun,
                        selectedStep = null,
                        selectedStepMessages = persistentListOf(),
                        selectedStepMetrics = null,
                        selectedStepTrace = null,
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to cancel run"))
            }
        }
    }

    fun deleteRun(runId: String) {
        if (!runRepository.supportsRunDetail) return
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                runRepository.deleteRun(runId)
                _uiState.value = UiState.Success(
                    current.copy(
                        runs = current.runs.filterNot { it.id == runId }.toImmutableList(),
                        selectedRun = if (current.selectedRun?.id == runId) null else current.selectedRun,
                        selectedRunMessages = if (current.selectedRun?.id == runId) persistentListOf() else current.selectedRunMessages,
                        selectedRunUsage = if (current.selectedRun?.id == runId) null else current.selectedRunUsage,
                        selectedRunMetrics = if (current.selectedRun?.id == runId) null else current.selectedRunMetrics,
                        selectedRunSteps = if (current.selectedRun?.id == runId) persistentListOf() else current.selectedRunSteps,
                        selectedStep = if (current.selectedRun?.id == runId) null else current.selectedStep,
                        selectedStepMessages = if (current.selectedRun?.id == runId) persistentListOf() else current.selectedStepMessages,
                        selectedStepMetrics = if (current.selectedRun?.id == runId) null else current.selectedStepMetrics,
                        selectedStepTrace = if (current.selectedRun?.id == runId) null else current.selectedStepTrace,
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to delete run"))
            }
        }
    }

    fun clearSelectedRun() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            current.copy(
                selectedRun = null,
                selectedRunMessages = persistentListOf(),
                selectedRunUsage = null,
                selectedRunMetrics = null,
                selectedRunSteps = persistentListOf(),
                selectedStep = null,
                selectedStepMessages = persistentListOf(),
                selectedStepMetrics = null,
                selectedStepTrace = null,
            )
        )
    }

    fun clearSelectedStep() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            current.copy(
                selectedStep = null,
                selectedStepMessages = persistentListOf(),
                selectedStepMetrics = null,
                selectedStepTrace = null,
            )
        )
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
