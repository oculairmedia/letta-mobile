package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityProperty
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.data.model.IdentityUpsertParams
import kotlinx.coroutines.flow.StateFlow

interface IIdentityRepository {
    val identities: StateFlow<List<Identity>>
    suspend fun refreshIdentities()
    suspend fun countIdentities(): Int
    suspend fun getIdentity(identityId: String): Identity
    suspend fun createIdentity(params: IdentityCreateParams): Identity
    suspend fun upsertIdentity(params: IdentityUpsertParams): Identity
    suspend fun updateIdentity(identityId: String, params: IdentityUpdateParams): Identity
    suspend fun upsertIdentityProperties(identityId: String, properties: List<IdentityProperty>): Identity
    suspend fun deleteIdentity(identityId: String)
    suspend fun attachIdentity(agentId: String, identityId: String)
    suspend fun detachIdentity(agentId: String, identityId: String)
    suspend fun listAgentsForIdentity(identityId: String): List<Agent>
    suspend fun listBlocksForIdentity(identityId: String): List<Block>
}
