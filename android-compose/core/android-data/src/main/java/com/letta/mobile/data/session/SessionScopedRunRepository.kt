package com.letta.mobile.data.session

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.repository.api.IRunRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun defaultSessionScopedRunRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedRunRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IRunRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedRunRepositoryScope(),
    )

    private val _runs = MutableStateFlow(sessionManager.current.runRepository.runs.value)
    override val runs: StateFlow<List<Run>> = _runs

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.runRepository.runs }
            .onEach { _runs.value = it }
            .launchIn(proxyScope)
    }

    private val current: IRunRepository
        get() = sessionManager.current.runRepository

    override suspend fun refreshRuns(params: RunListParams) = sessionManager.withCurrentSession { it.runRepository.refreshRuns(params) }

    override suspend fun getRecentRuns(limit: Int): List<Run> = sessionManager.withCurrentSession { it.runRepository.getRecentRuns(limit) }

    override suspend fun getRun(runId: String): Run = sessionManager.withCurrentSession { it.runRepository.getRun(runId) }

    override suspend fun getRunMessages(runId: String): List<LettaMessage> = sessionManager.withCurrentSession { it.runRepository.getRunMessages(runId) }

    override suspend fun getRunUsage(runId: String): UsageStatistics = sessionManager.withCurrentSession { it.runRepository.getRunUsage(runId) }

    override suspend fun getRunMetrics(runId: String): RunMetrics = sessionManager.withCurrentSession { it.runRepository.getRunMetrics(runId) }

    override suspend fun getRunSteps(runId: String): List<Step> = sessionManager.withCurrentSession { it.runRepository.getRunSteps(runId) }

    override suspend fun cancelRun(run: Run): Run = sessionManager.withCurrentSession { it.runRepository.cancelRun(run) }

    override suspend fun deleteRun(runId: String) = sessionManager.withCurrentSession { it.runRepository.deleteRun(runId) }

    override fun upsertRun(run: Run) = current.upsertRun(run)

    fun close() { proxyScope.cancel() }
}
