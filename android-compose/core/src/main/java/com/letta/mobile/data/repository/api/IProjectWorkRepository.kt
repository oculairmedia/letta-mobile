package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.IssueAnalyticsResponse
import com.letta.mobile.data.model.ProjectIssueAnalyticsParams
import com.letta.mobile.data.model.ProjectIssueDetail
import com.letta.mobile.data.model.ProjectIssueListParams
import com.letta.mobile.data.model.ProjectIssueListResponse
import com.letta.mobile.data.model.ProjectIssueSummary
import kotlinx.coroutines.flow.StateFlow

interface IProjectWorkRepository {
    val readyWorkByProject: StateFlow<Map<String, List<ProjectIssueSummary>>>
    val issuesByProject: StateFlow<Map<String, List<ProjectIssueSummary>>>
    val issueDetails: StateFlow<Map<String, ProjectIssueDetail>>
    val issueAnalyticsByProject: StateFlow<Map<String, IssueAnalyticsResponse>>

    suspend fun refreshReadyWork(projectId: String, limit: Int? = null, cursor: String? = null): List<ProjectIssueSummary>
    suspend fun refreshIssues(
        projectId: String,
        params: ProjectIssueListParams = ProjectIssueListParams(),
    ): List<ProjectIssueSummary>

    suspend fun refreshIssuePage(
        projectId: String,
        params: ProjectIssueListParams = ProjectIssueListParams(),
    ): ProjectIssueListResponse

    suspend fun refreshIssueAnalytics(
        projectId: String,
        params: ProjectIssueAnalyticsParams,
    ): IssueAnalyticsResponse

    suspend fun getIssue(issueId: String, forceRefresh: Boolean = false): ProjectIssueDetail
    suspend fun invalidateProjectCache(projectId: String)
    suspend fun claimIssue(
        issueId: String,
        assignee: String,
        ifMatch: String,
        idempotencyKey: String = newIdempotencyKey(),
    ): ProjectIssueSummary

    suspend fun unclaimIssue(
        issueId: String,
        ifMatch: String,
        idempotencyKey: String = newIdempotencyKey(),
    ): ProjectIssueSummary

    suspend fun updateIssueStatus(
        issueId: String,
        status: String,
        ifMatch: String,
        idempotencyKey: String = newIdempotencyKey(),
    ): ProjectIssueSummary

    suspend fun addIssueNote(
        issueId: String,
        note: String,
        ifMatch: String,
        idempotencyKey: String = newIdempotencyKey(),
    ): ProjectIssueSummary

    suspend fun closeIssue(
        issueId: String,
        reason: String,
        ifMatch: String,
        idempotencyKey: String = newIdempotencyKey(),
    ): ProjectIssueSummary

    suspend fun reopenIssue(
        issueId: String,
        reason: String,
        ifMatch: String,
        idempotencyKey: String = newIdempotencyKey(),
    ): ProjectIssueSummary

    fun newIdempotencyKey(): String
}
