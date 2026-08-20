package com.letta.mobile.data.session

import com.letta.mobile.data.model.IssueAnalyticsResponse
import com.letta.mobile.data.model.ProjectIssueAnalyticsParams
import com.letta.mobile.data.model.ProjectIssueDetail
import com.letta.mobile.data.model.ProjectIssueListParams
import com.letta.mobile.data.model.ProjectIssueListResponse
import com.letta.mobile.data.model.ProjectIssueSummary
import com.letta.mobile.data.repository.api.IProjectWorkRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun defaultSessionScopedProjectWorkRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedProjectWorkRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IProjectWorkRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedProjectWorkRepositoryScope(),
    )

    private val _readyWorkByProject = MutableStateFlow(sessionManager.current.projectWorkRepository.readyWorkByProject.value)
    override val readyWorkByProject: StateFlow<Map<String, List<ProjectIssueSummary>>> = _readyWorkByProject.asStateFlow()

    private val _issuesByProject = MutableStateFlow(sessionManager.current.projectWorkRepository.issuesByProject.value)
    override val issuesByProject: StateFlow<Map<String, List<ProjectIssueSummary>>> = _issuesByProject.asStateFlow()

    private val _issueDetails = MutableStateFlow(sessionManager.current.projectWorkRepository.issueDetails.value)
    override val issueDetails: StateFlow<Map<String, ProjectIssueDetail>> = _issueDetails.asStateFlow()

    private val _issueAnalyticsByProject =
        MutableStateFlow(sessionManager.current.projectWorkRepository.issueAnalyticsByProject.value)
    override val issueAnalyticsByProject: StateFlow<Map<String, IssueAnalyticsResponse>> =
        _issueAnalyticsByProject.asStateFlow()

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.projectWorkRepository.readyWorkByProject }
            .onEach { _readyWorkByProject.value = it }
            .launchIn(proxyScope)
        sessionManager.currentGraph
            .flatMapLatest { it.projectWorkRepository.issuesByProject }
            .onEach { _issuesByProject.value = it }
            .launchIn(proxyScope)
        sessionManager.currentGraph
            .flatMapLatest { it.projectWorkRepository.issueDetails }
            .onEach { _issueDetails.value = it }
            .launchIn(proxyScope)
        sessionManager.currentGraph
            .flatMapLatest { it.projectWorkRepository.issueAnalyticsByProject }
            .onEach { _issueAnalyticsByProject.value = it }
            .launchIn(proxyScope)
    }

    private val current: IProjectWorkRepository
        get() = sessionManager.current.projectWorkRepository

    override suspend fun refreshReadyWork(projectId: String, limit: Int?, cursor: String?): List<ProjectIssueSummary> =
        sessionManager.withCurrentSession { it.projectWorkRepository.refreshReadyWork(projectId, limit, cursor) }

    override suspend fun refreshIssues(projectId: String, params: ProjectIssueListParams): List<ProjectIssueSummary> =
        sessionManager.withCurrentSession { it.projectWorkRepository.refreshIssues(projectId, params) }

    override suspend fun refreshIssuePage(projectId: String, params: ProjectIssueListParams): ProjectIssueListResponse =
        sessionManager.withCurrentSession { it.projectWorkRepository.refreshIssuePage(projectId, params) }

    override suspend fun refreshIssueAnalytics(
        projectId: String,
        params: ProjectIssueAnalyticsParams,
    ): IssueAnalyticsResponse = sessionManager.withCurrentSession { it.projectWorkRepository.refreshIssueAnalytics(projectId, params) }

    override suspend fun getIssue(issueId: String, forceRefresh: Boolean): ProjectIssueDetail =
        sessionManager.withCurrentSession { it.projectWorkRepository.getIssue(issueId, forceRefresh) }

    override suspend fun invalidateProjectCache(projectId: String) = sessionManager.withCurrentSession { it.projectWorkRepository.invalidateProjectCache(projectId) }
    override suspend fun claimIssue(issueId: String, assignee: String, ifMatch: String, idempotencyKey: String): ProjectIssueSummary =
        sessionManager.withCurrentSession { it.projectWorkRepository.claimIssue(issueId, assignee, ifMatch, idempotencyKey) }

    override suspend fun unclaimIssue(issueId: String, ifMatch: String, idempotencyKey: String): ProjectIssueSummary =
        sessionManager.withCurrentSession { it.projectWorkRepository.unclaimIssue(issueId, ifMatch, idempotencyKey) }

    override suspend fun updateIssueStatus(
        issueId: String,
        status: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = sessionManager.withCurrentSession { it.projectWorkRepository.updateIssueStatus(issueId, status, ifMatch, idempotencyKey) }

    override suspend fun addIssueNote(
        issueId: String,
        note: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = sessionManager.withCurrentSession { it.projectWorkRepository.addIssueNote(issueId, note, ifMatch, idempotencyKey) }

    override suspend fun closeIssue(
        issueId: String,
        reason: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = sessionManager.withCurrentSession { it.projectWorkRepository.closeIssue(issueId, reason, ifMatch, idempotencyKey) }

    override suspend fun reopenIssue(
        issueId: String,
        reason: String,
        ifMatch: String,
        idempotencyKey: String,
    ): ProjectIssueSummary = sessionManager.withCurrentSession { it.projectWorkRepository.reopenIssue(issueId, reason, ifMatch, idempotencyKey) }

    override fun newIdempotencyKey(): String = current.newIdempotencyKey()

    fun close() { proxyScope.cancel() }
}
