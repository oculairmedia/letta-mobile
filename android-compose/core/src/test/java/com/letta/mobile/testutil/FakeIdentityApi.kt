package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.IdentityApi
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.data.model.IdentityUpsertParams
import io.mockk.mockk

class FakeIdentityApi : IdentityApi(mockk(relaxed = true)) {
    var identities = mutableListOf<Identity>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listIdentities(): List<Identity> {
        calls.add("listIdentities")
        if (shouldFail) throw ApiException(500, "Server error")
        return identities.toList()
    }

    override suspend fun retrieveIdentity(identityId: String): Identity {
        calls.add("retrieveIdentity:$identityId")
        if (shouldFail) throw ApiException(500, "Server error")
        return identities.firstOrNull { it.id == identityId } ?: throw ApiException(404, "Not found")
    }

    override suspend fun createIdentity(params: IdentityCreateParams): Identity {
        calls.add("createIdentity:${params.identifierKey}")
        if (shouldFail) throw ApiException(500, "Server error")
        val identity = Identity(
            id = "identity-${identities.size}",
            identifierKey = params.identifierKey,
            name = params.name,
            identityType = params.identityType,
            projectId = params.projectId,
            agentIds = params.agentIds ?: emptyList(),
            blockIds = params.blockIds ?: emptyList(),
            properties = params.properties ?: emptyList(),
        )
        identities.add(identity)
        return identity
    }

    override suspend fun upsertIdentity(params: IdentityUpsertParams): Identity {
        calls.add("upsertIdentity:${params.identifierKey}")
        if (shouldFail) throw ApiException(500, "Server error")
        val existingIndex = identities.indexOfFirst { it.identifierKey == params.identifierKey }
        val identity = Identity(
            id = if (existingIndex >= 0) identities[existingIndex].id else "identity-${identities.size}",
            identifierKey = params.identifierKey,
            name = params.name,
            identityType = params.identityType,
            projectId = params.projectId,
            agentIds = params.agentIds ?: emptyList(),
            blockIds = params.blockIds ?: emptyList(),
            properties = params.properties ?: emptyList(),
        )
        if (existingIndex >= 0) identities[existingIndex] = identity else identities.add(identity)
        return identity
    }

    override suspend fun updateIdentity(identityId: String, params: IdentityUpdateParams): Identity {
        calls.add("updateIdentity:$identityId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = identities.indexOfFirst { it.id == identityId }
        if (index < 0) throw ApiException(404, "Not found")
        val current = identities[index]
        val updated = current.copy(
            identifierKey = params.identifierKey ?: current.identifierKey,
            name = params.name ?: current.name,
            identityType = params.identityType ?: current.identityType,
            agentIds = params.agentIds ?: current.agentIds,
            blockIds = params.blockIds ?: current.blockIds,
            properties = params.properties ?: current.properties,
        )
        identities[index] = updated
        return updated
    }

    override suspend fun deleteIdentity(identityId: String) {
        calls.add("deleteIdentity:$identityId")
        if (shouldFail) throw ApiException(500, "Server error")
        identities.removeAll { it.id == identityId }
    }

    override suspend fun attachIdentity(agentId: String, identityId: String) {
        calls.add("attachIdentity:$agentId:$identityId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = identities.indexOfFirst { it.id == identityId }
        if (index < 0) return
        val current = identities[index]
        if (agentId !in current.agentIds) {
            identities[index] = current.copy(agentIds = current.agentIds + agentId)
        }
    }

    override suspend fun detachIdentity(agentId: String, identityId: String) {
        calls.add("detachIdentity:$agentId:$identityId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = identities.indexOfFirst { it.id == identityId }
        if (index < 0) return
        val current = identities[index]
        identities[index] = current.copy(agentIds = current.agentIds.filterNot { it == agentId })
    }
}
