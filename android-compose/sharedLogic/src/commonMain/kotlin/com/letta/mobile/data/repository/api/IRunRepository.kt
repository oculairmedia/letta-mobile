package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.UsageStatistics
import kotlinx.coroutines.flow.StateFlow

interface IRunRepository {
    val runs: StateFlow<List<Run>>

    /**
     * False when the active backend cannot serve run messages/usage/metrics or cancel/delete
     * (iroh:// has admin_rpc `run.list`/`run.get`/`step.list` only). Callers hide those
     * affordances instead of calling a route that would fail.
     */
    val supportsRunDetail: Boolean get() = true

    suspend fun refreshRuns(params: RunListParams = RunListParams())
    suspend fun getRecentRuns(limit: Int = 100): List<Run>
    suspend fun getRun(runId: String): Run
    suspend fun getRunMessages(runId: String): List<LettaMessage>
    suspend fun getRunUsage(runId: String): UsageStatistics
    suspend fun getRunMetrics(runId: String): RunMetrics
    suspend fun getRunSteps(runId: String): List<Step>
    suspend fun cancelRun(run: Run): Run
    suspend fun deleteRun(runId: String)
    fun upsertRun(run: Run)
}
