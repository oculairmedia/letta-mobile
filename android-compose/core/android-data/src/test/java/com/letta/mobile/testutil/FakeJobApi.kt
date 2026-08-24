package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.JobApi
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import io.mockk.mockk

class FakeJobApi : JobApi(mockk(relaxed = true)) {
    var jobs = mutableListOf<Job>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listJobs(params: JobListParams): List<Job> {
        calls.add("listJobs")
        if (shouldFail) throw ApiException(500, "Server error")
        return jobs.filter { job ->
            (params.active == null || (job.status in activeStatuses()) == params.active)
        }
    }

    override suspend fun retrieveJob(jobId: String): Job {
        calls.add("retrieveJob:$jobId")
        if (shouldFail) throw ApiException(500, "Server error")
        return jobs.firstOrNull { it.id == jobId } ?: throw ApiException(404, "Not found")
    }

    override suspend fun cancelJob(jobId: String): Job {
        calls.add("cancelJob:$jobId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = jobs.indexOfFirst { it.id == jobId }
        if (index < 0) throw ApiException(404, "Not found")
        val updated = jobs[index].copy(status = "cancelled", stopReason = "cancelled")
        jobs[index] = updated
        return updated
    }

    override suspend fun deleteJob(jobId: String): Job {
        calls.add("deleteJob:$jobId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = jobs.indexOfFirst { it.id == jobId }
        if (index < 0) throw ApiException(404, "Not found")
        return jobs.removeAt(index)
    }
}

private fun activeStatuses(): Set<String> = setOf("created", "running", "pending")
