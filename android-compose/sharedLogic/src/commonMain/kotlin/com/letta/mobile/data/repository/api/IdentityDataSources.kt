package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityProperty
import com.letta.mobile.data.model.IdentityRelatedListParams
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.data.model.IdentityUpsertParams

/**
 * Remote HTTP (or equivalent) identity admin surface used by
 * [com.letta.mobile.data.repository.CachedIdentityRepository].
 */
interface IdentityRemoteSource {
    suspend fun listIdentities(): List<Identity>
    suspend fun countIdentities(): Int
    suspend fun retrieveIdentity(identityId: String): Identity
    suspend fun createIdentity(params: IdentityCreateParams): Identity
    suspend fun upsertIdentity(params: IdentityUpsertParams): Identity
    suspend fun updateIdentity(identityId: String, params: IdentityUpdateParams): Identity
    suspend fun deleteIdentity(identityId: String)
    suspend fun upsertIdentityProperties(identityId: String, properties: List<IdentityProperty>): Identity
    suspend fun listAgentsForIdentity(params: IdentityRelatedListParams): List<Agent>
    suspend fun listBlocksForIdentity(params: IdentityRelatedListParams): List<Block>
    suspend fun attachIdentity(agentId: String, identityId: String)
    suspend fun detachIdentity(agentId: String, identityId: String)
}

/**
 * Iroh admin_rpc identity list/get surface.
 */
interface IdentityIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listIdentities(): List<Identity>
    suspend fun getIdentity(identityId: String): Identity
}
