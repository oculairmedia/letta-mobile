package com.letta.mobile.data.session

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.model.ArchiveCreateParams
import com.letta.mobile.data.model.ArchiveUpdateParams
import com.letta.mobile.data.repository.api.IArchiveRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun defaultSessionScopedArchiveRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedArchiveRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IArchiveRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedArchiveRepositoryScope(),
    )

    private val _archives = MutableStateFlow(sessionManager.current.archiveRepository.archives.value)
    override val archives: StateFlow<List<Archive>> = _archives

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.archiveRepository.archives }
            .onEach { _archives.value = it }
            .launchIn(proxyScope)
    }

    private val current: IArchiveRepository
        get() = sessionManager.current.archiveRepository

    override suspend fun refreshArchives(name: String?, agentId: String?) =
        sessionManager.withCurrentSession { it.archiveRepository.refreshArchives(name, agentId) }

    override suspend fun getArchive(archiveId: String): Archive =
        sessionManager.withCurrentSession { it.archiveRepository.getArchive(archiveId) }

    override suspend fun createArchive(params: ArchiveCreateParams): Archive =
        sessionManager.withCurrentSession { it.archiveRepository.createArchive(params) }

    override suspend fun updateArchive(archiveId: String, params: ArchiveUpdateParams): Archive =
        sessionManager.withCurrentSession { it.archiveRepository.updateArchive(archiveId, params) }

    override suspend fun deleteArchive(archiveId: String): Archive =
        sessionManager.withCurrentSession { it.archiveRepository.deleteArchive(archiveId) }

    override suspend fun listAgentsForArchive(archiveId: String): List<Agent> =
        sessionManager.withCurrentSession { it.archiveRepository.listAgentsForArchive(archiveId) }

    override suspend fun deletePassageFromArchive(archiveId: String, passageId: String) =
        sessionManager.withCurrentSession { it.archiveRepository.deletePassageFromArchive(archiveId, passageId) }

    fun close() { proxyScope.cancel() }
}
