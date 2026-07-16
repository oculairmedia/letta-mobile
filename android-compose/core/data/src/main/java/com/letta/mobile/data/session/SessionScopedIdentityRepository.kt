package com.letta.mobile.data.session

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityId
import com.letta.mobile.data.model.IdentityProperty
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.data.model.IdentityUpsertParams
import com.letta.mobile.data.repository.api.IIdentityRepository
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

internal fun defaultSessionScopedIdentityRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedIdentityRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IIdentityRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedIdentityRepositoryScope(),
    )

    private val _identities = MutableStateFlow(sessionManager.current.identityRepository.identities.value)
    override val identities: StateFlow<List<Identity>> = _identities

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.identityRepository.identities }
            .onEach { _identities.value = it }
            .launchIn(proxyScope)
    }

    private val current: IIdentityRepository
        get() = sessionManager.current.identityRepository

    override suspend fun refreshIdentities() = sessionManager.withCurrentSession { it.identityRepository.refreshIdentities() }

    override suspend fun countIdentities(): Int = sessionManager.withCurrentSession { it.identityRepository.countIdentities() }

    override suspend fun getIdentity(identityId: IdentityId): Identity = sessionManager.withCurrentSession { it.identityRepository.getIdentity(identityId) }

    override suspend fun createIdentity(params: IdentityCreateParams): Identity = sessionManager.withCurrentSession { it.identityRepository.createIdentity(params) }

    override suspend fun upsertIdentity(params: IdentityUpsertParams): Identity = sessionManager.withCurrentSession { it.identityRepository.upsertIdentity(params) }

    override suspend fun updateIdentity(identityId: IdentityId, params: IdentityUpdateParams): Identity =
        sessionManager.withCurrentSession { it.identityRepository.updateIdentity(identityId, params) }

    override suspend fun upsertIdentityProperties(
        identityId: IdentityId,
        properties: List<IdentityProperty>,
    ): Identity = sessionManager.withCurrentSession { it.identityRepository.upsertIdentityProperties(identityId, properties) }

    override suspend fun deleteIdentity(identityId: IdentityId) = sessionManager.withCurrentSession { it.identityRepository.deleteIdentity(identityId) }

    override suspend fun attachIdentity(agentId: AgentId, identityId: IdentityId) =
        sessionManager.withCurrentSession { it.identityRepository.attachIdentity(agentId, identityId) }

    override suspend fun detachIdentity(agentId: AgentId, identityId: IdentityId) =
        sessionManager.withCurrentSession { it.identityRepository.detachIdentity(agentId, identityId) }

    override suspend fun listAgentsForIdentity(identityId: IdentityId): List<Agent> =
        sessionManager.withCurrentSession { it.identityRepository.listAgentsForIdentity(identityId) }

    override suspend fun listBlocksForIdentity(identityId: IdentityId): List<Block> =
        sessionManager.withCurrentSession { it.identityRepository.listBlocksForIdentity(identityId) }

    fun close() { proxyScope.cancel() }
}
