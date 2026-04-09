package com.letta.mobile.data.api

import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.bodyAsText
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class JobApi @Inject constructor(
    private val apiClient: LettaApiClient,
) {
    open suspend fun listJobs(params: JobListParams = JobListParams()): List<Job> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/jobs/") {
            parameter("source_id", params.sourceId)
            parameter("before", params.before)
            parameter("after", params.after)
            parameter("limit", params.limit)
            parameter("order", params.order)
            parameter("order_by", params.orderBy)
            parameter("active", params.active)
            parameter("ascending", params.ascending)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun retrieveJob(jobId: String): Job {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/jobs/$jobId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun cancelJob(jobId: String): Job {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.patch("$baseUrl/v1/jobs/$jobId/cancel")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun deleteJob(jobId: String): Job {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.delete("$baseUrl/v1/jobs/$jobId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return if (response.status == HttpStatusCode.NoContent) {
            Job(id = jobId)
        } else {
            response.body()
        }
    }
}
