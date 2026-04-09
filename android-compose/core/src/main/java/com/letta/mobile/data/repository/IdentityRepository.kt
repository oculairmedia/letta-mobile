package com.letta.mobile.data.repository

import com.letta.mobile.data.api.IdentityApi
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.data.model.IdentityUpsertParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityRepository @Inject constructor(
    private val identityApi: IdentityApi,
) {
    private val _identities = MutableStateFlow<List<Identity>>(emptyList())
    val identities: StateFlow<List<Identity>> = _identities.asStateFlow()

    suspend fun refreshIdentities() {
        _identities.value = identityApi.listIdentities()
    }

    suspend fun getIdentity(identityId: String): Identity {
        return identityApi.retrieveIdentity(identityId)
    }

    suspend fun createIdentity(params: IdentityCreateParams): Identity {
        val identity = identityApi.createIdentity(params)
        upsertIdentityInCache(identity)
        return identity
    }

    suspend fun upsertIdentity(params: IdentityUpsertParams): Identity {
        val identity = identityApi.upsertIdentity(params)
        upsertIdentityInCache(identity)
        return identity
    }

    suspend fun updateIdentity(identityId: String, params: IdentityUpdateParams): Identity {
        val identity = identityApi.updateIdentity(identityId, params)
        upsertIdentityInCache(identity)
        return identity
    }

    suspend fun deleteIdentity(identityId: String) {
        identityApi.deleteIdentity(identityId)
        _identities.update { current -> current.filterNot { it.id == identityId } }
    }

    suspend fun attachIdentity(agentId: String, identityId: String) {
        identityApi.attachIdentity(agentId, identityId)
    }

    suspend fun detachIdentity(agentId: String, identityId: String) {
        identityApi.detachIdentity(agentId, identityId)
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
