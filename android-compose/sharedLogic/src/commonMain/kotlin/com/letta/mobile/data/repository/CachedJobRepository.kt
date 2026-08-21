package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import com.letta.mobile.data.repository.api.IJobRepository
import com.letta.mobile.data.repository.api.JobIrohSource
import com.letta.mobile.data.repository.api.JobRemoteSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Phase 5k: platform-neutral cached job repository. */
open class CachedJobRepository(
    private val remote: JobRemoteSource,
    private val irohJobSource: JobIrohSource? = null,
) : IJobRepository {
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    override val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    override suspend fun refreshJobs(params: JobListParams) {
        val irohSource = irohJobSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _jobs.value = irohSource.listJobs()
            return
        }
        _jobs.value = remote.listJobs(params)
    }

    override suspend fun getJob(jobId: String): Job {
        val irohSource = irohJobSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.getJob(jobId)
        }
        return remote.retrieveJob(jobId)
    }

    override suspend fun cancelJob(jobId: String): Job {
        val job = remote.cancelJob(jobId)
        upsertJob(job)
        return job
    }

    override suspend fun deleteJob(jobId: String): Job {
        val deleted = remote.deleteJob(jobId)
        _jobs.update { current -> current.filterNot { it.id == jobId } }
        return deleted
    }

    override fun upsertJob(job: Job) {
        _jobs.update { current ->
            val index = current.indexOfFirst { it.id == job.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = job }
            } else {
                current + job
            }
        }
    }
}
