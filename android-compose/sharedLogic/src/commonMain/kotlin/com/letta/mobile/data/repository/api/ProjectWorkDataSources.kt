package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.IssueAnalyticsResponse
import com.letta.mobile.data.model.ProjectIssueAnalyticsParams
import com.letta.mobile.data.model.ProjectIssueDetailResponse
import com.letta.mobile.data.model.ProjectIssueListParams
import com.letta.mobile.data.model.ProjectIssueListResponse
import com.letta.mobile.data.model.ProjectIssueMutationResponse
import com.letta.mobile.data.model.ProjectReadyWorkResponse

/**
 * Remote HTTP project work / issue surface used by
 * [com.letta.mobile.data.repository.CachedProjectWorkRepository].
 * Platform modules supply Ktor/[ProjectWorkApi] bindings.
 */
interface ProjectWorkRemoteSource {
    suspend fun getReadyWork(projectId: String, limit: Int?, cursor: String?): ProjectReadyWorkResponse
    suspend fun listIssues(projectId: String, params: ProjectIssueListParams): ProjectIssueListResponse
    suspend fun getIssueAnalytics(projectId: String, params: ProjectIssueAnalyticsParams): IssueAnalyticsResponse
    suspend fun getIssue(issueId: String): ProjectIssueDetailResponse
    suspend fun claimIssue(
        issueId: String,
        assignee: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueMutationResponse

    suspend fun unclaimIssue(
        issueId: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueMutationResponse

    suspend fun updateIssueStatus(
        issueId: String,
        status: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueMutationResponse

    suspend fun addIssueNote(
        issueId: String,
        note: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueMutationResponse

    suspend fun closeIssue(
        issueId: String,
        reason: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueMutationResponse

    suspend fun reopenIssue(
        issueId: String,
        reason: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueMutationResponse
}
