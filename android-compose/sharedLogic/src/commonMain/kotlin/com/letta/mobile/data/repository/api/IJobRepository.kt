package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import kotlinx.coroutines.flow.StateFlow

interface IJobRepository {
    val jobs: StateFlow<List<Job>>
    suspend fun refreshJobs(params: JobListParams = JobListParams())
    suspend fun getJob(jobId: String): Job
    suspend fun cancelJob(jobId: String): Job
    suspend fun deleteJob(jobId: String): Job
    fun upsertJob(job: Job)
}
