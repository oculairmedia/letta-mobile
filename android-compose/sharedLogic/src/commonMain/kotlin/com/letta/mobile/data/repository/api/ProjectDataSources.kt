package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.BeadsRemoteProvisionResponse
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.model.ProjectSyncTriggerResponse

/**
 * Remote HTTP project admin surface used by
 * [com.letta.mobile.data.repository.CachedProjectRepository].
 * Platform modules supply Ktor/[ProjectApi] bindings; Iroh traffic goes through
 * [ProjectIrohSource].
 */
interface ProjectRemoteSource {
    suspend fun listProjects(): ProjectCatalog
    suspend fun getProject(identifier: String): ProjectSummary
    suspend fun getBeadsRemoteStatus(identifier: String): BeadsRemoteStatus
    suspend fun provisionBeadsRemote(identifier: String, push: Boolean): BeadsRemoteProvisionResponse
    suspend fun triggerSync(identifier: String): ProjectSyncTriggerResponse
    suspend fun createProject(
        name: String?,
        filesystemPath: String,
        gitUrl: String?,
    ): ProjectSummary

    suspend fun updateProject(
        identifier: String,
        filesystemPath: String?,
        gitUrl: String?,
    ): ProjectSummary

    suspend fun archiveProject(identifier: String): ProjectSummary
    suspend fun deleteProject(identifier: String)
}

/**
 * Iroh admin_rpc project surface. Implemented by
 * [com.letta.mobile.data.repository.IrohAdminRpcProjectSource].
 */
interface ProjectIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun refreshProjects(): ProjectCatalog
    suspend fun getProject(identifier: String): ProjectSummary
    suspend fun getBeadsRemoteStatus(identifier: String): BeadsRemoteStatus
    suspend fun provisionBeadsRemote(identifier: String, push: Boolean): BeadsRemoteProvisionResponse
    suspend fun triggerSync(identifier: String): ProjectSyncTriggerResponse
    suspend fun createProject(
        name: String?,
        filesystemPath: String,
        gitUrl: String?,
    ): ProjectSummary

    suspend fun updateProject(
        identifier: String,
        filesystemPath: String?,
        gitUrl: String?,
    ): ProjectSummary

    suspend fun archiveProject(identifier: String): ProjectSummary
    suspend fun deleteProject(identifier: String)
}
