package com.letta.mobile.data.repository

import com.letta.mobile.data.api.IdentityApi
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityProperty
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.data.model.IdentityUpsertParams
import com.letta.mobile.data.repository.api.IIdentityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class IdentityRepository(
    private val identityApi: IdentityApi,
) : IIdentityRepository {
    private val _identities = MutableStateFlow<List<Identity>>(emptyList())
    override val identities: StateFlow<List<Identity>> = _identities.asStateFlow()

    override suspend fun refreshIdentities() {
        _identities.value = identityApi.listIdentities()
    }

    override suspend fun countIdentities(): Int {
        return identityApi.countIdentities()
    }

    override suspend fun getIdentity(identityId: String): Identity {
        return identityApi.retrieveIdentity(identityId)
    }

    override suspend fun createIdentity(params: IdentityCreateParams): Identity {
        val identity = identityApi.createIdentity(params)
        upsertIdentityInCache(identity)
        return identity
    }

    override suspend fun upsertIdentity(params: IdentityUpsertParams): Identity {
        val identity = identityApi.upsertIdentity(params)
        upsertIdentityInCache(identity)
        return identity
    }

    override suspend fun updateIdentity(identityId: String, params: IdentityUpdateParams): Identity {
        val identity = identityApi.updateIdentity(identityId, params)
        upsertIdentityInCache(identity)
        return identity
    }

    override suspend fun upsertIdentityProperties(identityId: String, properties: List<IdentityProperty>): Identity {
        val identity = identityApi.upsertIdentityProperties(identityId, properties)
        upsertIdentityInCache(identity)
        return identity
    }

    override suspend fun deleteIdentity(identityId: String) {
        identityApi.deleteIdentity(identityId)
        _identities.update { current -> current.filterNot { it.id == identityId } }
    }

    override suspend fun attachIdentity(agentId: String, identityId: String) {
        identityApi.attachIdentity(agentId, identityId)
    }

    override suspend fun detachIdentity(agentId: String, identityId: String) {
        identityApi.detachIdentity(agentId, identityId)
    }

    override suspend fun listAgentsForIdentity(identityId: String): List<Agent> {
        return identityApi.listAgentsForIdentity(identityId = identityId, limit = 1000)
    }

    override suspend fun listBlocksForIdentity(identityId: String): List<Block> {
        return identityApi.listBlocksForIdentity(identityId = identityId, limit = 1000)
    }

    private fun upsertIdentityInCache(identity: Identity) {
        _identities.update { current ->
            val index = current.indexOfFirst { it.id == identity.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = identity }
            } else {
                current + identity
            }
        }
    }
}
