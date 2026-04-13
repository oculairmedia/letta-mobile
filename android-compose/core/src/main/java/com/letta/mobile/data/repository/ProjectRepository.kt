package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ProjectApi
import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.model.ProjectSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val projectApi: ProjectApi,
) {
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    private var lastRefreshAtMillis: Long = 0L

    suspend fun refreshProjects(): ProjectCatalog {
        val catalog = projectApi.listProjects().sanitize()
        _projects.value = catalog.projects
        lastRefreshAtMillis = System.currentTimeMillis()
        return catalog
    }

    suspend fun getProject(identifier: String): ProjectSummary {
        val cached = _projects.value.firstOrNull { it.identifier == identifier }
        if (cached != null) return cached

        val fresh = projectApi.getProject(identifier).sanitize()
        _projects.update { current ->
            val index = current.indexOfFirst { it.identifier == fresh.identifier }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = fresh }
            } else {
                current + fresh
            }
        }
        return fresh
    }

    fun hasFreshProjects(maxAgeMs: Long): Boolean {
        return _projects.value.isNotEmpty() && System.currentTimeMillis() - lastRefreshAtMillis <= maxAgeMs
    }

    suspend fun refreshProjectsIfStale(maxAgeMs: Long): Boolean {
        if (hasFreshProjects(maxAgeMs)) return false
        refreshProjects()
        return true
    }

    private fun ProjectCatalog.sanitize(): ProjectCatalog = copy(
        projects = projects.map { it.sanitize() },
    )

    private fun ProjectSummary.sanitize(): ProjectSummary = copy(
        gitUrl = gitUrl?.let(::sanitizeGitUrl),
        updatedAt = normalizeTimestamp(updatedAt),
        lastScanAt = normalizeTimestamp(lastScanAt),
        lastSyncAt = normalizeTimestamp(lastSyncAt),
        lastCheckedAt = normalizeTimestamp(lastCheckedAt),
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
