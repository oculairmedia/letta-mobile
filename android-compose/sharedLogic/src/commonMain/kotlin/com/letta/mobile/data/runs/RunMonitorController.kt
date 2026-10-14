package com.letta.mobile.data.runs

import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionRoute
import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.runtime.RunId
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One run opened in the monitor: the run, its steps and, where the host serves them ([full]), its
 * token usage. An Iroh host answers `run.list` / `run.get` / `step.list` only, so its detail is the
 * run and its steps, and cancel / delete are not offered.
 */
@Immutable
data class RunDetail(
    val run: Run,
    val steps: List<Step> = emptyList(),
    val usage: UsageStatistics? = null,
    val full: Boolean = false,
)

/** Counts over the listed runs, for the page's summary line. */
@Immutable
data class RunSummary(val total: Int = 0, val active: Int = 0, val failed: Int = 0) {
    companion object {
        fun of(runs: List<Run>): RunSummary = RunSummary(
            total = runs.size,
            active = runs.count(Run::isActive),
            failed = runs.count(Run::isFailed),
        )
    }
}

/**
 * letta-mobile-ckmub.2.5 (hard cut S17): what the shared Runs page shows. [error] is a list that
 * could not load at all; [operationError] a detail, cancel or delete that failed over a loaded list.
 */
@Immutable
data class RunMonitorState(
    val runs: List<Run> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val activeOnly: Boolean = false,
    val query: String = "",
    val detail: RunDetail? = null,
    val detailLoading: Boolean = false,
    val operationError: String? = null,
) {
    /** The listed runs narrowed by [query] (id, agent, conversation, status, stop reason). */
    val visibleRuns: List<Run> get() = RunFilter.matching(runs, query)

    val summary: RunSummary get() = RunSummary.of(runs)

    /** A reload over a list already shown (a first load shows the page's loading state). */
    val refreshing: Boolean get() = loading && runs.isNotEmpty()
}

/** The monitor's search: a case-insensitive match on a run's ids and states. */
object RunFilter {
    fun matching(runs: List<Run>, query: String): List<Run> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return runs
        return runs.filter { run -> run.searchFields().any { it.lowercase().contains(needle) } }
    }

    private fun Run.searchFields(): List<String> = listOfNotNull(id, agentId, conversationId, status, stopReason)
}

/** A run still working: created or running. */
val Run.isActive: Boolean get() = status in ACTIVE_STATUSES

/** A run that ended in an error. */
val Run.isFailed: Boolean get() = status == ERROR_STATUS || stopReason == ERROR_STATUS

private val ACTIVE_STATUSES = setOf("created", "running")
private const val ERROR_STATUS = "error"

/**
 * The run monitor shared by every host (letta-mobile-ckmub.2.5): lists the host's runs, filters them
 * and opens one. Cancel and delete exist only where [IRunRepository.supportsRunDetail] is true.
 *
 * letta-mobile-by4vm.13: a null [runRepository] is a session that serves no runs; the page shows the
 * host's "not served here" reason instead of calling it.
 */
class RunMonitorController(
    private val runRepository: IRunRepository?,
    private val scope: CoroutineScope,
    private val errorMessageMapper: (Throwable, String) -> String = { throwable, fallback -> throwable.message ?: fallback },
) {
    private val stateFlow = MutableStateFlow(RunMonitorState())
    val state: StateFlow<RunMonitorState> = stateFlow

    /** Whether runs can be cancelled and deleted on this host. */
    val canManageRuns: Boolean get() = runRepository?.supportsRunDetail == true

    private var loadJob: Job? = null
    private var detailJob: Job? = null

    fun start() = refresh()

    /** Reloads the list; a newer reload supersedes one in flight. */
    fun refresh() {
        loadJob?.cancel()
        val runs = runRepository ?: return stateFlow.update {
            it.copy(loading = false, error = errorMessageMapper(AppServerRouteUnavailable(SessionRoute.RUNS), LOAD_FAILED))
        }
        stateFlow.update { it.copy(loading = true) }
        loadJob = scope.launch {
            val activeOnly = stateFlow.value.activeOnly
            runCatchingCancellable { runs.refreshRuns(RunListParams(active = activeOnly.takeIf { it })) }
                .onSuccess { stateFlow.update { it.copy(runs = runs.runs.value, loading = false, error = null) } }
                .onFailure { e -> stateFlow.update { it.copy(loading = false, error = errorMessageMapper(e, LOAD_FAILED)) } }
        }
    }

    fun setActiveOnly(activeOnly: Boolean) {
        if (stateFlow.value.activeOnly == activeOnly) return
        stateFlow.update { it.copy(activeOnly = activeOnly) }
        refresh()
    }

    fun setQuery(query: String) = stateFlow.update { it.copy(query = query) }

    /** Opens [runId]: the run and its steps, plus its usage where the host serves it. */
    fun open(runId: String) {
        detailJob?.cancel()
        stateFlow.update { it.copy(detailLoading = true, operationError = null) }
        detailJob = scope.launch {
            runCatchingCancellable { loadDetail(RunId(runId)) }
                .onSuccess { detail -> stateFlow.update { it.copy(detail = detail, detailLoading = false) } }
                .onFailure { e -> stateFlow.update { it.copy(detailLoading = false, operationError = errorMessageMapper(e, DETAIL_FAILED)) } }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        stateFlow.update { it.copy(detail = null, detailLoading = false) }
    }

    fun clearOperationError() = stateFlow.update { it.copy(operationError = null) }

    /** Cancels the open run (hosts with run detail only). */
    fun cancel(runId: String) {
        val run = stateFlow.value.runs.firstOrNull { it.id == runId } ?: stateFlow.value.detail?.run?.takeIf { it.id == runId } ?: return
        if (!canManageRuns) return
        scope.launch {
            runCatchingCancellable { servedRuns().cancelRun(run) }
                .onSuccess { updated -> stateFlow.update { it.replacing(updated) } }
                .onFailure { e -> stateFlow.update { it.copy(operationError = errorMessageMapper(e, CANCEL_FAILED)) } }
        }
    }

    /** Deletes a run (hosts with run detail only) and closes it if it was open. */
    fun delete(runId: String) {
        if (!canManageRuns) return
        scope.launch {
            runCatchingCancellable { servedRuns().deleteRun(RunId(runId)) }
                .onSuccess { stateFlow.update { it.removing(runId) } }
                .onFailure { e -> stateFlow.update { it.copy(operationError = errorMessageMapper(e, DELETE_FAILED)) } }
        }
    }

    /** The served run repository; the backstop when a page calls past an absent route. */
    private fun servedRuns(): IRunRepository = runRepository ?: throw AppServerRouteUnavailable(SessionRoute.RUNS)

    private suspend fun loadDetail(runId: RunId): RunDetail {
        val runs = servedRuns()
        val run = runs.getRun(runId)
        val steps = runs.getRunSteps(runId)
        if (!runs.supportsRunDetail) return RunDetail(run = run, steps = steps)
        return RunDetail(run = run, steps = steps, usage = runs.getRunUsage(runId), full = true)
    }

    private fun RunMonitorState.replacing(run: Run): RunMonitorState = copy(
        runs = runs.map { if (it.id == run.id) run else it }.filterNot { activeOnly && !it.isActive },
        detail = detail?.takeIf { it.run.id == run.id }?.copy(run = run) ?: detail,
        operationError = null,
    )

    private fun RunMonitorState.removing(runId: String): RunMonitorState = copy(
        runs = runs.filterNot { it.id == runId },
        detail = detail?.takeUnless { it.run.id == runId },
        operationError = null,
    )

    private companion object {
        const val LOAD_FAILED = "Failed to load runs"
        const val DETAIL_FAILED = "Failed to load run details"
        const val CANCEL_FAILED = "Failed to cancel run"
        const val DELETE_FAILED = "Failed to delete run"
    }
}
