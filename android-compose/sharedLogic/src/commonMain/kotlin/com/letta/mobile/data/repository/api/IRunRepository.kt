package com.letta.mobile.data.repository.api

import com.letta.mobile.runtime.RunId
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
    suspend fun getRun(runId: RunId): Run
    suspend fun getRunMessages(runId: RunId): List<LettaMessage>
    suspend fun getRunUsage(runId: RunId): UsageStatistics
    suspend fun getRunMetrics(runId: RunId): RunMetrics
    suspend fun getRunSteps(runId: RunId): List<Step>
    suspend fun cancelRun(run: Run): Run
    suspend fun deleteRun(runId: RunId)
    fun upsertRun(run: Run)
}
