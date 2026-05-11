package com.letta.mobile.data.api

import com.letta.mobile.data.model.ProjectIssueConflictResponse
import com.letta.mobile.data.model.ProjectIssueDetailResponse
import com.letta.mobile.data.model.ProjectIssueListParams
import com.letta.mobile.data.model.ProjectIssueListResponse
import com.letta.mobile.data.model.ProjectIssueMutationResponse
import com.letta.mobile.data.model.ProjectReadyWorkResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ProjectIssueConflictException(
    val conflictResponse: ProjectIssueConflictResponse,
) : Exception(conflictResponse.error)

data class ProjectIssueMutationHeaders(
    val ifMatch: String,
    val idempotencyKey: String,
)

@Serializable
data class ProjectIssueClaimRequest(
    val assignee: String,
)

@Serializable
data class ProjectIssueStatusRequest(
    val status: String,
)

@Serializable
data class ProjectIssueNoteRequest(
    val text: String,
)

@Serializable
data class ProjectIssueReasonRequest(
    val reason: String,
)

@Singleton
open class ProjectWorkApi @Inject constructor(
    private val apiClient: LettaApiClient,
) {
    open suspend fun getReadyWork(
        projectId: String,
        limit: Int? = null,
        cursor: String? = null,
    ): ProjectReadyWorkResponse {
        val response = client().get("${baseUrl()}/api/projects/$projectId/ready-work") {
            optionalParameter("limit", limit)
            optionalParameter("cursor", cursor)
        }
        return response.bodyOrThrow()
    }

    open suspend fun listIssues(
        projectId: String,
        params: ProjectIssueListParams = ProjectIssueListParams(),
    ): ProjectIssueListResponse {
        val response = client().get("${baseUrl()}/api/projects/$projectId/issues") {
            optionalParameter("status", params.status)
            optionalParameter("priority", params.priority)
            optionalParameter("assignee", params.assignee)
            optionalParameter("type", params.type)
            optionalParameter("ready", params.ready)
            optionalParameter("q", params.query)
            optionalParameter("updatedSince", params.updatedSince)
            optionalParameter("sort", params.sort)
            optionalParameter("limit", params.limit)
            optionalParameter("cursor", params.cursor)
        }
        return response.bodyOrThrow()
    }

    open suspend fun getIssue(issueId: String): ProjectIssueDetailResponse {
        val response = client().get("${baseUrl()}/api/issues/$issueId")
        return response.bodyOrThrow()
    }

    open suspend fun claimIssue(
        issueId: String,
        request: ProjectIssueClaimRequest,
        headers: ProjectIssueMutationHeaders,
    ): ProjectIssueMutationResponse = mutateIssue(
        path = "/api/issues/$issueId/claim",
        headers = headers,
        body = request,
    )

    open suspend fun unclaimIssue(
        issueId: String,
        headers: ProjectIssueMutationHeaders,
    ): ProjectIssueMutationResponse = mutateIssue(
        path = "/api/issues/$issueId/unclaim",
        headers = headers,
        body = EmptyMutationRequest,
    )

    open suspend fun updateIssueStatus(
        issueId: String,
        request: ProjectIssueStatusRequest,
        headers: ProjectIssueMutationHeaders,
    ): ProjectIssueMutationResponse {
        val response = client().patch("${baseUrl()}/api/issues/$issueId/status") {
            contentType(ContentType.Application.Json)
            mutationHeaders(headers)
            setBody(request)
        }
        return response.bodyOrThrow()
    }

    open suspend fun addIssueNote(
        issueId: String,
        request: ProjectIssueNoteRequest,
        headers: ProjectIssueMutationHeaders,
    ): ProjectIssueMutationResponse = mutateIssue(
        path = "/api/issues/$issueId/notes",
        headers = headers,
        body = request,
    )

    open suspend fun closeIssue(
        issueId: String,
        request: ProjectIssueReasonRequest,
        headers: ProjectIssueMutationHeaders,
    ): ProjectIssueMutationResponse = mutateIssue(
        path = "/api/issues/$issueId/close",
        headers = headers,
        body = request,
    )

    open suspend fun reopenIssue(
        issueId: String,
        request: ProjectIssueReasonRequest,
        headers: ProjectIssueMutationHeaders,
    ): ProjectIssueMutationResponse = mutateIssue(
        path = "/api/issues/$issueId/reopen",
        headers = headers,
        body = request,
    )

    private suspend inline fun <reified T> mutateIssue(
        path: String,
        headers: ProjectIssueMutationHeaders,
        body: T,
    ): ProjectIssueMutationResponse {
        val response = client().post("${baseUrl()}$path") {
            contentType(ContentType.Application.Json)
            mutationHeaders(headers)
            setBody(body)
        }
        return response.bodyOrThrow()
    }

    private suspend fun client() = apiClient.getClient()

    private fun baseUrl() = apiClient.getBaseUrl().trimEnd('/')

    private fun io.ktor.client.request.HttpRequestBuilder.mutationHeaders(headers: ProjectIssueMutationHeaders) {
        header(HttpHeaders.IfMatch, headers.ifMatch)
        header("Idempotency-Key", headers.idempotencyKey)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.optionalParameter(name: String, value: Any?) {
        if (value != null) parameter(name, value)
    }

    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
        if (status == HttpStatusCode.Conflict) {
            throw ProjectIssueConflictException(body())
        }
        if (status.value !in 200..299) {
            throw ApiException(status.value, bodyAsText())
        }
        return body()
    }
}

@Serializable
private object EmptyMutationRequest
