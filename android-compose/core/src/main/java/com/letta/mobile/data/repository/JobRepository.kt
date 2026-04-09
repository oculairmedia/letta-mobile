package com.letta.mobile.data.repository

import com.letta.mobile.data.api.JobApi
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JobRepository @Inject constructor(
    private val jobApi: JobApi,
) {
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    suspend fun refreshJobs(params: JobListParams = JobListParams()) {
        _jobs.value = jobApi.listJobs(params)
    }

    suspend fun getJob(jobId: String): Job {
        return jobApi.retrieveJob(jobId)
    }

    suspend fun cancelJob(jobId: String): Job {
        val job = jobApi.cancelJob(jobId)
        upsertJob(job)
        return job
    }

    suspend fun deleteJob(jobId: String): Job {
        val deleted = jobApi.deleteJob(jobId)
        _jobs.update { current -> current.filterNot { it.id == jobId } }
        return deleted
    }

    fun upsertJob(job: Job) {
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
