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
import com.letta.mobile.data.repository.iroh.AdminRpcMethod
import com.letta.mobile.data.repository.iroh.AdminRpcPath
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
        val result = rpc(projectRpc("project.list", path, body)) ?: return ProjectCatalog(projects = emptyList())
        return json.decodeFromJsonElement(ProjectCatalog.serializer(), result)
    }

    suspend fun getProject(identifier: String): ProjectSummary =
        getProject(ProjectId(identifier))

    suspend fun getProject(projectId: ProjectId): ProjectSummary {
        val project = ProjectRpcRef(projectId)
        val result = rpc(projectRpc("project.get", project.apiPath(), project.idBody()))
            ?: error("Iroh admin_rpc project.get returned no result")
        return json.decodeFromJsonElement(ProjectDetailResponse.serializer(), result).project
    }

    suspend fun getBeadsRemoteStatus(identifier: String): BeadsRemoteStatus =
        getBeadsRemoteStatus(ProjectId(identifier))

    suspend fun getBeadsRemoteStatus(projectId: ProjectId): BeadsRemoteStatus {
        val project = ProjectRpcRef(projectId)
        val result = rpc(
            projectRpc("project.beadsRemoteStatus", project.apiPath("/beads-remote"), project.idBody()),
        ) ?: error("Iroh admin_rpc project.beadsRemoteStatus returned no result")
        return json.decodeFromJsonElement(BeadsRemoteStatus.serializer(), result)
    }

    suspend fun provisionBeadsRemote(identifier: String, push: Boolean = true): BeadsRemoteProvisionResponse =
        provisionBeadsRemote(ProjectId(identifier), push)

    suspend fun provisionBeadsRemote(projectId: ProjectId, push: Boolean = true): BeadsRemoteProvisionResponse {
        val project = ProjectRpcRef(projectId)
        val body = json.encodeToString(BeadsRemoteProvisionRequest.serializer(), BeadsRemoteProvisionRequest(push = push))
        val result = rpc(
            projectRpc(
                "project.provisionBeadsRemote",
                project.apiPath("/beads-remote/provision"),
                project.mergeIdBody(body),
            ),
        ) ?: error("Iroh admin_rpc project.provisionBeadsRemote returned no result")
        return json.decodeFromJsonElement(BeadsRemoteProvisionResponse.serializer(), result)
    }

    suspend fun triggerSync(identifier: String): ProjectSyncTriggerResponse =
        triggerSync(ProjectId(identifier))

    suspend fun triggerSync(projectId: ProjectId): ProjectSyncTriggerResponse {
        val body = json.encodeToString(
            ProjectSyncTriggerRequest.serializer(),
            ProjectSyncTriggerRequest(projectId = projectId),
        )
        val result = rpc(projectRpc("project.triggerSync", "/api/sync/trigger", body))
            ?: error("Iroh admin_rpc project.triggerSync returned no result")
        return json.decodeFromJsonElement(ProjectSyncTriggerResponse.serializer(), result)
    }

    suspend fun createProject(name: String?, filesystemPath: String, gitUrl: String?): ProjectSummary =
        createProject(ProjectCreateRpcParams(name = name, filesystemPath = filesystemPath, gitUrl = gitUrl))

    suspend fun createProject(params: ProjectCreateRpcParams): ProjectSummary {
        val body = json.encodeToString(
            ProjectCreateRequest.serializer(),
            ProjectCreateRequest(
                name = params.name,
                filesystemPath = params.filesystemPath,
                gitUrl = params.gitUrl,
            ),
        )
        val result = rpc(projectRpc("project.create", "/api/registry/projects", body))
            ?: error("Iroh admin_rpc project.create returned no result")
        return json.decodeFromJsonElement(ProjectMutationResponse.serializer(), result).project
    }

    suspend fun updateProject(identifier: String, filesystemPath: String?, gitUrl: String?): ProjectSummary =
        updateProject(
            ProjectUpdateRpcParams(
                projectId = ProjectId(identifier),
                filesystemPath = filesystemPath,
                gitUrl = gitUrl,
            ),
        )

    suspend fun updateProject(params: ProjectUpdateRpcParams): ProjectSummary {
        val body = json.encodeToString(
            ProjectUpdateRequest.serializer(),
            ProjectUpdateRequest(filesystemPath = params.filesystemPath, gitUrl = params.gitUrl),
        )
        return mutateProject(params.projectId, "project.update", body)
    }

    suspend fun archiveProject(identifier: String): ProjectSummary =
        archiveProject(ProjectId(identifier))

    suspend fun archiveProject(projectId: ProjectId): ProjectSummary {
        val body = json.encodeToString(ProjectUpdateRequest.serializer(), ProjectUpdateRequest(status = "archived"))
        return mutateProject(projectId, "project.archive", body)
    }

    suspend fun deleteProject(identifier: String) = deleteProject(ProjectId(identifier))

    suspend fun deleteProject(projectId: ProjectId) {
        val project = ProjectRpcRef(projectId)
        rpc(projectRpc("project.delete", project.registryPath(), project.idBody()))
    }

    private suspend fun mutateProject(projectId: ProjectId, method: String, body: String): ProjectSummary {
        val project = ProjectRpcRef(projectId)
        val result = rpc(projectRpc(method, project.registryPath(), project.mergeIdBody(body)))
            ?: error("Iroh admin_rpc $method returned no result")
        return json.decodeFromJsonElement(ProjectMutationResponse.serializer(), result).project
    }

    private fun ProjectRpcRef.idBody(): String = buildJsonObject {
        put("identifier", id.value)
    }.toString()

    private fun ProjectRpcRef.mergeIdBody(body: String): String {
        val payload = json.parseToJsonElement(body).jsonObject
        return buildJsonObject {
            put("identifier", id.value)
            payload.forEach { (key, value) -> put(key, value) }
        }.toString()
    }

    private fun projectRpc(method: String, path: String, body: String? = null): AdminRpcCall =
        AdminRpcCall.of(AdminRpcMethod(method), AdminRpcPath(path), body)

    private suspend fun rpc(call: AdminRpcCall): JsonElement? {
        val response = channelTransport.adminRpc(method = call.method, path = call.path, body = call.body)
        if (!response.success) error(response.error ?: "Iroh admin_rpc ${call.method} failed")
        return response.result
    }

    private data class ProjectRpcRef(val id: ProjectId) {
        fun apiPath(suffix: String = ""): String = "/api/projects/${id.value}$suffix"
        fun registryPath(): String = "/api/registry/projects/${id.value}"
    }
}

/** Param bag for project.create admin_rpc. */
data class ProjectCreateRpcParams(
    val name: String?,
    val filesystemPath: String,
    val gitUrl: String?,
)

/** Param bag for project.update admin_rpc. */
data class ProjectUpdateRpcParams(
    val projectId: ProjectId,
    val filesystemPath: String?,
    val gitUrl: String?,
)

@kotlinx.serialization.Serializable
private data class BeadsRemoteProvisionRequest(val push: Boolean = true)

@kotlinx.serialization.Serializable
private data class ProjectMutationResponse(val project: ProjectSummary)
