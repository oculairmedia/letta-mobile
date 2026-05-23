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

    override suspend fun refreshIdentities() = current.refreshIdentities()

    override suspend fun countIdentities(): Int = current.countIdentities()

    override suspend fun getIdentity(identityId: String): Identity = current.getIdentity(identityId)

    override suspend fun createIdentity(params: IdentityCreateParams): Identity = current.createIdentity(params)

    override suspend fun upsertIdentity(params: IdentityUpsertParams): Identity = current.upsertIdentity(params)

    override suspend fun updateIdentity(identityId: String, params: IdentityUpdateParams): Identity =
        current.updateIdentity(identityId, params)

    override suspend fun upsertIdentityProperties(
        identityId: String,
        properties: List<IdentityProperty>,
    ): Identity = current.upsertIdentityProperties(identityId, properties)

    override suspend fun deleteIdentity(identityId: String) = current.deleteIdentity(identityId)

    override suspend fun attachIdentity(agentId: String, identityId: String) =
        current.attachIdentity(agentId, identityId)

    override suspend fun detachIdentity(agentId: String, identityId: String) =
        current.detachIdentity(agentId, identityId)

    override suspend fun listAgentsForIdentity(identityId: String): List<Agent> =
        current.listAgentsForIdentity(identityId)

    override suspend fun listBlocksForIdentity(identityId: String): List<Block> =
        current.listBlocksForIdentity(identityId)
}
