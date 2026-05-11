package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProjectReadyWorkResponse(
    @SerialName("project_id") val projectId: String = "",
    @SerialName("ready_work") val readyWork: List<ProjectIssueSummary> = emptyList(),
    val page: ProjectIssuePage,
    val etag: String? = null,
    @SerialName("data_freshness") val dataFreshness: ProjectDataFreshness? = null,
    val timestamp: String? = null,
) {
    val items: List<ProjectIssueSummary>
        get() = readyWork
}

@Serializable
data class ProjectIssueListResponse(
    @SerialName("project_id") val projectId: String = "",
    val issues: List<ProjectIssueSummary> = emptyList(),
    @SerialName("work_items") val workItems: List<ProjectIssueSummary> = emptyList(),
    val page: ProjectIssuePage,
    val etag: String? = null,
    @SerialName("data_freshness") val dataFreshness: ProjectDataFreshness? = null,
    val timestamp: String? = null,
) {
    val items: List<ProjectIssueSummary>
        get() = issues.ifEmpty { workItems }
}

@Serializable
data class ProjectIssueDetailResponse(
    val issue: ProjectIssueDetail,
    val timestamp: String? = null,
)

@Serializable
data class ProjectIssueMutationResponse(
    @SerialName("issue_id") val issueId: String? = null,
    val action: String,
    val applied: Boolean,
    @SerialName("idempotent_replay") val idempotentReplay: Boolean = false,
    val issue: ProjectIssueSummary,
    val timestamp: String? = null,
)

@Serializable
data class ProjectIssueSummary(
    val id: String,
    @SerialName("project_id") val projectId: String = "",
    val provider: String? = null,
    val title: String = "",
    val type: String? = null,
    val priority: String? = null,
    val status: String = "",
    @SerialName("status_label") val statusLabel: String? = null,
    val ready: Boolean = false,
    val assignee: String? = null,
    @SerialName("blocked_by") val blockedBy: List<ProjectIssueRef> = emptyList(),
    val blocks: List<ProjectIssueRef> = emptyList(),
    @SerialName("is_blocked") val isBlocked: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val summary: String? = null,
    @SerialName("acceptance_criteria") val acceptanceCriteria: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("child_count") val childCount: Int = 0,
    @SerialName("validation_warnings") val validationWarnings: List<String> = emptyList(),
    val etag: String? = null,
)

@Serializable
data class ProjectIssueDetail(
    val id: String,
    @SerialName("project_id") val projectId: String = "",
    val provider: String? = null,
    val title: String = "",
    val type: String? = null,
    val priority: String? = null,
    val status: String = "",
    @SerialName("status_label") val statusLabel: String? = null,
    val ready: Boolean = false,
    val assignee: String? = null,
    @SerialName("blocked_by") val blockedBy: List<ProjectIssueRef> = emptyList(),
    val blocks: List<ProjectIssueRef> = emptyList(),
    @SerialName("is_blocked") val isBlocked: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val summary: String? = null,
    @SerialName("acceptance_criteria") val acceptanceCriteria: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("child_count") val childCount: Int = 0,
    @SerialName("validation_warnings") val validationWarnings: List<String> = emptyList(),
    val etag: String? = null,
    val description: String? = null,
    @SerialName("design_notes") val designNotes: String? = null,
    val notes: List<ProjectIssueNote> = emptyList(),
    val comments: List<ProjectIssueNote> = emptyList(),
    val children: List<ProjectIssueRef> = emptyList(),
    val timestamps: ProjectIssueTimestamps? = null,
    val metadata: ProjectIssueMetadata? = null,
)

@Serializable
data class ProjectIssueRef(
    val id: String,
    val title: String? = null,
    val status: String? = null,
)

@Serializable
data class ProjectIssueNote(
    val id: String? = null,
    val text: String? = null,
    val author: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class ProjectIssueTimestamps(
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("last_sync_at") val lastSyncAt: String? = null,
)

@Serializable
data class ProjectIssueMetadata(
    @SerialName("huly_id") val hulyId: String? = null,
    @SerialName("vibe_task_id") val vibeTaskId: String? = null,
    @SerialName("deleted_from_huly") val deletedFromHuly: Boolean = false,
    @SerialName("deleted_from_vibe") val deletedFromVibe: Boolean = false,
)

@Serializable
data class ProjectIssuePage(
    val limit: Int? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("total_known") val totalKnown: Int? = null,
)

@Serializable
data class ProjectIssueConflictResponse(
    val error: String,
    val statusCode: Int,
    val conflict: ProjectIssueConflict,
    val timestamp: String? = null,
)

@Serializable
data class ProjectIssueConflict(
    val reason: String,
    val expected: String? = null,
    val current: String? = null,
    val issueId: String,
)

data class ProjectIssueListParams(
    val status: String? = null,
    val priority: String? = null,
    val assignee: String? = null,
    val type: String? = null,
    val ready: Boolean? = null,
    val query: String? = null,
    val updatedSince: String? = null,
    val sort: String? = null,
    val limit: Int? = null,
    val cursor: String? = null,
)
