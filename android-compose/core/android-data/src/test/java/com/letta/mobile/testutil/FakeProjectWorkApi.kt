package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ProjectIssueClaimRequest
import com.letta.mobile.data.api.ProjectIssueMutationHeaders
import com.letta.mobile.data.api.ProjectIssueNoteRequest
import com.letta.mobile.data.api.ProjectIssueReasonRequest
import com.letta.mobile.data.api.ProjectIssueStatusRequest
import com.letta.mobile.data.api.ProjectWorkApi
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
import io.mockk.mockk

class FakeProjectWorkApi : ProjectWorkApi(mockk(relaxed = true)) {
    val readyWork = mutableMapOf<String, List<ProjectIssueSummary>>()
    val issues = mutableMapOf<String, List<ProjectIssueSummary>>()
    val issueAnalytics = mutableMapOf<String, IssueAnalyticsResponse>()
    val issueDetails = mutableMapOf<String, ProjectIssueDetail>()
    val calls = mutableListOf<String>()
    val mutationHeaders = mutableListOf<ProjectIssueMutationHeaders>()
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
        request: ProjectIssueClaimRequest,
        headers: ProjectIssueMutationHeaders,
    ): ProjectIssueMutationResponse {
        calls.add("claimIssue:$issueId:${request.assignee}")
        mutationHeaders.add(headers)
        return mutation(issueId, action = "claim") { it.copy(assignee = request.assignee) }
    }

    override suspend fun unclaimIssue(
        issueId: String,
        headers: ProjectIssueMutationHeaders,
    ): ProjectIssueMutationResponse {
        calls.add("unclaimIssue:$issueId")
        mutationHeaders.add(headers)
        return mutation(issueId, action = "unclaim") { it.copy(assignee = null) }
    }

    override suspend fun updateIssueStatus(
        issueId: String,
        request: ProjectIssueStatusRequest,
        headers: ProjectIssueMutationHeaders,
    ): ProjectIssueMutationResponse {
        calls.add("updateIssueStatus:$issueId:${request.status}")
        mutationHeaders.add(headers)
        return mutation(issueId, action = "status") { it.copy(status = request.status) }
    }

    override suspend fun addIssueNote(
        issueId: String,
        request: ProjectIssueNoteRequest,
        headers: ProjectIssueMutationHeaders,
    ): ProjectIssueMutationResponse {
        calls.add("addIssueNote:$issueId:${request.text}")
        mutationHeaders.add(headers)
        return mutation(issueId, action = "note") { it }
    }

    override suspend fun closeIssue(
        issueId: String,
        request: ProjectIssueReasonRequest,
        headers: ProjectIssueMutationHeaders,
    ): ProjectIssueMutationResponse {
        calls.add("closeIssue:$issueId:${request.reason}")
        mutationHeaders.add(headers)
        return mutation(issueId, action = "close") { it.copy(status = "closed", ready = false) }
    }

    override suspend fun reopenIssue(
        issueId: String,
        request: ProjectIssueReasonRequest,
        headers: ProjectIssueMutationHeaders,
    ): ProjectIssueMutationResponse {
        calls.add("reopenIssue:$issueId:${request.reason}")
        mutationHeaders.add(headers)
        return mutation(issueId, action = "reopen") { it.copy(status = "open") }
    }

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
