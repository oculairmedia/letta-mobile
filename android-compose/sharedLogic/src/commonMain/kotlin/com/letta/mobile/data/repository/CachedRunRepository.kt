package com.letta.mobile.data.repository

import com.letta.mobile.runtime.RunId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.repository.api.RunIrohSource
import com.letta.mobile.data.repository.api.RunRemoteSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Phase 5k: platform-neutral cached run repository. */
open class CachedRunRepository(
    private val remote: RunRemoteSource,
    private val irohRunSource: RunIrohSource? = null,
) : IRunRepository {
    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    override val runs: StateFlow<List<Run>> = _runs.asStateFlow()

    override val supportsRunDetail: Boolean
        get() = irohRunSource?.shouldUseIroh() != true

    override suspend fun refreshRuns(params: RunListParams) {
        val irohSource = irohRunSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _runs.value = irohSource.listRuns(params)
            return
        }
        _runs.value = remote.listRuns(params)
    }

    override suspend fun getRecentRuns(limit: Int): List<Run> {
        val recentParams = RunListParams(
            limit = limit,
            order = "desc",
            orderBy = "created_at",
        )
        val irohSource = irohRunSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.listRuns(recentParams)
        }
        return remote.listRuns(recentParams)
    }

    override suspend fun getRun(runId: RunId): Run {
        val irohSource = irohRunSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.getRun(runId.value)
        }
        return remote.retrieveRun(runId.value)
    }

    override suspend fun getRunMessages(runId: RunId): List<LettaMessage> {
        if (!supportsRunDetail) irohUnsupported("run.messages($runId)")
        return remote.listRunMessages(runId = runId.value, order = "asc")
    }

    override suspend fun getRunUsage(runId: RunId): UsageStatistics {
        if (!supportsRunDetail) irohUnsupported("run.usage($runId)")
        return remote.retrieveRunUsage(runId.value)
    }

    override suspend fun getRunMetrics(runId: RunId): RunMetrics {
        if (!supportsRunDetail) irohUnsupported("run.metrics($runId)")
        return remote.retrieveRunMetrics(runId.value)
    }

    override suspend fun getRunSteps(runId: RunId): List<Step> {
        val irohSource = irohRunSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.getRunSteps(runId.value)
        }
        return remote.listRunSteps(runId = runId.value, order = "desc")
    }

    override suspend fun cancelRun(run: Run): Run {
        if (!supportsRunDetail) irohUnsupported("run.cancel(${run.id})")
        remote.cancelRun(agentId = run.agentId, runId = run.id)
        val refreshed = remote.retrieveRun(run.id)
        upsertRun(refreshed)
        return refreshed
    }

    override suspend fun deleteRun(runId: RunId) {
        if (!supportsRunDetail) irohUnsupported("run.delete($runId)")
        remote.deleteRun(runId.value)
        _runs.update { current -> current.filterNot { it.id == runId.value } }
    }

    private fun irohUnsupported(operation: String): Nothing =
        throw UnsupportedOperationException("Iroh admin_rpc does not support $operation yet")

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
