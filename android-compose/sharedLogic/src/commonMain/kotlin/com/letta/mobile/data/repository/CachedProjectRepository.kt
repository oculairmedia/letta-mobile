package com.letta.mobile.data.repository

import com.letta.mobile.data.model.BeadsRemoteProvisionResponse
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.model.ProjectSyncTriggerResponse
import com.letta.mobile.data.repository.api.IProjectRepository
import com.letta.mobile.data.repository.api.ProjectIrohSource
import com.letta.mobile.data.repository.api.ProjectRemoteSource
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant

/** Phase 5n: platform-neutral cached project repository. */
open class CachedProjectRepository(
    private val remote: ProjectRemoteSource,
    private val irohProjectSource: ProjectIrohSource? = null,
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
            http = { remote.listProjects() },
        ).sanitize()
        _projects.value = catalog.projects
        lastRefreshAtMillis = Clock.System.now().toEpochMilliseconds()
        return catalog
    }

    override suspend fun getProject(identifier: String): ProjectSummary {
        val cached = _projects.value.firstOrNull { it.identifier == identifier }
        if (cached != null) return cached

        val fresh = fromActiveSource(
            iroh = { it.getProject(identifier) },
            http = { remote.getProject(identifier) },
        ).sanitize()
        upsertProject(fresh)
        return fresh
    }

    override suspend fun getBeadsRemoteStatus(identifier: String): BeadsRemoteStatus {
        return fromActiveSource(
            iroh = { it.getBeadsRemoteStatus(identifier) },
            http = { remote.getBeadsRemoteStatus(identifier) },
        ).sanitize()
    }

    override suspend fun provisionBeadsRemote(identifier: String, push: Boolean): BeadsRemoteProvisionResponse {
        return fromActiveSource(
            iroh = { it.provisionBeadsRemote(identifier, push) },
            http = { remote.provisionBeadsRemote(identifier, push) },
        )
    }

    override suspend fun triggerSync(identifier: String): ProjectSyncTriggerResponse {
        return fromActiveSource(
            iroh = { it.triggerSync(identifier) },
            http = { remote.triggerSync(identifier) },
        )
    }

    override suspend fun createProject(
        name: String?,
        filesystemPath: String,
        gitUrl: String?,
    ): ProjectSummary {
        val created = fromActiveSource(
            iroh = { it.createProject(name, filesystemPath, gitUrl) },
            http = { remote.createProject(name, filesystemPath, gitUrl) },
        ).sanitize()
        _projects.update { current ->
            (current + created)
                .distinctBy { it.identifier }
                .sortedWith(compareBy { it.name.lowercase() })
        }
        lastRefreshAtMillis = Clock.System.now().toEpochMilliseconds()
        return created
    }

    override suspend fun updateProject(
        identifier: String,
        filesystemPath: String?,
        gitUrl: String?,
    ): ProjectSummary {
        val updated = fromActiveSource(
            iroh = { it.updateProject(identifier, filesystemPath, gitUrl) },
            http = { remote.updateProject(identifier, filesystemPath, gitUrl) },
        ).sanitize()
        upsertProject(updated)
        lastRefreshAtMillis = Clock.System.now().toEpochMilliseconds()
        return updated
    }

    override suspend fun archiveProject(identifier: String): ProjectSummary {
        val updated = fromActiveSource(
            iroh = { it.archiveProject(identifier) },
            http = { remote.archiveProject(identifier) },
        ).sanitize()
        upsertProject(updated)
        lastRefreshAtMillis = Clock.System.now().toEpochMilliseconds()
        return updated
    }

    override suspend fun deleteProject(identifier: String) {
        fromActiveSource(
            iroh = { it.deleteProject(identifier) },
            http = { remote.deleteProject(identifier) },
        )
        _projects.update { current -> current.filterNot { it.identifier == identifier } }
        lastRefreshAtMillis = Clock.System.now().toEpochMilliseconds()
    }

    override fun hasFreshProjects(maxAgeMs: Long): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        return _projects.value.isNotEmpty() && now - lastRefreshAtMillis <= maxAgeMs
    }

    override suspend fun refreshProjectsIfStale(maxAgeMs: Long): Boolean = refreshMutex.withLock {
        if (hasFreshProjects(maxAgeMs)) return@withLock false
        refreshProjectsLocked()
        true
    }

    private suspend fun <T> fromActiveSource(
        iroh: suspend (ProjectIrohSource) -> T,
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

    private fun sanitizeGitUrl(raw: String): String =
        GIT_URL_CREDENTIALS_REGEX.replace(raw, "")

    private fun normalizeTimestamp(value: String?): String? {
        if (value == null) return null
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it).toString() } ?: trimmed
    }

    private companion object {
        val GIT_URL_CREDENTIALS_REGEX = Regex("(?<=://)[^/@]+@")
    }
}
