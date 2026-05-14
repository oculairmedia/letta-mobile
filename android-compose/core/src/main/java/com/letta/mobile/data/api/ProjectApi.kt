package com.letta.mobile.data.api

import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.model.ProjectDetailResponse
import com.letta.mobile.data.model.ProjectSummary
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProjectCreateRequest(
    val name: String? = null,
    @SerialName("filesystem_path") val filesystemPath: String,
    @SerialName("git_url") val gitUrl: String? = null,
)

@Serializable
data class ProjectUpdateRequest(
    val status: String? = null,
    @SerialName("filesystem_path") val filesystemPath: String? = null,
    @SerialName("git_url") val gitUrl: String? = null,
)

@Serializable
private data class ProjectMutationResponse(
    val project: ProjectSummary,
)

@Singleton
open class ProjectApi @Inject constructor(
    private val apiClient: LettaApiClient,
) {
    /**
     * letta-mobile-2ixd: lightweight capability probe for the projects API.
     * Returns true when the connected backend serves `/api/projects` with a
     * 2xx response (or anything that isn't a definitive 404 / 501 / 405),
     * false when the server tells us the endpoint isn't supported, and
     * true (the assume-supported fallback) on transient errors so a flaky
     * network doesn't silently hide the feature. CapabilityRepository
     * caches this per-config so we don't probe on every recomposition.
     */
    open suspend fun probeAvailability(): Boolean {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl().trimEnd('/')
        return try {
            val response = client.get("$baseUrl/api/projects?limit=1")
            when (response.status.value) {
                404, 405, 501 -> false
                else -> true
            }
        } catch (e: Exception) {
            // Network / parse / other — assume supported to avoid hiding a
            // working feature behind transient failures.
            true
        }
    }

    open suspend fun listProjects(): ProjectCatalog {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl().trimEnd('/')

        val response = client.get("$baseUrl/api/projects")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body<ProjectCatalog>()
    }

    open suspend fun getProject(identifier: String): ProjectSummary {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl().trimEnd('/')

        val response = client.get("$baseUrl/api/projects/$identifier")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body<ProjectDetailResponse>().project
    }

    open suspend fun createProject(request: ProjectCreateRequest): ProjectSummary {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl().trimEnd('/')

        val response = client.post("$baseUrl/api/registry/projects") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body<ProjectMutationResponse>().project
    }

    open suspend fun updateProject(identifier: String, request: ProjectUpdateRequest): ProjectSummary {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl().trimEnd('/')

        val response = client.patch("$baseUrl/api/registry/projects/$identifier") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body<ProjectMutationResponse>().project
    }

    open suspend fun archiveProject(identifier: String): ProjectSummary {
        return updateProject(
            identifier = identifier,
            request = ProjectUpdateRequest(status = "archived"),
        )
    }

    open suspend fun deleteProject(identifier: String) {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl().trimEnd('/')

        val response = client.delete("$baseUrl/api/registry/projects/$identifier")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}
