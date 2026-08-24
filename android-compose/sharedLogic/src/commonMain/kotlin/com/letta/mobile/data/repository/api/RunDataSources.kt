package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.UsageStatistics

interface RunRemoteSource {
    suspend fun listRuns(params: RunListParams = RunListParams()): List<Run>
    suspend fun retrieveRun(runId: String): Run
    suspend fun listRunMessages(
        runId: String,
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
    ): List<LettaMessage>

    suspend fun retrieveRunUsage(runId: String): UsageStatistics
    suspend fun retrieveRunMetrics(runId: String): RunMetrics
    suspend fun listRunSteps(
        runId: String,
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
    ): List<Step>

    suspend fun cancelRun(agentId: String, runId: String): Map<String, String>
    suspend fun deleteRun(runId: String)
}

interface RunIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listRuns(params: RunListParams = RunListParams()): List<Run>
    suspend fun getRun(runId: String): Run
    suspend fun getRunSteps(runId: String): List<Step>
}
