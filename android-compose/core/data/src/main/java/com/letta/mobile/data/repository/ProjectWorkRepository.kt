package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ProjectIssueClaimRequest
import com.letta.mobile.data.api.ProjectIssueMutationHeaders
import com.letta.mobile.data.api.ProjectIssueNoteRequest
import com.letta.mobile.data.api.ProjectIssueReasonRequest
import com.letta.mobile.data.api.ProjectIssueStatusRequest
import com.letta.mobile.data.api.ProjectWorkApi
import com.letta.mobile.data.model.IssueAnalyticsResponse
import com.letta.mobile.data.model.ProjectIssueAnalyticsParams
import com.letta.mobile.data.model.ProjectIssueDetail
import com.letta.mobile.data.model.ProjectIssueListParams
import com.letta.mobile.data.model.ProjectIssueListResponse
import com.letta.mobile.data.model.ProjectIssueSummary
import com.letta.mobile.data.repository.api.IProjectWorkRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

open class ProjectWorkRepository @Inject constructor(
    private val projectWorkApi: ProjectWorkApi,
) : IProjectWorkRepository {
    private val _readyWorkByProject = MutableStateFlow<Map<String, List<ProjectIssueSummary>>>(emptyMap())
    override val readyWorkByProject: StateFlow<Map<String, List<ProjectIssueSummary>>> = _readyWorkByProject.asStateFlow()

    private val _issuesByProject = MutableStateFlow<Map<String, List<ProjectIssueSummary>>>(emptyMap())
    override val issuesByProject: StateFlow<Map<String, List<ProjectIssueSummary>>> = _issuesByProject.asStateFlow()

    private val _issueDetails = MutableStateFlow<Map<String, ProjectIssueDetail>>(emptyMap())
    override val issueDetails: StateFlow<Map<String, ProjectIssueDetail>> = _issueDetails.asStateFlow()

    private val _issueAnalyticsByProject = MutableStateFlow<Map<String, IssueAnalyticsResponse>>(emptyMap())
    override val issueAnalyticsByProject: StateFlow<Map<String, IssueAnalyticsResponse>> = _issueAnalyticsByProject.asStateFlow()

    private val refreshMutex = Mutex()
    private val analyticsRefreshMutex = Mutex()

    override open suspend fun refreshReadyWork(projectId: String, limit: Int?, cursor: String?): List<ProjectIssueSummary> =
        refreshMutex.withLock {
            val response = projectWorkApi.getReadyWork(projectId, limit, cursor)
            _readyWorkByProject.update { current -> current + (projectId to response.items) }
            response.items
        }

    override open suspend fun refreshIssues(
        projectId: String,
        params: ProjectIssueListParams,
    ): List<ProjectIssueSummary> = refreshMutex.withLock {
        val response = projectWorkApi.listIssues(projectId, params)
        _issuesByProject.update { current -> current + (projectId to response.items) }
        response.items
    }

    override open suspend fun refreshIssuePage(
        projectId: String,
        params: ProjectIssueListParams,
    ): ProjectIssueListResponse = refreshMutex.withLock {
        val response = projectWorkApi.listIssues(projectId, params)
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

    override open suspend fun refreshIssueAnalytics(
        projectId: String,
        params: ProjectIssueAnalyticsParams,
    ): IssueAnalyticsResponse = analyticsRefreshMutex.withLock {
        val response = projectWorkApi.getIssueAnalytics(projectId, params)
        _issueAnalyticsByProject.update { current -> current + (projectId to response) }
        response
    }

    override open suspend fun getIssue(issueId: String, forceRefresh: Boolean): ProjectIssueDetail =
        refreshMutex.withLock {
            if (!forceRefresh) {
                _issueDetails.value[issueId]?.let { return@withLock it }
            }
            val issue = projectWorkApi.getIssue(issueId).issue
            _issueDetails.update { current -> current + (issueId to issue) }
            issue
        }

    override open suspend fun invalidateProjectCache(projectId: String) {
        // Take both mutexes so invalidation can't interleave with an in-flight
        // refresh and reintroduce stale entries we just cleared.
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

    override open suspend fun claimIssue(
        issueId: String,
        assignee: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = applyMutationResult(
        projectWorkApi.claimIssue(
            issueId = issueId,
            request = ProjectIssueClaimRequest(assignee),
            headers = ProjectIssueMutationHeaders(ifMatch, idempotencyKey),
        ).issue,
    )

    override open suspend fun unclaimIssue(
        issueId: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = applyMutationResult(
        projectWorkApi.unclaimIssue(
            issueId = issueId,
            headers = ProjectIssueMutationHeaders(ifMatch, idempotencyKey),
        ).issue,
    )

    override open suspend fun updateIssueStatus(
        issueId: String,
        status: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = applyMutationResult(
        projectWorkApi.updateIssueStatus(
            issueId = issueId,
            request = ProjectIssueStatusRequest(status),
            headers = ProjectIssueMutationHeaders(ifMatch, idempotencyKey),
        ).issue,
    )

    override open suspend fun addIssueNote(
        issueId: String,
        note: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = applyMutationResult(
        projectWorkApi.addIssueNote(
            issueId = issueId,
            request = ProjectIssueNoteRequest(note),
            headers = ProjectIssueMutationHeaders(ifMatch, idempotencyKey),
        ).issue,
    )

    override open suspend fun closeIssue(
        issueId: String,
        reason: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = applyMutationResult(
        projectWorkApi.closeIssue(
            issueId = issueId,
            request = ProjectIssueReasonRequest(reason),
            headers = ProjectIssueMutationHeaders(ifMatch, idempotencyKey),
        ).issue,
    )

    override open suspend fun reopenIssue(
        issueId: String,
        reason: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = applyMutationResult(
        projectWorkApi.reopenIssue(
            issueId = issueId,
            request = ProjectIssueReasonRequest(reason),
            headers = ProjectIssueMutationHeaders(ifMatch, idempotencyKey),
        ).issue,
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
            blockedBy = if (issue.blockedBy.isNotEmpty()) issue.blockedBy else cached.blockedBy,
            blocks = if (issue.blocks.isNotEmpty()) issue.blocks else cached.blocks,
            isBlocked = issue.isBlocked,
            updatedAt = issue.updatedAt ?: cached.updatedAt,
            createdAt = issue.createdAt ?: cached.createdAt,
            summary = issue.summary ?: cached.summary,
            acceptanceCriteria = if (issue.acceptanceCriteria.isNotEmpty()) issue.acceptanceCriteria else cached.acceptanceCriteria,
            labels = if (issue.labels.isNotEmpty()) issue.labels else cached.labels,
            parentId = issue.parentId ?: cached.parentId,
            childCount = if (issue.childCount != 0) issue.childCount else cached.childCount,
            validationWarnings = if (issue.validationWarnings.isNotEmpty()) issue.validationWarnings else cached.validationWarnings,
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

    override open fun newIdempotencyKey(): String = "android-${UUID.randomUUID()}"
}
