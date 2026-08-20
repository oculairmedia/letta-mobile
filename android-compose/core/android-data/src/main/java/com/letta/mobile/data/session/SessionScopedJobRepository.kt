package com.letta.mobile.data.session

import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import com.letta.mobile.data.repository.api.IJobRepository
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

internal fun defaultSessionScopedJobRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedJobRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IJobRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedJobRepositoryScope(),
    )

    private val _jobs = MutableStateFlow(sessionManager.current.jobRepository.jobs.value)
    override val jobs: StateFlow<List<Job>> = _jobs

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.jobRepository.jobs }
            .onEach { _jobs.value = it }
            .launchIn(proxyScope)
    }

    private val current: IJobRepository
        get() = sessionManager.current.jobRepository

    override suspend fun refreshJobs(params: JobListParams) = sessionManager.withCurrentSession { it.jobRepository.refreshJobs(params) }

    override suspend fun getJob(jobId: String): Job = sessionManager.withCurrentSession { it.jobRepository.getJob(jobId) }

    override suspend fun cancelJob(jobId: String): Job = sessionManager.withCurrentSession { it.jobRepository.cancelJob(jobId) }

    override suspend fun deleteJob(jobId: String): Job = sessionManager.withCurrentSession { it.jobRepository.deleteJob(jobId) }

    override fun upsertJob(job: Job) = current.upsertJob(job)

    fun close() { proxyScope.cancel() }
}
