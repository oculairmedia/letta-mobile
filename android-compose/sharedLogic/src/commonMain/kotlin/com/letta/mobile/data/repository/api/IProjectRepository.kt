package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.BeadsRemoteProvisionResponse
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.model.ProjectSyncTriggerResponse
import kotlinx.coroutines.flow.StateFlow

interface IProjectRepository {
    val projects: StateFlow<List<ProjectSummary>>

    suspend fun refreshProjects(): ProjectCatalog
    suspend fun getProject(identifier: String): ProjectSummary
    suspend fun getBeadsRemoteStatus(identifier: String): BeadsRemoteStatus
    suspend fun provisionBeadsRemote(identifier: String, push: Boolean = true): BeadsRemoteProvisionResponse
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
    fun hasFreshProjects(maxAgeMs: Long): Boolean
    suspend fun refreshProjectsIfStale(maxAgeMs: Long): Boolean
}
