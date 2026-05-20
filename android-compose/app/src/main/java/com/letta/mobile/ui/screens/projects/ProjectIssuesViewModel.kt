package com.letta.mobile.ui.screens.projects

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.letta.mobile.data.model.IssueAnalyticsResponse
import com.letta.mobile.data.model.ProjectIssueAnalyticsParams
import com.letta.mobile.data.model.ProjectIssueDetail
import com.letta.mobile.data.model.ProjectIssueListParams
import com.letta.mobile.data.model.ProjectIssueSummary
import com.letta.mobile.data.repository.ProjectWorkRepository
import com.letta.mobile.data.repository.VibesyncEventStreamRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.navigation.ProjectIssueDetailRoute
import com.letta.mobile.ui.navigation.ProjectIssuesRoute
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class ProjectIssuesUiState(
    val projectId: String,
    val projectName: String?,
    val readyWork: ImmutableList<ProjectIssueSummary> = persistentListOf(),
    val issues: ImmutableList<ProjectIssueSummary> = persistentListOf(),
    val completedTimeline: ImmutableList<ProjectIssueTimelineItem> = persistentListOf(),
    val creationBuckets: ImmutableList<ProjectIssueCreationBucket> = persistentListOf(),
    val analyticsSummary: IssueAnalyticsSummaryUi? = null,
    val analyticsIsPartial: Boolean = false,
    val analyticsCompletionSource: String? = null,
    val analyticsNotice: String? = null,
    val timelineHasMore: Boolean = false,
    val searchQuery: String = "",
    val selectedStatus: String? = null,
    val isRefreshing: Boolean = false,
    val isLoadingMoreIssues: Boolean = false,
    val hasMoreIssues: Boolean = false,
    val nextIssueCursor: String? = null,
)

@androidx.compose.runtime.Immutable
data class ProjectIssueTimelineItem(
    val id: String,
    val title: String,
    val completedAt: String,
    val statusLabel: String,
    val priority: String?,
    val type: String?,
    val completedBy: String? = null,
    val completionReason: String? = null,
)

@androidx.compose.runtime.Immutable
data class ProjectIssueCreationBucket(
    val date: String,
    val label: String,
    val count: Int,
    val completedCount: Int = 0,
)

@androidx.compose.runtime.Immutable
data class IssueAnalyticsSummaryUi(
    val openCount: Int,
    val inProgressCount: Int,
    val completedCount: Int,
    val blockedCount: Int,
    val readyCount: Int,
    val totalCreatedInRange: Int,
    val totalCompletedInRange: Int,
)

private data class ProjectIssueAnalytics(
    val completedTimeline: ImmutableList<ProjectIssueTimelineItem>,
    val creationBuckets: ImmutableList<ProjectIssueCreationBucket>,
    val summary: IssueAnalyticsSummaryUi?,
    val isPartial: Boolean,
    val completionSource: String?,
    val timelineHasMore: Boolean,
)

@androidx.compose.runtime.Immutable
data class ProjectIssueDetailUiState(
    val projectId: String,
    val projectName: String?,
    val issueId: String,
    val issue: ProjectIssueDetail,
    val showActions: Boolean = false,
    val showNoteDialog: Boolean = false,
    val isMutating: Boolean = false,
)

sealed interface ProjectIssuesUiEvent {
    data class ShowMessage(val message: String) : ProjectIssuesUiEvent
}

@HiltViewModel
class ProjectIssuesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projectWorkRepository: ProjectWorkRepository,
    private val vibesyncEventStreamRepository: VibesyncEventStreamRepository? = null,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ProjectIssuesRoute>()

    private val _uiState = MutableStateFlow<UiState<ProjectIssuesUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ProjectIssuesUiState>> = _uiState.asStateFlow()

    private val _events = Channel<ProjectIssuesUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loadIssuesJob: Job? = null

    init {
        observeVibesyncEvents()
        loadIssues()
    }

    private fun observeVibesyncEvents() {
        val eventRepository = vibesyncEventStreamRepository ?: return
        eventRepository.start()
        viewModelScope.launch {
            eventRepository.events.collect { event ->
                if (event.projectId != route.projectId) return@collect
                if (event.type == "sync:completed" || event.type == "config:updated") {
                    projectWorkRepository.invalidateProjectCache(route.projectId)
                    loadIssues(forceRefresh = true)
                }
            }
        }
    }

    override fun onCleared() {
        vibesyncEventStreamRepository?.stop()
        super.onCleared()
    }

    fun refresh() = loadIssues(forceRefresh = true)

    fun loadMoreIssues() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        if (current.isLoadingMoreIssues || current.hasMoreIssues.not()) return
        val cursor = current.nextIssueCursor ?: return

        viewModelScope.launch {
            _uiState.value = UiState.Success(current.copy(isLoadingMoreIssues = true))
            runCatching {
                projectWorkRepository.refreshIssuePage(
                    projectId = route.projectId,
                    params = ProjectIssueListParams(
                        limit = ISSUE_PAGE_SIZE,
                        cursor = cursor,
                        sort = "updated_desc",
                    ),
                )
            }.onSuccess { page ->
                val latest = (_uiState.value as? UiState.Success)?.data ?: current
                val mergedIssues = (latest.issues + page.items).distinctBy(ProjectIssueSummary::id).toImmutableList()
                _uiState.value = UiState.Success(
                    latest.copy(
                        issues = mergedIssues,
                        hasMoreIssues = page.page.hasMore,
                        nextIssueCursor = page.page.nextCursor,
                        isLoadingMoreIssues = false,
                    )
                )
            }.onFailure { error ->
                val latest = (_uiState.value as? UiState.Success)?.data ?: current
                _uiState.value = UiState.Success(latest.copy(isLoadingMoreIssues = false))
                _events.trySend(ProjectIssuesUiEvent.ShowMessage(mapErrorToUserMessage(error.toException(), "Failed to load more project issues")))
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(searchQuery = query))
    }

    fun selectStatus(status: String?) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedStatus = status))
    }

    fun filteredIssues(): List<ProjectIssueSummary> {
        val current = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        return current.issues.applyIssueFilter(current.searchQuery, current.selectedStatus)
    }

    /**
     * Same search/status filter as [filteredIssues], applied to the
     * "Ready to work on" cards so they don't show stale results when the user
     * is searching for something specific.
     *
     * Ready work is by definition open / in-progress, so the `closed` status
     * filter collapses it to an empty list rather than leaving stale data.
     */
    fun filteredReadyWork(): List<ProjectIssueSummary> {
        val current = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        if (current.selectedStatus.equals("closed", ignoreCase = true)) return emptyList()
        return current.readyWork.applyIssueFilter(current.searchQuery, current.selectedStatus)
    }

    private fun List<ProjectIssueSummary>.applyIssueFilter(
        searchQuery: String,
        selectedStatus: String?,
    ): List<ProjectIssueSummary> {
        val query = searchQuery.trim().lowercase()
        return filter { issue ->
            val matchesStatus = selectedStatus == null || issue.status.equals(selectedStatus, ignoreCase = true)
            val matchesQuery = query.isBlank() ||
                issue.id.lowercase().contains(query) ||
                issue.title.lowercase().contains(query) ||
                issue.summary?.lowercase()?.contains(query) == true ||
                issue.labels.any { it.lowercase().contains(query) }
            matchesStatus && matchesQuery
        }
    }

    private fun loadIssues(forceRefresh: Boolean = false) {
        // Cancel any in-flight load so a stale response can't overwrite the fresh
        // post-sync data when events fire faster than the network round-trip.
        loadIssuesJob?.cancel()
        loadIssuesJob = viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data
            if (current == null) {
                _uiState.value = UiState.Loading
            } else {
                _uiState.value = UiState.Success(current.copy(isRefreshing = true))
            }

            val analyticsParams = defaultIssueAnalyticsParams()
            Log.i(
                TAG,
                "Load issues project=${route.projectId} forceRefresh=$forceRefresh " +
                    "rangeStart=${analyticsParams.rangeStart} rangeEnd=${analyticsParams.rangeEnd} " +
                    "timezone=${analyticsParams.timezone} timelineLimit=${analyticsParams.timelineLimit}",
            )

            val (readyResult, issuesResult, analyticsResult) = supervisorScope {
                val readyDeferred = async {
                    projectWorkRepository.refreshReadyWork(route.projectId, limit = ISSUE_PAGE_SIZE)
                }
                val issuesDeferred = async {
                    projectWorkRepository.refreshIssuePage(
                        projectId = route.projectId,
                        params = ProjectIssueListParams(limit = ISSUE_PAGE_SIZE, sort = "updated_desc"),
                    )
                }
                val analyticsDeferred = async {
                    projectWorkRepository.refreshIssueAnalytics(
                        projectId = route.projectId,
                        params = analyticsParams,
                    )
                }
                Triple(
                    runCatching { readyDeferred.await() },
                    runCatching { issuesDeferred.await() },
                    runCatching { analyticsDeferred.await() },
                )
            }

            issuesResult.onSuccess { issuePage ->
                val issues = issuePage.items
                val ready = readyResult.getOrElse { emptyList() }
                val analyticsError = analyticsResult.exceptionOrNull()
                val analyticsResponse = analyticsResult.getOrNull()
                if (analyticsResponse == null) {
                    Log.w(
                        TAG,
                        "Analytics unavailable; using client fallback project=${route.projectId} " +
                            "issues=${issues.size} error=${analyticsError?.message}",
                        analyticsError,
                    )
                } else {
                    Log.i(
                        TAG,
                        "Analytics raw project=${route.projectId} createdBuckets=${analyticsResponse.createdBuckets.size} " +
                            "createdTotal=${analyticsResponse.createdBuckets.sumOf { it.createdCount }} " +
                            "completedBuckets=${analyticsResponse.completedBuckets.size} " +
                            "completedTotal=${analyticsResponse.completedBuckets.sumOf { it.completedCount }} " +
                            "timeline=${analyticsResponse.completedTimeline.size} " +
                            "summaryCreated=${analyticsResponse.summary.totalCreatedInRange} " +
                            "summaryCompleted=${analyticsResponse.summary.totalCompletedInRange} " +
                            "source=${analyticsResponse.completionSource} partial=${analyticsResponse.isPartial} " +
                            "hasMore=${analyticsResponse.timelinePage.hasMore}",
                    )
                }
                val analytics = withContext(Dispatchers.Default) {
                    analyticsResponse?.toProjectIssueAnalytics()
                        ?: buildFallbackProjectIssueAnalytics(issues)
                }
                Log.i(
                    TAG,
                    "Analytics UI project=${route.projectId} issues=${issues.size} ready=${ready.size} " +
                        "buckets=${analytics.creationBuckets.size} createdChartTotal=${analytics.creationBuckets.sumOf { it.count }} " +
                        "completedBucketTotal=${analytics.creationBuckets.sumOf { it.completedCount }} " +
                        "timeline=${analytics.completedTimeline.size} summary=${analytics.summary.analyticsSummaryLog()} " +
                        "source=${analytics.completionSource} partial=${analytics.isPartial} hasMore=${analytics.timelineHasMore}",
                )
                _uiState.value = UiState.Success(
                    ProjectIssuesUiState(
                        projectId = route.projectId,
                        projectName = route.projectName,
                        readyWork = ready.toImmutableList(),
                        issues = issues.toImmutableList(),
                        completedTimeline = analytics.completedTimeline,
                        creationBuckets = analytics.creationBuckets,
                        analyticsSummary = analytics.summary,
                        analyticsIsPartial = analytics.isPartial,
                        analyticsCompletionSource = analytics.completionSource,
                        analyticsNotice = analyticsError?.let { error ->
                            mapErrorToUserMessage(
                                error.toException(),
                                "Issue analytics is unavailable. Check that Server URL points to Vibesync.",
                            )
                        },
                        timelineHasMore = analytics.timelineHasMore,
                        searchQuery = current?.searchQuery.orEmpty(),
                        selectedStatus = current?.selectedStatus,
                        isRefreshing = false,
                        hasMoreIssues = issuePage.page.hasMore,
                        nextIssueCursor = issuePage.page.nextCursor,
                    )
                )
                readyResult.exceptionOrNull()?.let { error ->
                    _events.trySend(ProjectIssuesUiEvent.ShowMessage(mapErrorToUserMessage(error.toException(), "Ready work is unavailable")))
                }
                analyticsResult.exceptionOrNull()?.let { error ->
                    _events.trySend(ProjectIssuesUiEvent.ShowMessage(mapErrorToUserMessage(error.toException(), "Issue analytics is unavailable")))
                }
            }.onFailure { error ->
                val message = mapErrorToUserMessage(error.toException(), "Failed to load project issues")
                if (current == null || forceRefresh.not()) {
                    _uiState.value = UiState.Error(message)
                } else {
                    _uiState.value = UiState.Success(current.copy(isRefreshing = false))
                    _events.trySend(ProjectIssuesUiEvent.ShowMessage(message))
                }
            }
        }
    }

    companion object {
        private const val ISSUE_PAGE_SIZE = 50
    }
}

@HiltViewModel
class ProjectIssueDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projectWorkRepository: ProjectWorkRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ProjectIssueDetailRoute>()

    private val _uiState = MutableStateFlow<UiState<ProjectIssueDetailUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ProjectIssueDetailUiState>> = _uiState.asStateFlow()

    private val _events = Channel<ProjectIssuesUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        loadIssue()
    }

    fun refresh() = loadIssue(forceRefresh = true)

    fun showActions(show: Boolean) = updateSuccess { it.copy(showActions = show) }

    fun showNoteDialog(show: Boolean) = updateSuccess { it.copy(showNoteDialog = show, showActions = false) }

    fun claimIssue() = mutate("Claimed issue") { issue ->
        projectWorkRepository.claimIssue(issue.id, assignee = "Android", ifMatch = requireEtag(issue))
    }

    fun unclaimIssue() = mutate("Cleared assignee") { issue ->
        projectWorkRepository.unclaimIssue(issue.id, ifMatch = requireEtag(issue))
    }

    fun closeIssue() = mutate("Closed issue") { issue ->
        projectWorkRepository.closeIssue(issue.id, reason = "Closed from Letta Mobile", ifMatch = requireEtag(issue))
    }

    fun reopenIssue() = mutate("Reopened issue") { issue ->
        projectWorkRepository.reopenIssue(issue.id, reason = "Reopened from Letta Mobile", ifMatch = requireEtag(issue))
    }

    fun addNote(note: String) = mutate("Added note") { issue ->
        projectWorkRepository.addIssueNote(issue.id, note = note.trim(), ifMatch = requireEtag(issue))
    }

    private fun loadIssue(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if ((_uiState.value as? UiState.Success) == null) _uiState.value = UiState.Loading
            runCatching {
                projectWorkRepository.getIssue(route.issueId, forceRefresh = forceRefresh)
            }.onSuccess { issue ->
                _uiState.value = UiState.Success(
                    ProjectIssueDetailUiState(
                        projectId = route.projectId,
                        projectName = route.projectName,
                        issueId = route.issueId,
                        issue = issue,
                    )
                )
            }.onFailure { error ->
                _uiState.value = UiState.Error(mapErrorToUserMessage(error.toException(), "Failed to load issue"))
            }
        }
    }

    private fun mutate(
        successMessage: String,
        action: suspend (ProjectIssueDetail) -> ProjectIssueSummary,
    ) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            _uiState.value = UiState.Success(current.copy(isMutating = true, showActions = false, showNoteDialog = false))
            runCatching {
                action(current.issue)
                projectWorkRepository.getIssue(current.issue.id, forceRefresh = true)
            }.onSuccess { issue ->
                _uiState.value = UiState.Success(current.copy(issue = issue, isMutating = false))
                _events.trySend(ProjectIssuesUiEvent.ShowMessage(successMessage))
            }.onFailure { error ->
                _uiState.value = UiState.Success(current.copy(isMutating = false))
                _events.trySend(ProjectIssuesUiEvent.ShowMessage(mapErrorToUserMessage(error.toException(), "Issue action failed")))
            }
        }
    }

    private fun requireEtag(issue: ProjectIssueDetail): String =
        issue.etag ?: throw IllegalStateException("Refresh this issue before changing it; the server did not provide an ETag.")

    private fun updateSuccess(transform: (ProjectIssueDetailUiState) -> ProjectIssueDetailUiState) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(transform(current))
    }

}

private fun Throwable.toException(): Exception = this as? Exception ?: Exception(this)

private fun defaultIssueAnalyticsParams(
    zoneId: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
): ProjectIssueAnalyticsParams {
    val today = now.atZone(zoneId).toLocalDate()
    return ProjectIssueAnalyticsParams(
        rangeStart = today.minusDays(29).atStartOfDay(zoneId).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        rangeEnd = today.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        granularity = "day",
        timezone = zoneId.id,
        timelineLimit = 30,
    )
}

private fun IssueAnalyticsResponse.toProjectIssueAnalytics(): ProjectIssueAnalytics {
    val completedByBucketStart = this.completedBuckets.associateBy { it.bucketStart }
    return ProjectIssueAnalytics(
        completedTimeline = this.completedTimeline.map { issue ->
            ProjectIssueTimelineItem(
                id = issue.issueId,
                title = issue.title,
                completedAt = issue.completedAt,
                statusLabel = issue.statusLabel.ifBlank { issue.status },
                priority = issue.priority,
                type = issue.type.ifBlank { null },
                completedBy = issue.completedBy,
                completionReason = issue.completionReason,
            )
        }.toImmutableList(),
        creationBuckets = this.createdBuckets.map { bucket ->
            ProjectIssueCreationBucket(
                date = bucket.bucketStart,
                label = bucket.label,
                count = bucket.createdCount,
                completedCount = completedByBucketStart[bucket.bucketStart]?.completedCount ?: 0,
            )
        }.toImmutableList(),
        summary = IssueAnalyticsSummaryUi(
            openCount = this.summary.openCount,
            inProgressCount = this.summary.inProgressCount,
            completedCount = this.summary.completedCount,
            blockedCount = this.summary.blockedCount,
            readyCount = this.summary.readyCount,
            totalCreatedInRange = this.summary.totalCreatedInRange,
            totalCompletedInRange = this.summary.totalCompletedInRange,
        ),
        isPartial = this.isPartial,
        completionSource = this.completionSource,
        timelineHasMore = this.timelinePage.hasMore,
    )
}

private fun buildFallbackProjectIssueAnalytics(
    issues: List<ProjectIssueSummary>,
): ProjectIssueAnalytics = ProjectIssueAnalytics(
    completedTimeline = buildCompletedIssueTimeline(issues).toImmutableList(),
    creationBuckets = buildIssueCreationBuckets(issues).toImmutableList(),
    summary = null,
    isPartial = true,
    completionSource = "client_issue_list_fallback",
    timelineHasMore = false,
)

internal fun buildCompletedIssueTimeline(
    issues: List<ProjectIssueSummary>,
    limit: Int = 30,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<ProjectIssueTimelineItem> = issues
    .asSequence()
    .filter(ProjectIssueSummary::isCompletedIssue)
    .mapNotNull { issue ->
        val completedAt = listOfNotNull(issue.updatedAt, issue.createdAt)
            .firstNotNullOfOrNull { timestamp ->
                timestamp.toIssueInstantOrNull(zoneId)?.let { instant -> timestamp to instant }
            }
            ?: return@mapNotNull null
        completedAt.second to ProjectIssueTimelineItem(
            id = issue.id,
            title = issue.title,
            completedAt = completedAt.first,
            statusLabel = issue.statusLabel ?: issue.status,
            priority = issue.priority,
            type = issue.type,
        )
    }
    .sortedByDescending { it.first }
    .take(limit)
    .map { it.second }
    .toList()

internal fun buildIssueCreationBuckets(
    issues: List<ProjectIssueSummary>,
    maxBuckets: Int = 12,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<ProjectIssueCreationBucket> {
    val groupedByDate = issues
        .mapNotNull { issue -> issue.createdAt?.toIssueLocalDateOrNull(zoneId) }
        .groupingBy { it }
        .eachCount()

    val sameYearFormatter = DateTimeFormatter.ofPattern("MMM d", locale)
    val withYearFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
    val currentYear = LocalDate.now(zoneId).year

    return groupedByDate
        .toSortedMap()
        .entries
        .toList()
        .takeLast(maxBuckets)
        .map { (date, count) ->
            ProjectIssueCreationBucket(
                date = date.toString(),
                label = date.format(if (date.year == currentYear) sameYearFormatter else withYearFormatter),
                count = count,
            )
        }
}

private fun ProjectIssueSummary.isCompletedIssue(): Boolean {
    val normalizedStatus = status.lowercase(Locale.ROOT)
    val normalizedLabel = statusLabel?.lowercase(Locale.ROOT).orEmpty()
    return normalizedStatus in completedStatusValues ||
        completedStatusValues.any { completedStatus -> normalizedLabel.contains(completedStatus) }
}

private val completedStatusValues = setOf("closed", "done", "completed", "complete", "resolved")

private fun String.toIssueLocalDateOrNull(zoneId: ZoneId): LocalDate? =
    toIssueInstantOrNull(zoneId)?.atZone(zoneId)?.toLocalDate()
        ?: runCatching { LocalDate.parse(this) }.getOrNull()

private fun String.toIssueInstantOrNull(zoneId: ZoneId): Instant? =
    runCatching { Instant.parse(this) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(this).atZone(zoneId).toInstant() }.getOrNull()
        ?: runCatching { LocalDate.parse(this).atStartOfDay(zoneId).toInstant() }.getOrNull()

private fun IssueAnalyticsSummaryUi?.analyticsSummaryLog(): String =
    this?.let { "created=${it.totalCreatedInRange},completed=${it.totalCompletedInRange},open=${it.openCount},ready=${it.readyCount}" }
        ?: "none"

private const val TAG = "ProjectIssuesVM"
