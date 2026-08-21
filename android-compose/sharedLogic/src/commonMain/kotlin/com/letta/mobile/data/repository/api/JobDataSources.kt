package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams

interface JobRemoteSource {
    suspend fun listJobs(params: JobListParams = JobListParams()): List<Job>
    suspend fun retrieveJob(jobId: String): Job
    suspend fun cancelJob(jobId: String): Job
    suspend fun deleteJob(jobId: String): Job
}

interface JobIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listJobs(): List<Job>
    suspend fun getJob(jobId: String): Job
}
