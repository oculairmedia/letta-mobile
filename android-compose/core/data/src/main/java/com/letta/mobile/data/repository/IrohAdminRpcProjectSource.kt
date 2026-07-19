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
import com.letta.mobile.data.repository.iroh.AdminRpcCall
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
        val result = rpc(AdminRpcCall("project.list", path, body)) ?: return ProjectCatalog(projects = emptyList())
        return json.decodeFromJsonElement(ProjectCatalog.serializer(), result)
    }

    suspend fun getProject(identifier: String): ProjectSummary {
        val project = ProjectRpcRef(identifier)
        val result = rpc(AdminRpcCall("project.get", project.apiPath(), project.idBody()))
            ?: error("Iroh admin_rpc project.get returned no result")
        return json.decodeFromJsonElement(ProjectDetailResponse.serializer(), result).project
    }

    suspend fun getBeadsRemoteStatus(identifier: String): BeadsRemoteStatus {
        val project = ProjectRpcRef(identifier)
        val result = rpc(
            AdminRpcCall(
                "project.beadsRemoteStatus",
                project.apiPath("/beads-remote"),
                project.idBody(),
            ),
        ) ?: error("Iroh admin_rpc project.beadsRemoteStatus returned no result")
        return json.decodeFromJsonElement(BeadsRemoteStatus.serializer(), result)
    }

    suspend fun provisionBeadsRemote(identifier: String, push: Boolean = true): BeadsRemoteProvisionResponse {
        val project = ProjectRpcRef(identifier)
        val body = json.encodeToString(BeadsRemoteProvisionRequest.serializer(), BeadsRemoteProvisionRequest(push = push))
        val result = rpc(
            AdminRpcCall(
                "project.provisionBeadsRemote",
                project.apiPath("/beads-remote/provision"),
                project.mergeIdBody(body),
            ),
        ) ?: error("Iroh admin_rpc project.provisionBeadsRemote returned no result")
        return json.decodeFromJsonElement(BeadsRemoteProvisionResponse.serializer(), result)
    }

    suspend fun triggerSync(identifier: String): ProjectSyncTriggerResponse {
        val body = json.encodeToString(
            ProjectSyncTriggerRequest.serializer(),
            ProjectSyncTriggerRequest(projectId = ProjectId(identifier)),
        )
        val result = rpc(AdminRpcCall("project.triggerSync", "/api/sync/trigger", body))
            ?: error("Iroh admin_rpc project.triggerSync returned no result")
        return json.decodeFromJsonElement(ProjectSyncTriggerResponse.serializer(), result)
    }

    suspend fun createProject(name: String?, filesystemPath: String, gitUrl: String?): ProjectSummary {
        val body = json.encodeToString(
            ProjectCreateRequest.serializer(),
            ProjectCreateRequest(name = name, filesystemPath = filesystemPath, gitUrl = gitUrl),
        )
        val result = rpc(AdminRpcCall("project.create", "/api/registry/projects", body))
            ?: error("Iroh admin_rpc project.create returned no result")
        return json.decodeFromJsonElement(ProjectMutationResponse.serializer(), result).project
    }

    suspend fun updateProject(identifier: String, filesystemPath: String?, gitUrl: String?): ProjectSummary {
        val body = json.encodeToString(
            ProjectUpdateRequest.serializer(),
            ProjectUpdateRequest(filesystemPath = filesystemPath, gitUrl = gitUrl),
        )
        return mutateProject(identifier, "project.update", body)
    }

    suspend fun archiveProject(identifier: String): ProjectSummary {
        val body = json.encodeToString(ProjectUpdateRequest.serializer(), ProjectUpdateRequest(status = "archived"))
        return mutateProject(identifier, "project.archive", body)
    }

    private suspend fun mutateProject(identifier: String, method: String, body: String): ProjectSummary {
        val project = ProjectRpcRef(identifier)
        val result = rpc(
            AdminRpcCall(
                method,
                project.registryPath(),
                project.mergeIdBody(body),
            ),
        ) ?: error("Iroh admin_rpc $method returned no result")
        return json.decodeFromJsonElement(ProjectMutationResponse.serializer(), result).project
    }

    suspend fun deleteProject(identifier: String) {
        val project = ProjectRpcRef(identifier)
        rpc(AdminRpcCall("project.delete", project.registryPath(), project.idBody()))
    }

    private fun ProjectRpcRef.idBody(): String = buildJsonObject {
        put("identifier", value)
    }.toString()

    private fun ProjectRpcRef.mergeIdBody(body: String): String {
        val payload = json.parseToJsonElement(body).jsonObject
        return buildJsonObject {
            put("identifier", value)
            payload.forEach { (key, value) -> put(key, value) }
        }.toString()
    }

    private suspend fun rpc(call: AdminRpcCall): JsonElement? {
        val response = channelTransport.adminRpc(method = call.method, path = call.path, body = call.body)
        if (!response.success) error(response.error ?: "Iroh admin_rpc ${call.method} failed")
        return response.result
    }

    private data class ProjectRpcRef(val value: String) {
        fun apiPath(suffix: String = ""): String = "/api/projects/$value$suffix"
        fun registryPath(): String = "/api/registry/projects/$value"
    }
}

@kotlinx.serialization.Serializable
private data class BeadsRemoteProvisionRequest(val push: Boolean = true)

@kotlinx.serialization.Serializable
private data class ProjectMutationResponse(val project: ProjectSummary)
