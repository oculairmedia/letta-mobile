package com.letta.mobile.data.repository

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
import com.letta.mobile.data.repository.api.IdentityIrohSource
import com.letta.mobile.data.repository.api.IdentityRemoteSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Phase 5j: platform-neutral cached identity repository.
 */
open class CachedIdentityRepository(
    private val remote: IdentityRemoteSource,
    private val irohIdentitySource: IdentityIrohSource? = null,
) : IIdentityRepository {
    private val _identities = MutableStateFlow<List<Identity>>(emptyList())
    override val identities: StateFlow<List<Identity>> = _identities.asStateFlow()

    override suspend fun refreshIdentities() {
        val irohSource = irohIdentitySource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _identities.value = irohSource.listIdentities()
            return
        }
        _identities.value = remote.listIdentities()
    }

    override suspend fun countIdentities(): Int = remote.countIdentities()

    override suspend fun getIdentity(identityId: IdentityId): Identity {
        val irohSource = irohIdentitySource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.getIdentity(identityId.value)
        }
        return remote.retrieveIdentity(identityId.value)
    }

    override suspend fun createIdentity(params: IdentityCreateParams): Identity {
        val identity = remote.createIdentity(params)
        upsertIdentityInCache(identity)
        return identity
    }

    override suspend fun upsertIdentity(params: IdentityUpsertParams): Identity {
        val identity = remote.upsertIdentity(params)
        upsertIdentityInCache(identity)
        return identity
    }

    override suspend fun updateIdentity(identityId: IdentityId, params: IdentityUpdateParams): Identity {
        val identity = remote.updateIdentity(identityId.value, params)
        upsertIdentityInCache(identity)
        return identity
    }

    override suspend fun upsertIdentityProperties(identityId: IdentityId, properties: List<IdentityProperty>): Identity {
        val identity = remote.upsertIdentityProperties(identityId.value, properties)
        upsertIdentityInCache(identity)
        return identity
    }

    override suspend fun deleteIdentity(identityId: IdentityId) {
        remote.deleteIdentity(identityId.value)
        _identities.update { current -> current.filterNot { it.id == identityId } }
    }

    override suspend fun attachIdentity(agentId: AgentId, identityId: IdentityId) {
        remote.attachIdentity(agentId.value, identityId.value)
    }

    override suspend fun detachIdentity(agentId: AgentId, identityId: IdentityId) {
        remote.detachIdentity(agentId.value, identityId.value)
    }

    override suspend fun listAgentsForIdentity(identityId: IdentityId): List<Agent> {
        return exhaustCursorPages(
            pageSize = PaginationConstants.DEFAULT_PAGE_SIZE,
            maxPages = PaginationConstants.DEFAULT_MAX_PAGES,
            fetch = { limit, after ->
                remote.listAgentsForIdentity(
                    identityId = identityId.value,
                    limit = limit,
                    before = null,
                    after = after,
                    order = null,
                )
            },
            extractCursor = { agent -> agent.id.value },
            dedupKey = { agent -> agent.id.value },
        )
    }

    override suspend fun listBlocksForIdentity(identityId: IdentityId): List<Block> {
        return exhaustCursorPages(
            pageSize = PaginationConstants.DEFAULT_PAGE_SIZE,
            maxPages = PaginationConstants.DEFAULT_MAX_PAGES,
            fetch = { limit, after ->
                remote.listBlocksForIdentity(
                    identityId = identityId.value,
                    limit = limit,
                    before = null,
                    after = after,
                    order = null,
                )
            },
            extractCursor = { block -> block.id.value },
            dedupKey = { block -> block.id.value },
        )
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
