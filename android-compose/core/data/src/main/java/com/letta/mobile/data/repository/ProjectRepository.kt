package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ProjectApi
import com.letta.mobile.data.api.ProjectCreateRequest
import com.letta.mobile.data.api.ProjectUpdateRequest
import com.letta.mobile.data.model.BeadsRemoteProvisionResponse
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.model.ProjectSyncTriggerResponse
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.repository.api.IProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.net.URI
import javax.inject.Inject

open class ProjectRepository @Inject constructor(
    private val projectApi: ProjectApi,
    private val irohProjectSource: IrohAdminRpcProjectSource? = null,
) : IProjectRepository {
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    override val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    private val refreshMutex = Mutex()
    private var lastRefreshAtMillis: Long = 0L

    override suspend fun refreshProjects(): ProjectCatalog = refreshMutex.withLock {
        refreshProjectsLocked()
    }

    private suspend fun refreshProjectsLocked(): ProjectCatalog {
        val catalog = fromActiveSource(
            iroh = { it.refreshProjects() },
            http = { projectApi.listProjects() },
        ).sanitize()
        _projects.value = catalog.projects
        lastRefreshAtMillis = System.currentTimeMillis()
        return catalog
    }

    override suspend fun getProject(identifier: String): ProjectSummary {
        val cached = _projects.value.firstOrNull { it.identifier == identifier }
        if (cached != null) return cached

        val fresh = fromActiveSource(
            iroh = { it.getProject(ProjectId(identifier)) },
            http = { projectApi.getProject(identifier) },
        ).sanitize()
        upsertProject(fresh)
        return fresh
    }

    override suspend fun getBeadsRemoteStatus(identifier: String): BeadsRemoteStatus {
        return fromActiveSource(
            iroh = { it.getBeadsRemoteStatus(ProjectId(identifier)) },
            http = { projectApi.getBeadsRemoteStatus(identifier) },
        ).sanitize()
    }

    override suspend fun provisionBeadsRemote(identifier: String, push: Boolean): BeadsRemoteProvisionResponse {
        return fromActiveSource(
            iroh = { it.provisionBeadsRemote(ProjectId(identifier), push) },
            http = { projectApi.provisionBeadsRemote(identifier, push) },
        )
    }

    override suspend fun triggerSync(identifier: String): ProjectSyncTriggerResponse {
        return fromActiveSource(
            iroh = { it.triggerSync(ProjectId(identifier)) },
            http = { projectApi.triggerSync(identifier) },
        )
    }

    override suspend fun createProject(
        name: String?,
        filesystemPath: String,
        gitUrl: String?,
    ): ProjectSummary {
        val created = fromActiveSource(
            iroh = {
                it.createProject(
                    ProjectCreateRpcParams(
                        name = name?.let(::ProjectDisplayName),
                        filesystemPath = ProjectFilesystemPath(filesystemPath),
                        gitUrl = gitUrl?.let(::ProjectGitUrl),
                    ),
                )
            },
            http = {
                projectApi.createProject(
                    ProjectCreateRequest(
                        name = name,
                        filesystemPath = filesystemPath,
                        gitUrl = gitUrl,
                    ),
                )
            },
        ).sanitize()
        _projects.update { current ->
            (current + created)
                .distinctBy { it.identifier }
                .sortedWith(compareBy<ProjectSummary> { it.name.lowercase() })
        }
        lastRefreshAtMillis = System.currentTimeMillis()
        return created
    }

    override suspend fun updateProject(
        identifier: String,
        filesystemPath: String?,
        gitUrl: String?,
    ): ProjectSummary {
        val updated = fromActiveSource(
            iroh = {
                it.updateProject(
                    ProjectUpdateRpcParams(
                        projectId = ProjectId(identifier),
                        filesystemPath = filesystemPath?.let(::ProjectFilesystemPath),
                        gitUrl = gitUrl?.let(::ProjectGitUrl),
                    ),
                )
            },
            http = {
                projectApi.updateProject(
                    identifier = identifier,
                    request = ProjectUpdateRequest(
                        filesystemPath = filesystemPath,
                        gitUrl = gitUrl,
                    ),
                )
            },
        ).sanitize()
        upsertProject(updated)
        lastRefreshAtMillis = System.currentTimeMillis()
        return updated
    }

    override suspend fun archiveProject(identifier: String): ProjectSummary {
        val updated = fromActiveSource(
            iroh = { it.archiveProject(ProjectId(identifier)) },
            http = { projectApi.archiveProject(identifier) },
        ).sanitize()
        upsertProject(updated)
        lastRefreshAtMillis = System.currentTimeMillis()
        return updated
    }

    override suspend fun deleteProject(identifier: String) {
        fromActiveSource(
            iroh = { it.deleteProject(ProjectId(identifier)) },
            http = { projectApi.deleteProject(identifier) },
        )
        _projects.update { current -> current.filterNot { it.identifier == identifier } }
        lastRefreshAtMillis = System.currentTimeMillis()
    }

    override fun hasFreshProjects(maxAgeMs: Long): Boolean {
        return _projects.value.isNotEmpty() && System.currentTimeMillis() - lastRefreshAtMillis <= maxAgeMs
    }

    override suspend fun refreshProjectsIfStale(maxAgeMs: Long): Boolean = refreshMutex.withLock {
        if (hasFreshProjects(maxAgeMs)) return@withLock false
        refreshProjectsLocked()
        true
    }

    private suspend fun <T> fromActiveSource(
        iroh: suspend (IrohAdminRpcProjectSource) -> T,
        http: suspend () -> T,
    ): T {
        val source = irohProjectSource
        return if (source != null && source.shouldUseIroh()) iroh(source) else http()
    }

    private fun upsertProject(project: ProjectSummary) {
        _projects.update { current ->
            val index = current.indexOfFirst { it.identifier == project.identifier }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = project }
            } else {
                current + project
            }
        }
    }

    private fun ProjectCatalog.sanitize(): ProjectCatalog = copy(
        projects = projects.map { it.sanitize() },
    )

    private fun ProjectSummary.sanitize(): ProjectSummary = copy(
        filesystemPath = filesystemPath ?: repo?.filesystemPath,
        gitUrl = (gitUrl ?: repo?.remoteUrl)?.let(::sanitizeGitUrl),
        lettaAgentId = lettaAgentId ?: agents?.defaultAgentId,
        issueCount = issueCount ?: tracker?.summary?.totalKnown,
        beadsIssueCount = beadsIssueCount ?: tracker?.summary?.totalKnown,
        updatedAt = normalizeTimestamp(updatedAt),
        lastScanAt = normalizeTimestamp(lastScanAt),
        lastSyncAt = normalizeTimestamp(lastSyncAt ?: tracker?.dataFreshness?.lastSyncAt),
        lastCheckedAt = normalizeTimestamp(lastCheckedAt),
        lastActivityAt = normalizeTimestamp(lastActivityAt),
        beadsRemote = beadsRemote?.sanitize(),
    )

    private fun BeadsRemoteStatus.sanitize(): BeadsRemoteStatus = copy(
        provisionedAt = normalizeTimestamp(provisionedAt),
    )

    private fun sanitizeGitUrl(raw: String): String {
        return runCatching {
            val uri = URI(raw)
            if (uri.userInfo.isNullOrBlank()) return raw
            URI(
                uri.scheme,
                null,
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment,
            ).toString()
        }.getOrDefault(raw)
    }

    private fun normalizeTimestamp(value: String?): String? {
        if (value == null) return null
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.toLongOrNull()?.let { Instant.ofEpochMilli(it).toString() } ?: trimmed
    }
}
