package com.letta.mobile.data.repository

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.api.RunApi
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.repository.api.IRunRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RunRepository @Inject constructor(
    private val runApi: RunApi,
) : IRunRepository {
    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    override val runs: StateFlow<List<Run>> = _runs.asStateFlow()

    override suspend fun refreshRuns(params: RunListParams) {
        _runs.value = runApi.listRuns(params)
    }

    override suspend fun getRecentRuns(limit: Int): List<Run> {
        return runApi.listRuns(
            RunListParams(
                limit = limit,
                order = "desc",
                orderBy = "created_at",
            )
        )
    }

    override suspend fun getRun(runId: String): Run {
        return runApi.retrieveRun(runId)
    }

    override suspend fun getRunMessages(runId: String): List<LettaMessage> {
        return runApi.listRunMessages(runId = runId, order = "asc")
    }

    override suspend fun getRunUsage(runId: String): UsageStatistics {
        return runApi.retrieveRunUsage(runId)
    }

    override suspend fun getRunMetrics(runId: String): RunMetrics {
        return runApi.retrieveRunMetrics(runId)
    }

    override suspend fun getRunSteps(runId: String): List<Step> {
        return runApi.listRunSteps(runId = runId, order = "desc")
    }

    override suspend fun cancelRun(run: Run): Run {
        runApi.cancelRun(agentId = run.agentId, runId = run.id)
        val refreshed = runApi.retrieveRun(run.id)
        upsertRun(refreshed)
        return refreshed
    }

    override suspend fun deleteRun(runId: String) {
        runApi.deleteRun(runId)
        _runs.update { current -> current.filterNot { it.id == runId } }
    }

    override fun upsertRun(run: Run) {
        _runs.update { current ->
            val index = current.indexOfFirst { it.id == run.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = run }
            } else {
                current + run
            }
        }
    }
}
