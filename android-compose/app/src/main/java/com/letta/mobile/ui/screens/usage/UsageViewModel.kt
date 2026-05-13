package com.letta.mobile.ui.screens.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.RunRepository
import com.letta.mobile.data.repository.StepRepository
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class RunSummary(
    val id: String,
    val agentName: String,
    val model: String,
    val status: String,
    val totalTokens: Int,
    val durationMs: Long?,
    val createdAt: String,
    val hasError: Boolean,
)

@androidx.compose.runtime.Immutable
data class UsageUiState(
    val selectedTimeRange: TimeRange = TimeRange.TWENTY_FOUR_HOURS,
    val analytics: UsageAnalytics? = null,
    val recentRuns: ImmutableList<RunSummary> = persistentListOf(),
    val operationError: String? = null,
)

@HiltViewModel
class UsageViewModel @Inject constructor(
    private val stepRepository: StepRepository,
    private val runRepository: RunRepository,
    private val agentRepository: AgentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<UsageUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<UsageUiState>> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun selectTimeRange(timeRange: TimeRange) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedTimeRange = timeRange))
        loadData()
    }

    fun refresh() {
        loadData()
    }

    fun clearOperationError() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(operationError = null))
    }

    private fun loadData() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data
            val timeRange = current?.selectedTimeRange ?: TimeRange.TWENTY_FOUR_HOURS
            _uiState.value = UiState.Loading
            try {
                val now = Instant.now()
                val windowStart = now.minus(timeRange.hours.toLong(), ChronoUnit.HOURS)

                // Steps endpoint may not be available on all server versions;
                // degrade gracefully so runs-based data still renders.
                val steps = runCatching {
                    stepRepository.listSteps(
                        StepListParams(
                            startDate = windowStart.toString(),
                            endDate = now.toString(),
                            limit = 1000,
                        ),
                    )
                }.getOrDefault(emptyList())

                val recentRuns = runRepository.getRecentRuns(200).filter { run ->
                    val createdAt = run.createdAt ?: return@filter false
                    runCatching {
                        val ts = Instant.parse(createdAt)
                        !ts.isBefore(windowStart) && !ts.isAfter(now)
                    }.getOrDefault(false)
                }

                val agentNames = agentRepository.agents.value
                    .associate { it.id.value to (it.name ?: it.id.value) }

                val analytics = UsageAnalyticsCalculator.calculate(
                    steps = steps,
                    runs = recentRuns,
                    agentNames = agentNames,
                    timeRange = timeRange,
                )

                val runSummaries = recentRuns.take(20).map { run ->
                    buildRunSummary(run, agentNames, steps)
                }

                _uiState.value = UiState.Success(
                    UsageUiState(
                        selectedTimeRange = timeRange,
                        analytics = analytics,
                        recentRuns = runSummaries.toImmutableList(),
                    ),
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load usage data"))
            }
        }
    }

    private fun buildRunSummary(
        run: Run,
        agentNames: Map<String, String>,
        steps: List<com.letta.mobile.data.model.Step>,
    ): RunSummary {
        val runSteps = steps.filter { it.runId == run.id }
        val totalTokens = runSteps.sumOf { it.totalTokens ?: ((it.promptTokens ?: 0) + (it.completionTokens ?: 0)) }
        val model = runSteps.firstOrNull()?.model ?: "—"
        val durationMs = run.totalDurationNs?.let { it / 1_000_000 }
        return RunSummary(
            id = run.id,
            agentName = agentNames[run.agentId] ?: run.agentId.take(8),
            model = model,
            status = run.status ?: "unknown",
            totalTokens = totalTokens,
            durationMs = durationMs,
            createdAt = run.createdAt ?: "",
            hasError = run.status == "error" || run.stopReason == "error",
        )
    }
}
