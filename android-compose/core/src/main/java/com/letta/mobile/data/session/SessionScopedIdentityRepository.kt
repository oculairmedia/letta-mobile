package com.letta.mobile.data.session

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityProperty
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.data.model.IdentityUpsertParams
import com.letta.mobile.data.repository.api.IIdentityRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
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
    proxyScope: CoroutineScope,
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

    override suspend fun getIdentity(identityId: String): Identity = sessionManager.withCurrentSession { it.identityRepository.getIdentity(identityId) }

    override suspend fun createIdentity(params: IdentityCreateParams): Identity = sessionManager.withCurrentSession { it.identityRepository.createIdentity(params) }

    override suspend fun upsertIdentity(params: IdentityUpsertParams): Identity = sessionManager.withCurrentSession { it.identityRepository.upsertIdentity(params) }

    override suspend fun updateIdentity(identityId: String, params: IdentityUpdateParams): Identity =
        sessionManager.withCurrentSession { it.identityRepository.updateIdentity(identityId, params) }

    override suspend fun upsertIdentityProperties(
        identityId: String,
        properties: List<IdentityProperty>,
    ): Identity = sessionManager.withCurrentSession { it.identityRepository.upsertIdentityProperties(identityId, properties) }

    override suspend fun deleteIdentity(identityId: String) = sessionManager.withCurrentSession { it.identityRepository.deleteIdentity(identityId) }

    override suspend fun attachIdentity(agentId: String, identityId: String) =
        sessionManager.withCurrentSession { it.identityRepository.attachIdentity(agentId, identityId) }

    override suspend fun detachIdentity(agentId: String, identityId: String) =
        sessionManager.withCurrentSession { it.identityRepository.detachIdentity(agentId, identityId) }

    override suspend fun listAgentsForIdentity(identityId: String): List<Agent> =
        sessionManager.withCurrentSession { it.identityRepository.listAgentsForIdentity(identityId) }

    override suspend fun listBlocksForIdentity(identityId: String): List<Block> =
        sessionManager.withCurrentSession { it.identityRepository.listBlocksForIdentity(identityId) }
}
