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
import kotlin.jvm.JvmInline
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
open class IrohAdminRpcProjectSource(
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
        refreshProjects(ProjectListLimit(1))
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    open suspend fun refreshProjects(limit: ProjectListLimit? = null): ProjectCatalog {
        val path = ProjectRpcPaths.list(limit)
        val body = ProjectRpcBodies.listLimit(limit)
        val result = rpc(projectRpc(ProjectRpcMethods.List, path, body))
            ?: return ProjectCatalog(projects = emptyList())
        return json.decodeFromJsonElement(ProjectCatalog.serializer(), result)
    }

    suspend fun getProject(projectId: ProjectId): ProjectSummary {
        val project = ProjectRpcRef(projectId)
        val result = rpc(projectRpc(ProjectRpcMethods.Get, project.apiPath(), project.idBody()))
            ?: error("Iroh admin_rpc project.get returned no result")
        return json.decodeFromJsonElement(ProjectDetailResponse.serializer(), result).project
    }

    suspend fun getBeadsRemoteStatus(projectId: ProjectId): BeadsRemoteStatus {
        val project = ProjectRpcRef(projectId)
        val result = rpc(
            projectRpc(
                ProjectRpcMethods.BeadsRemoteStatus,
                project.apiPath(ProjectRpcPaths.BeadsRemoteSuffix),
                project.idBody(),
            ),
        ) ?: error("Iroh admin_rpc project.beadsRemoteStatus returned no result")
        return json.decodeFromJsonElement(BeadsRemoteStatus.serializer(), result)
    }

    suspend fun provisionBeadsRemote(
        projectId: ProjectId,
        push: Boolean = true,
    ): BeadsRemoteProvisionResponse {
        val project = ProjectRpcRef(projectId)
        val body = json.encodeToString(
            BeadsRemoteProvisionRequest.serializer(),
            BeadsRemoteProvisionRequest(push = push),
        )
        val result = rpc(
            projectRpc(
                ProjectRpcMethods.ProvisionBeadsRemote,
                project.apiPath(ProjectRpcPaths.BeadsRemoteProvisionSuffix),
                project.mergeIdBody(AdminRpcJsonBody(body)),
            ),
        ) ?: error("Iroh admin_rpc project.provisionBeadsRemote returned no result")
        return json.decodeFromJsonElement(BeadsRemoteProvisionResponse.serializer(), result)
    }

    suspend fun triggerSync(projectId: ProjectId): ProjectSyncTriggerResponse {
        val body = json.encodeToString(
            ProjectSyncTriggerRequest.serializer(),
            ProjectSyncTriggerRequest(projectId = projectId),
        )
        val result = rpc(
            projectRpc(ProjectRpcMethods.TriggerSync, ProjectRpcPaths.SyncTrigger, AdminRpcJsonBody(body)),
        ) ?: error("Iroh admin_rpc project.triggerSync returned no result")
        return json.decodeFromJsonElement(ProjectSyncTriggerResponse.serializer(), result)
    }

    suspend fun createProject(params: ProjectCreateRpcParams): ProjectSummary {
        val body = json.encodeToString(
            ProjectCreateRequest.serializer(),
            ProjectCreateRequest(
                name = params.name?.value,
                filesystemPath = params.filesystemPath.value,
                gitUrl = params.gitUrl?.value,
            ),
        )
        val result = rpc(
            projectRpc(ProjectRpcMethods.Create, ProjectRpcPaths.RegistryProjects, AdminRpcJsonBody(body)),
        ) ?: error("Iroh admin_rpc project.create returned no result")
        return json.decodeFromJsonElement(ProjectMutationResponse.serializer(), result).project
    }

    suspend fun updateProject(params: ProjectUpdateRpcParams): ProjectSummary {
        val body = json.encodeToString(
            ProjectUpdateRequest.serializer(),
            ProjectUpdateRequest(
                filesystemPath = params.filesystemPath?.value,
                gitUrl = params.gitUrl?.value,
            ),
        )
        return mutateProject(
            ProjectMutationRpcParams(
                projectId = params.projectId,
                method = ProjectRpcMethods.Update,
                body = AdminRpcJsonBody(body),
            ),
        )
    }

    suspend fun archiveProject(projectId: ProjectId): ProjectSummary {
        val body = json.encodeToString(
            ProjectUpdateRequest.serializer(),
            ProjectUpdateRequest(status = ProjectStatusTokens.Archived.value),
        )
        return mutateProject(
            ProjectMutationRpcParams(
                projectId = projectId,
                method = ProjectRpcMethods.Archive,
                body = AdminRpcJsonBody(body),
            ),
        )
    }

    suspend fun deleteProject(projectId: ProjectId) {
        val project = ProjectRpcRef(projectId)
        rpc(projectRpc(ProjectRpcMethods.Delete, project.registryPath(), project.idBody()))
    }

    private suspend fun mutateProject(params: ProjectMutationRpcParams): ProjectSummary {
        val project = ProjectRpcRef(params.projectId)
        val result = rpc(
            projectRpc(params.method, project.registryPath(), project.mergeIdBody(params.body)),
        ) ?: error("Iroh admin_rpc ${params.method.value} returned no result")
        return json.decodeFromJsonElement(ProjectMutationResponse.serializer(), result).project
    }

    private fun ProjectRpcRef.idBody(): AdminRpcJsonBody = AdminRpcJsonBody(
        buildJsonObject {
            put(ProjectRpcJsonKeys.Identifier.value, id.value)
        }.toString(),
    )

    private fun ProjectRpcRef.mergeIdBody(body: AdminRpcJsonBody): AdminRpcJsonBody {
        val payload = json.parseToJsonElement(body.value).jsonObject
        return AdminRpcJsonBody(
            buildJsonObject {
                put(ProjectRpcJsonKeys.Identifier.value, id.value)
                payload.forEach { (key, value) -> put(key, value) }
            }.toString(),
        )
    }

    private fun projectRpc(
        method: AdminRpcMethod,
        path: AdminRpcPath,
        body: AdminRpcJsonBody? = null,
    ): AdminRpcCall = AdminRpcCall.of(method, path, body?.value)

    private suspend fun rpc(call: AdminRpcCall): JsonElement? {
        val response = channelTransport.adminRpc(method = call.method, path = call.path, body = call.body)
        if (!response.success) error(response.error ?: "Iroh admin_rpc ${call.method} failed")
        return response.result
    }

    private data class ProjectRpcRef(val id: ProjectId) {
        fun apiPath(suffix: ProjectPathSuffix = ProjectPathSuffix.None): AdminRpcPath =
            AdminRpcPath("/api/projects/${id.value}${suffix.value}")

        fun registryPath(): AdminRpcPath = AdminRpcPath("/api/registry/projects/${id.value}")
    }
}

@JvmInline
value class ProjectFilesystemPath(val value: String)

@JvmInline
value class ProjectGitUrl(val value: String)

@JvmInline
value class ProjectDisplayName(val value: String)

/** Param bag for project.create admin_rpc. */
data class ProjectCreateRpcParams(
    val name: ProjectDisplayName?,
    val filesystemPath: ProjectFilesystemPath,
    val gitUrl: ProjectGitUrl?,
)

/** Param bag for project.update admin_rpc. */
data class ProjectUpdateRpcParams(
    val projectId: ProjectId,
    val filesystemPath: ProjectFilesystemPath?,
    val gitUrl: ProjectGitUrl?,
)

@JvmInline
value class ProjectListLimit(val value: Int)

@JvmInline
value class AdminRpcJsonBody(val value: String)

@JvmInline
value class ProjectPathSuffix(val value: String) {
    companion object {
        val None = ProjectPathSuffix("")
    }
}

@JvmInline
value class ProjectStatusToken(val value: String)

@JvmInline
value class ProjectRpcJsonKey(val value: String)

private data class ProjectMutationRpcParams(
    val projectId: ProjectId,
    val method: AdminRpcMethod,
    val body: AdminRpcJsonBody,
)

private object ProjectRpcMethods {
    val List = AdminRpcMethod("project.list")
    val Get = AdminRpcMethod("project.get")
    val BeadsRemoteStatus = AdminRpcMethod("project.beadsRemoteStatus")
    val ProvisionBeadsRemote = AdminRpcMethod("project.provisionBeadsRemote")
    val TriggerSync = AdminRpcMethod("project.triggerSync")
    val Create = AdminRpcMethod("project.create")
    val Update = AdminRpcMethod("project.update")
    val Archive = AdminRpcMethod("project.archive")
    val Delete = AdminRpcMethod("project.delete")
}

private object ProjectRpcPaths {
    val SyncTrigger = AdminRpcPath("/api/sync/trigger")
    val RegistryProjects = AdminRpcPath("/api/registry/projects")
    val BeadsRemoteSuffix = ProjectPathSuffix("/beads-remote")
    val BeadsRemoteProvisionSuffix = ProjectPathSuffix("/beads-remote/provision")

    fun list(limit: ProjectListLimit?): AdminRpcPath {
        val query = limit?.let { "?limit=${it.value}" }.orEmpty()
        return AdminRpcPath("/api/projects$query")
    }
}

private object ProjectRpcBodies {
    fun listLimit(limit: ProjectListLimit?): AdminRpcJsonBody? =
        limit?.let {
            AdminRpcJsonBody(
                buildJsonObject {
                    put(ProjectRpcJsonKeys.Limit.value, it.value)
                }.toString(),
            )
        }
}

private object ProjectStatusTokens {
    val Archived = ProjectStatusToken("archived")
}

private object ProjectRpcJsonKeys {
    val Identifier = ProjectRpcJsonKey("identifier")
    val Limit = ProjectRpcJsonKey("limit")
}

@kotlinx.serialization.Serializable
private data class BeadsRemoteProvisionRequest(val push: Boolean = true)

@kotlinx.serialization.Serializable
private data class ProjectMutationResponse(val project: ProjectSummary)
