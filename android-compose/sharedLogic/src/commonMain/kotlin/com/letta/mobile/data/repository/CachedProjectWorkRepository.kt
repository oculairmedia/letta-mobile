package com.letta.mobile.data.repository

import com.letta.mobile.data.model.IssueAnalyticsResponse
import com.letta.mobile.data.model.ProjectIssueAnalyticsParams
import com.letta.mobile.data.model.ProjectIssueDetail
import com.letta.mobile.data.model.ProjectIssueListParams
import com.letta.mobile.data.model.ProjectIssueListResponse
import com.letta.mobile.data.model.ProjectIssueSummary
import com.letta.mobile.data.repository.api.IProjectWorkRepository
import com.letta.mobile.data.repository.api.ProjectWorkRemoteSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Phase 5n: platform-neutral cached project work / issue repository. */
open class CachedProjectWorkRepository(
    private val remote: ProjectWorkRemoteSource,
) : IProjectWorkRepository {
    private val _readyWorkByProject = MutableStateFlow<Map<String, List<ProjectIssueSummary>>>(emptyMap())
    override val readyWorkByProject: StateFlow<Map<String, List<ProjectIssueSummary>>> = _readyWorkByProject.asStateFlow()

    private val _issuesByProject = MutableStateFlow<Map<String, List<ProjectIssueSummary>>>(emptyMap())
    override val issuesByProject: StateFlow<Map<String, List<ProjectIssueSummary>>> = _issuesByProject.asStateFlow()

    private val _issueDetails = MutableStateFlow<Map<String, ProjectIssueDetail>>(emptyMap())
    override val issueDetails: StateFlow<Map<String, ProjectIssueDetail>> = _issueDetails.asStateFlow()

    private val _issueAnalyticsByProject = MutableStateFlow<Map<String, IssueAnalyticsResponse>>(emptyMap())
    override val issueAnalyticsByProject: StateFlow<Map<String, IssueAnalyticsResponse>> =
        _issueAnalyticsByProject.asStateFlow()

    private val refreshMutex = Mutex()
    private val analyticsRefreshMutex = Mutex()

    override suspend fun refreshReadyWork(projectId: String, limit: Int?, cursor: String?): List<ProjectIssueSummary> =
        refreshMutex.withLock {
            val response = remote.getReadyWork(projectId, limit, cursor)
            _readyWorkByProject.update { current -> current + (projectId to response.items) }
            response.items
        }

    override suspend fun refreshIssues(
        projectId: String,
        params: ProjectIssueListParams,
    ): List<ProjectIssueSummary> = refreshMutex.withLock {
        val response = remote.listIssues(projectId, params)
        _issuesByProject.update { current -> current + (projectId to response.items) }
        response.items
    }

    override suspend fun refreshIssuePage(
        projectId: String,
        params: ProjectIssueListParams,
    ): ProjectIssueListResponse = refreshMutex.withLock {
        val response = remote.listIssues(projectId, params)
        _issuesByProject.update { current ->
            val mergedItems = if (params.cursor == null) {
                response.items
            } else {
                (current[projectId].orEmpty() + response.items).distinctBy(ProjectIssueSummary::id)
            }
            current + (projectId to mergedItems)
        }
        response
    }

    override suspend fun refreshIssueAnalytics(
        projectId: String,
        params: ProjectIssueAnalyticsParams,
    ): IssueAnalyticsResponse = analyticsRefreshMutex.withLock {
        val response = remote.getIssueAnalytics(projectId, params)
        _issueAnalyticsByProject.update { current -> current + (projectId to response) }
        response
    }

    override suspend fun getIssue(issueId: String, forceRefresh: Boolean): ProjectIssueDetail =
        refreshMutex.withLock {
            if (!forceRefresh) {
                _issueDetails.value[issueId]?.let { return@withLock it }
            }
            val issue = remote.getIssue(issueId).issue
            _issueDetails.update { current -> current + (issueId to issue) }
            issue
        }

    override suspend fun invalidateProjectCache(projectId: String) {
        refreshMutex.withLock {
            analyticsRefreshMutex.withLock {
                _readyWorkByProject.update { it - projectId }
                _issuesByProject.update { it - projectId }
                _issueAnalyticsByProject.update { it - projectId }
                _issueDetails.update { current ->
                    current.filterValues { detail -> detail.projectId != projectId }
                }
            }
        }
    }

    override suspend fun claimIssue(
        issueId: String,
        assignee: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = applyMutationResult(
        remote.claimIssue(issueId, assignee, ifMatch, idempotencyKey).issue,
    )

    override suspend fun unclaimIssue(
        issueId: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = applyMutationResult(
        remote.unclaimIssue(issueId, ifMatch, idempotencyKey).issue,
    )

    override suspend fun updateIssueStatus(
        issueId: String,
        status: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = applyMutationResult(
        remote.updateIssueStatus(issueId, status, ifMatch, idempotencyKey).issue,
    )

    override suspend fun addIssueNote(
        issueId: String,
        note: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = applyMutationResult(
        remote.addIssueNote(issueId, note, ifMatch, idempotencyKey).issue,
    )

    override suspend fun closeIssue(
        issueId: String,
        reason: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = applyMutationResult(
        remote.closeIssue(issueId, reason, ifMatch, idempotencyKey).issue,
    )

    override suspend fun reopenIssue(
        issueId: String,
        reason: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = applyMutationResult(
        remote.reopenIssue(issueId, reason, ifMatch, idempotencyKey).issue,
    )

    private suspend fun applyMutationResult(issue: ProjectIssueSummary): ProjectIssueSummary =
        refreshMutex.withLock {
            val mergedIssue = mergeWithCachedIssue(issue)
            _readyWorkByProject.update { current -> current.updateIssue(mergedIssue) }
            _issuesByProject.update { current -> current.updateIssue(mergedIssue) }
            mergedIssue
        }

    private fun mergeWithCachedIssue(issue: ProjectIssueSummary): ProjectIssueSummary {
        val cached = readyWorkByProject.value.values.flatten().firstOrNull { it.id == issue.id }
            ?: issuesByProject.value.values.flatten().firstOrNull { it.id == issue.id }
            ?: return issue

        return cached.copy(
            projectId = issue.projectId.ifBlank { cached.projectId },
            provider = issue.provider ?: cached.provider,
            title = issue.title.ifBlank { cached.title },
            type = issue.type ?: cached.type,
            priority = issue.priority ?: cached.priority,
            status = issue.status.ifBlank { cached.status },
            statusLabel = issue.statusLabel ?: cached.statusLabel,
            ready = when {
                issue.ready -> true
                issue.status == "closed" -> false
                else -> cached.ready
            },
            assignee = issue.assignee,
            blockedBy = issue.blockedBy.ifEmpty { cached.blockedBy },
            blocks = issue.blocks.ifEmpty { cached.blocks },
            isBlocked = issue.isBlocked,
            updatedAt = issue.updatedAt ?: cached.updatedAt,
            createdAt = issue.createdAt ?: cached.createdAt,
            summary = issue.summary ?: cached.summary,
            acceptanceCriteria = issue.acceptanceCriteria.ifEmpty { cached.acceptanceCriteria },
            labels = issue.labels.ifEmpty { cached.labels },
            parentId = issue.parentId ?: cached.parentId,
            childCount = if (issue.childCount != 0) issue.childCount else cached.childCount,
            validationWarnings = issue.validationWarnings.ifEmpty { cached.validationWarnings },
            etag = issue.etag ?: cached.etag,
        )
    }

    private fun Map<String, List<ProjectIssueSummary>>.updateIssue(
        issue: ProjectIssueSummary,
    ): Map<String, List<ProjectIssueSummary>> {
        val projectIssues = this[issue.projectId] ?: return this
        val updated = projectIssues.map { existing -> if (existing.id == issue.id) issue else existing }
        return this + (issue.projectId to updated)
    }

    @OptIn(ExperimentalUuidApi::class)
    override fun newIdempotencyKey(): String = "kmp-${Uuid.random()}"
}
