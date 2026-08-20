package com.letta.mobile.data.session

import com.letta.mobile.data.model.BeadsRemoteProvisionResponse
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.model.ProjectSyncTriggerResponse
import com.letta.mobile.data.repository.api.IProjectRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun defaultSessionScopedProjectRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedProjectRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IProjectRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedProjectRepositoryScope(),
    )

    private val _projects = MutableStateFlow(sessionManager.current.projectRepository.projects.value)
    override val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.projectRepository.projects }
            .onEach { _projects.value = it }
            .launchIn(proxyScope)
    }

    private val current: IProjectRepository
        get() = sessionManager.current.projectRepository

    override suspend fun refreshProjects(): ProjectCatalog = sessionManager.withCurrentSession { it.projectRepository.refreshProjects() }
    override suspend fun getProject(identifier: String): ProjectSummary = sessionManager.withCurrentSession { it.projectRepository.getProject(identifier) }
    override suspend fun getBeadsRemoteStatus(identifier: String): BeadsRemoteStatus =
        sessionManager.withCurrentSession { it.projectRepository.getBeadsRemoteStatus(identifier) }

    override suspend fun provisionBeadsRemote(identifier: String, push: Boolean): BeadsRemoteProvisionResponse =
        sessionManager.withCurrentSession { it.projectRepository.provisionBeadsRemote(identifier, push) }

    override suspend fun triggerSync(identifier: String): ProjectSyncTriggerResponse = sessionManager.withCurrentSession { it.projectRepository.triggerSync(identifier) }
    override suspend fun createProject(name: String?, filesystemPath: String, gitUrl: String?): ProjectSummary =
        sessionManager.withCurrentSession { it.projectRepository.createProject(name, filesystemPath, gitUrl) }

    override suspend fun updateProject(identifier: String, filesystemPath: String?, gitUrl: String?): ProjectSummary =
        sessionManager.withCurrentSession { it.projectRepository.updateProject(identifier, filesystemPath, gitUrl) }

    override suspend fun archiveProject(identifier: String): ProjectSummary = sessionManager.withCurrentSession { it.projectRepository.archiveProject(identifier) }
    override suspend fun deleteProject(identifier: String) = sessionManager.withCurrentSession { it.projectRepository.deleteProject(identifier) }
    override fun hasFreshProjects(maxAgeMs: Long): Boolean = current.hasFreshProjects(maxAgeMs)
    override suspend fun refreshProjectsIfStale(maxAgeMs: Long): Boolean = sessionManager.withCurrentSession { it.projectRepository.refreshProjectsIfStale(maxAgeMs) }

    fun close() { proxyScope.cancel() }
}
