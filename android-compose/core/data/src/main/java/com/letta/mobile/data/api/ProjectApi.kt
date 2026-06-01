package com.letta.mobile.data.api

import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.model.ProjectDetailResponse
import com.letta.mobile.data.model.BeadsRemoteProvisionResponse
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.model.ProjectSyncTriggerRequest
import com.letta.mobile.data.model.ProjectSyncTriggerResponse
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
     * letta-mobile-2ixd: capability probe for the projects API.
     *
     *   - 2xx with parseable [ProjectCatalog] body → supported.
     *   - 2xx with un-parseable body (e.g. shim's `200 []`, which has no
     *     `projects` field) → unsupported.
     *   - 404 / 405 / 501 → unsupported (definitive).
     *   - Any other 4xx/5xx → unsupported (the server actively refused
     *     the route or is broken; treat as "no projects here today").
     *   - Network error (ConnectException, timeout, etc.) → unsupported.
     *     The earlier "assume supported on network error" turned out to
     *     keep the tab visible against backends that were simply down,
     *     defeating the gate. CapabilityRepository re-probes on every
     *     active-config change, so a recovered server flips the flag
     *     back to true on the next switch.
     */
    open suspend fun probeAvailability(): Boolean {
        val (client, baseUrl) = apiClient.session()
        return try {
            val response = client.get("$baseUrl/api/projects?limit=1")
            if (response.status.value !in 200..299) {
                false
            } else {
                runCatching { response.body<ProjectCatalog>() }.isSuccess
            }
        } catch (e: Exception) {
            false
        }
    }

    open suspend fun listProjects(): ProjectCatalog {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/api/projects")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body<ProjectCatalog>()
    }

    open suspend fun getProject(identifier: String): ProjectSummary {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/api/projects/$identifier")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body<ProjectDetailResponse>().project
    }

    open suspend fun getBeadsRemoteStatus(identifier: String): BeadsRemoteStatus {
        val (client, baseUrl) = apiClient.session()
        val response = client.get("$baseUrl/api/projects/$identifier/beads-remote")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun provisionBeadsRemote(
        identifier: String,
        push: Boolean = true,
    ): BeadsRemoteProvisionResponse {
        val (client, baseUrl) = apiClient.session()
        val response = client.post("$baseUrl/api/projects/$identifier/beads-remote/provision") {
            contentType(ContentType.Application.Json)
            setBody(BeadsRemoteProvisionRequest(push = push))
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun triggerSync(identifier: String): ProjectSyncTriggerResponse {
        val (client, baseUrl) = apiClient.session()
        val response = client.post("$baseUrl/api/sync/trigger") {
            contentType(ContentType.Application.Json)
            setBody(ProjectSyncTriggerRequest(projectId = ProjectId(identifier)))
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun createProject(request: ProjectCreateRequest): ProjectSummary {
        val (client, baseUrl) = apiClient.session()

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
        val (client, baseUrl) = apiClient.session()

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
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/api/registry/projects/$identifier")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}

@Serializable
private data class BeadsRemoteProvisionRequest(
    val push: Boolean = true,
)
