package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityId
import com.letta.mobile.data.model.IdentityProperty
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.data.model.IdentityUpsertParams
import kotlinx.coroutines.flow.StateFlow

interface IIdentityRepository {
    val identities: StateFlow<List<Identity>>
    suspend fun refreshIdentities()
    suspend fun countIdentities(): Int
    suspend fun getIdentity(identityId: IdentityId): Identity
    suspend fun createIdentity(params: IdentityCreateParams): Identity
    suspend fun upsertIdentity(params: IdentityUpsertParams): Identity
    suspend fun updateIdentity(identityId: IdentityId, params: IdentityUpdateParams): Identity
    suspend fun upsertIdentityProperties(identityId: IdentityId, properties: List<IdentityProperty>): Identity
    suspend fun deleteIdentity(identityId: IdentityId)
    suspend fun attachIdentity(agentId: AgentId, identityId: IdentityId)
    suspend fun detachIdentity(agentId: AgentId, identityId: IdentityId)
    suspend fun listAgentsForIdentity(identityId: IdentityId): List<Agent>
    suspend fun listBlocksForIdentity(identityId: IdentityId): List<Block>
}
