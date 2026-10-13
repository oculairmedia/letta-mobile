package com.letta.mobile.testutil

import com.letta.mobile.data.backend.noAppServerRoute
import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.repository.api.ProjectWorkRemoteSource
import com.letta.mobile.data.model.IssueAnalyticsResponse
import com.letta.mobile.data.model.IssueAnalyticsSummary
import com.letta.mobile.data.model.ProjectIssueAnalyticsParams
import com.letta.mobile.data.model.ProjectIssueDetail
import com.letta.mobile.data.model.ProjectIssueDetailResponse
import com.letta.mobile.data.model.ProjectIssueListParams
import com.letta.mobile.data.model.ProjectIssueListResponse
import com.letta.mobile.data.model.ProjectIssueMutationResponse
import com.letta.mobile.data.model.ProjectIssuePage
import com.letta.mobile.data.model.ProjectIssueSummary
import com.letta.mobile.data.model.ProjectReadyWorkResponse

open class FakeProjectWorkApi : ProjectWorkRemoteSource by noAppServerRoute<ProjectWorkRemoteSource>() {
    val readyWork = mutableMapOf<String, List<ProjectIssueSummary>>()
    val issues = mutableMapOf<String, List<ProjectIssueSummary>>()
    val issueAnalytics = mutableMapOf<String, IssueAnalyticsResponse>()
    val issueDetails = mutableMapOf<String, ProjectIssueDetail>()
    val calls = mutableListOf<String>()
    val mutationHeaders = mutableListOf<MutationHeaders>()
    var shouldFail = false

    override suspend fun getReadyWork(projectId: String, limit: Int?, cursor: String?): ProjectReadyWorkResponse {
        calls.add("getReadyWork:$projectId:$limit:$cursor")
        if (shouldFail) throw ApiException(500, "Server error")
        val items = readyWork[projectId].orEmpty()
        return ProjectReadyWorkResponse(
            projectId = projectId,
            readyWork = items,
            page = ProjectIssuePage(limit = limit ?: items.size, hasMore = false, totalKnown = items.size),
        )
    }

    override suspend fun listIssues(projectId: String, params: ProjectIssueListParams): ProjectIssueListResponse {
        calls.add("listIssues:$projectId:${params.ready}:${params.status}:${params.limit}:${params.cursor}")
        if (shouldFail) throw ApiException(500, "Server error")
        val items = issues[projectId].orEmpty()
        val start = params.cursor?.toIntOrNull() ?: 0
        val limit = params.limit ?: items.size
        val pageItems = items.drop(start).take(limit)
        val nextOffset = start + pageItems.size
        val hasMore = nextOffset < items.size
        return ProjectIssueListResponse(
            projectId = projectId,
            issues = pageItems,
            page = ProjectIssuePage(
                limit = limit,
                nextCursor = nextOffset.toString().takeIf { hasMore },
                hasMore = hasMore,
                totalKnown = items.size,
            ),
        )
    }

    override suspend fun getIssueAnalytics(
        projectId: String,
        params: ProjectIssueAnalyticsParams,
    ): IssueAnalyticsResponse {
        calls.add("getIssueAnalytics:$projectId:${params.granularity}:${params.timelineLimit}")
        if (shouldFail) throw ApiException(500, "Server error")
        return issueAnalytics[projectId] ?: IssueAnalyticsResponse(
            projectId = projectId,
            rangeStart = params.rangeStart,
            rangeEnd = params.rangeEnd,
            granularity = params.granularity,
            timezone = params.timezone,
            summary = IssueAnalyticsSummary(),
        )
    }

    override suspend fun getIssue(issueId: String): ProjectIssueDetailResponse {
        calls.add("getIssue:$issueId")
        if (shouldFail) throw ApiException(500, "Server error")
        return ProjectIssueDetailResponse(
            issue = issueDetails[issueId] ?: throw ApiException(404, "Not found"),
        )
    }

    override suspend fun claimIssue(
        issueId: String,
        assignee: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueMutationResponse = recordMutation(
        MutationCall("claimIssue:$issueId:$assignee", MutationHeaders(ifMatch, idempotencyKey), issueId, "claim"),
    ) { it.copy(assignee = assignee) }

    override suspend fun unclaimIssue(
        issueId: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueMutationResponse = recordMutation(
        MutationCall("unclaimIssue:$issueId", MutationHeaders(ifMatch, idempotencyKey), issueId, "unclaim"),
    ) { it.copy(assignee = null) }

    override suspend fun updateIssueStatus(
        issueId: String,
        status: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueMutationResponse = recordMutation(
        MutationCall("updateIssueStatus:$issueId:$status", MutationHeaders(ifMatch, idempotencyKey), issueId, "status"),
    ) { it.copy(status = status) }

    override suspend fun addIssueNote(
        issueId: String,
        note: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueMutationResponse = recordMutation(
        MutationCall("addIssueNote:$issueId:$note", MutationHeaders(ifMatch, idempotencyKey), issueId, "note"),
    ) { it }

    override suspend fun closeIssue(
        issueId: String,
        reason: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueMutationResponse = recordMutation(
        MutationCall("closeIssue:$issueId:$reason", MutationHeaders(ifMatch, idempotencyKey), issueId, "close"),
    ) { it.copy(status = "closed", ready = false) }

    override suspend fun reopenIssue(
        issueId: String,
        reason: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueMutationResponse = recordMutation(
        MutationCall("reopenIssue:$issueId:$reason", MutationHeaders(ifMatch, idempotencyKey), issueId, "reopen"),
    ) { it.copy(status = "open") }

    /** One recorded issue mutation: what was called, with which headers, on which issue. */
    data class MutationCall(val call: String, val headers: MutationHeaders, val issueId: String, val action: String)

    private fun recordMutation(
        mutation: MutationCall,
        transform: (ProjectIssueSummary) -> ProjectIssueSummary,
    ): ProjectIssueMutationResponse {
        calls.add(mutation.call)
        mutationHeaders.add(mutation.headers)
        return mutation(mutation.issueId, action = mutation.action, transform = transform)
    }

    /** The optimistic-concurrency headers a mutation carried. */
    data class MutationHeaders(val ifMatch: String, val idempotencyKey: String)

    private fun mutation(
        issueId: String,
        action: String,
        transform: (ProjectIssueSummary) -> ProjectIssueSummary,
    ): ProjectIssueMutationResponse {
        val current = issues.values.flatten().firstOrNull { it.id == issueId }
            ?: readyWork.values.flatten().firstOrNull { it.id == issueId }
            ?: throw ApiException(404, "Not found")
        val updated = transform(current)
        issues.replaceAll { _, values -> values.map { if (it.id == issueId) updated else it } }
        readyWork.replaceAll { _, values -> values.map { if (it.id == issueId) updated else it } }
        return ProjectIssueMutationResponse(
            issueId = issueId,
            action = action,
            applied = true,
            issue = updated,
        )
    }
}
