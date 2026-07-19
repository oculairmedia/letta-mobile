package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ProjectCreateRequest
import com.letta.mobile.data.api.ProjectUpdateRequest
import com.letta.mobile.data.model.BeadsRemoteProvisionResponse
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.model.ProjectDetailResponse
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.model.ProjectSyncTriggerRequest
import com.letta.mobile.data.model.ProjectSyncTriggerResponse
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Projects over the Iroh admin_rpc control channel.
 *
 * Mirrors [ProjectApi]'s HTTP surface without falling back to raw HTTP in
 * iroh:// mode. This is the client half of the project.* admin_rpc parity slice:
 * the capability probe, Projects tab, create/update/archive/delete, beads-remote,
 * and sync trigger all go through the same Iroh stream as the rest of the admin
 * reads/writes.
 */
class IrohAdminRpcProjectSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    },
) {
    fun shouldUseIroh(): Boolean = settingsRepository.activeBackendIsIroh()

    suspend fun probeAvailability(): Boolean = try {
        refreshProjects(limit = 1)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    suspend fun refreshProjects(limit: Int? = null): ProjectCatalog {
        val path = "/api/projects" + (limit?.let { "?limit=$it" } ?: "")
        val body = buildJsonObject {
            limit?.let { put("limit", it) }
        }.toString()
        val result = rpc("project.list", path, body) ?: return ProjectCatalog(projects = emptyList())
        return json.decodeFromJsonElement(ProjectCatalog.serializer(), result)
    }

    suspend fun getProject(identifier: String): ProjectSummary {
        val result = rpc("project.get", "/api/projects/$identifier", idBody(identifier))
            ?: error("Iroh admin_rpc project.get returned no result")
        return json.decodeFromJsonElement(ProjectDetailResponse.serializer(), result).project
    }

    suspend fun getBeadsRemoteStatus(identifier: String): BeadsRemoteStatus {
        val result = rpc("project.beadsRemoteStatus", "/api/projects/$identifier/beads-remote", idBody(identifier))
            ?: error("Iroh admin_rpc project.beadsRemoteStatus returned no result")
        return json.decodeFromJsonElement(BeadsRemoteStatus.serializer(), result)
    }

    suspend fun provisionBeadsRemote(identifier: String, push: Boolean = true): BeadsRemoteProvisionResponse {
        val body = json.encodeToString(BeadsRemoteProvisionRequest.serializer(), BeadsRemoteProvisionRequest(push = push))
        val result = rpc("project.provisionBeadsRemote", "/api/projects/$identifier/beads-remote/provision", mergeIdBody(identifier, body))
            ?: error("Iroh admin_rpc project.provisionBeadsRemote returned no result")
        return json.decodeFromJsonElement(BeadsRemoteProvisionResponse.serializer(), result)
    }

    suspend fun triggerSync(identifier: String): ProjectSyncTriggerResponse {
        val body = json.encodeToString(ProjectSyncTriggerRequest.serializer(), ProjectSyncTriggerRequest(projectId = ProjectId(identifier)))
        val result = rpc("project.triggerSync", "/api/sync/trigger", body)
            ?: error("Iroh admin_rpc project.triggerSync returned no result")
        return json.decodeFromJsonElement(ProjectSyncTriggerResponse.serializer(), result)
    }

    suspend fun createProject(name: String?, filesystemPath: String, gitUrl: String?): ProjectSummary {
        val body = json.encodeToString(
            ProjectCreateRequest.serializer(),
            ProjectCreateRequest(name = name, filesystemPath = filesystemPath, gitUrl = gitUrl),
        )
        val result = rpc("project.create", "/api/registry/projects", body)
            ?: error("Iroh admin_rpc project.create returned no result")
        return json.decodeFromJsonElement(ProjectMutationResponse.serializer(), result).project
    }

    suspend fun updateProject(identifier: String, filesystemPath: String?, gitUrl: String?): ProjectSummary {
        val body = json.encodeToString(
            ProjectUpdateRequest.serializer(),
            ProjectUpdateRequest(filesystemPath = filesystemPath, gitUrl = gitUrl),
        )
        val result = rpc("project.update", "/api/registry/projects/$identifier", mergeIdBody(identifier, body))
            ?: error("Iroh admin_rpc project.update returned no result")
        return json.decodeFromJsonElement(ProjectMutationResponse.serializer(), result).project
    }

    suspend fun archiveProject(identifier: String): ProjectSummary {
        val body = json.encodeToString(ProjectUpdateRequest.serializer(), ProjectUpdateRequest(status = "archived"))
        val result = rpc("project.archive", "/api/registry/projects/$identifier", mergeIdBody(identifier, body))
            ?: error("Iroh admin_rpc project.archive returned no result")
        return json.decodeFromJsonElement(ProjectMutationResponse.serializer(), result).project
    }

    suspend fun deleteProject(identifier: String) {
        rpc("project.delete", "/api/registry/projects/$identifier", idBody(identifier))
    }

    private fun idBody(identifier: String): String = buildJsonObject {
        put("identifier", identifier)
    }.toString()

    private fun mergeIdBody(identifier: String, body: String): String {
        val payload = json.parseToJsonElement(body).jsonObject
        return buildJsonObject {
            put("identifier", identifier)
            payload.forEach { (key, value) -> put(key, value) }
        }.toString()
    }

    private suspend fun rpc(method: String, path: String, body: String?): kotlinx.serialization.json.JsonElement? {
        val response = channelTransport.adminRpc(method = method, path = path, body = body)
        if (!response.success) error(response.error ?: "Iroh admin_rpc $method failed")
        return response.result
    }
}

@kotlinx.serialization.Serializable
private data class BeadsRemoteProvisionRequest(val push: Boolean = true)

@kotlinx.serialization.Serializable
private data class ProjectMutationResponse(val project: ProjectSummary)
