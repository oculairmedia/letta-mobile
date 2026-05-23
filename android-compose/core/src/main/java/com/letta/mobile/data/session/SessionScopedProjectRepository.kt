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
    proxyScope: CoroutineScope,
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

    override suspend fun refreshProjects(): ProjectCatalog = current.refreshProjects()
    override suspend fun getProject(identifier: String): ProjectSummary = current.getProject(identifier)
    override suspend fun getBeadsRemoteStatus(identifier: String): BeadsRemoteStatus =
        current.getBeadsRemoteStatus(identifier)

    override suspend fun provisionBeadsRemote(identifier: String, push: Boolean): BeadsRemoteProvisionResponse =
        current.provisionBeadsRemote(identifier, push)

    override suspend fun triggerSync(identifier: String): ProjectSyncTriggerResponse = current.triggerSync(identifier)
    override suspend fun createProject(name: String?, filesystemPath: String, gitUrl: String?): ProjectSummary =
        current.createProject(name, filesystemPath, gitUrl)

    override suspend fun updateProject(identifier: String, filesystemPath: String?, gitUrl: String?): ProjectSummary =
        current.updateProject(identifier, filesystemPath, gitUrl)

    override suspend fun archiveProject(identifier: String): ProjectSummary = current.archiveProject(identifier)
    override suspend fun deleteProject(identifier: String) = current.deleteProject(identifier)
    override fun hasFreshProjects(maxAgeMs: Long): Boolean = current.hasFreshProjects(maxAgeMs)
    override suspend fun refreshProjectsIfStale(maxAgeMs: Long): Boolean = current.refreshProjectsIfStale(maxAgeMs)
}
