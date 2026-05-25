package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNames

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
data class IssueAnalyticsResponse(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("projectId")
    @SerialName("project_id") val projectId: String = "",
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("rangeStart")
    @SerialName("range_start") val rangeStart: String = "",
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("rangeEnd")
    @SerialName("range_end") val rangeEnd: String = "",
    val granularity: String = "",
    val timezone: String = "",
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("createdBuckets")
    @SerialName("created_buckets") val createdBuckets: List<CreatedBucket> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("completedBuckets")
    @SerialName("completed_buckets") val completedBuckets: List<CompletedBucket> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("completedTimeline")
    @SerialName("completed_timeline") val completedTimeline: List<CompletedTimelineIssue> = emptyList(),
    val summary: IssueAnalyticsSummary = IssueAnalyticsSummary(),
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("nextTimelineCursor")
    @SerialName("next_timeline_cursor") val nextTimelineCursor: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("timelinePage")
    @SerialName("timeline_page") val timelinePage: TimelinePage = TimelinePage(),
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("completionSource")
    @SerialName("completion_source") val completionSource: String = "",
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("isPartial")
    @SerialName("is_partial") val isPartial: Boolean = false,
    val etag: String = "",
    @SerialName("data_freshness") val dataFreshness: ProjectDataFreshness? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("generatedAt")
    @SerialName("generated_at") val generatedAt: String = "",
)

@Serializable
data class CreatedBucket(
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("bucketStart")
    @SerialName("bucket_start") val bucketStart: String,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("bucketEnd")
    @SerialName("bucket_end") val bucketEnd: String,
    val label: String,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("createdCount")
    @SerialName("created_count") val createdCount: Int,
)

@Serializable
data class CompletedBucket(
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("bucketStart")
    @SerialName("bucket_start") val bucketStart: String,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("bucketEnd")
    @SerialName("bucket_end") val bucketEnd: String,
    val label: String,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("completedCount")
    @SerialName("completed_count") val completedCount: Int,
)

@Serializable
data class CompletedTimelineIssue(
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("issueId")
    @SerialName("issue_id") val issueId: String,
    val title: String = "",
    val status: String = "",
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("statusLabel")
    @SerialName("status_label") val statusLabel: String = "",
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("completedAt")
    @SerialName("completed_at") val completedAt: String = "",
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("createdAt")
    @SerialName("created_at") val createdAt: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("updatedAt")
    @SerialName("updated_at") val updatedAt: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val priority: String? = null,
    val type: String = "",
    val assignee: String? = null,
    val labels: List<String> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("completedBy")
    @SerialName("completed_by") val completedBy: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("completionReason")
    @SerialName("completion_reason") val completionReason: String? = null,
)

@Serializable
data class IssueAnalyticsSummary(
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("openCount")
    @SerialName("open_count") val openCount: Int = 0,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("inProgressCount")
    @SerialName("in_progress_count") val inProgressCount: Int = 0,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("completedCount")
    @SerialName("completed_count") val completedCount: Int = 0,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("blockedCount")
    @SerialName("blocked_count") val blockedCount: Int = 0,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("readyCount")
    @SerialName("ready_count") val readyCount: Int = 0,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("totalCreatedInRange")
    @SerialName("total_created_in_range") val totalCreatedInRange: Int = 0,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("totalCompletedInRange")
    @SerialName("total_completed_in_range") val totalCompletedInRange: Int = 0,
)

@Serializable
data class TimelinePage(
    val limit: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("total_known") val totalKnown: Int = 0,
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

data class ProjectIssueAnalyticsParams(
    val rangeStart: String,
    val rangeEnd: String,
    val granularity: String,
    val timezone: String,
    val statusFilter: String? = null,
    val typeFilter: String? = null,
    val priorityFilter: String? = null,
    val assigneeFilter: String? = null,
    val labelFilter: String? = null,
    val cursor: String? = null,
    val timelineLimit: Int? = null,
)
