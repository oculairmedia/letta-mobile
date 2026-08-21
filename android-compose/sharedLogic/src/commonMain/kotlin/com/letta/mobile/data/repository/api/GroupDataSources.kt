package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.JsonElement

/**
 * Remote HTTP (or equivalent) group admin surface used by
 * [com.letta.mobile.data.repository.CachedGroupRepository].
 */
interface GroupRemoteSource {
    suspend fun listGroups(
        managerType: String? = null,
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
        projectId: String? = null,
        showHiddenGroups: Boolean? = null,
    ): List<Group>

    suspend fun countGroups(): Int
    suspend fun retrieveGroup(groupId: String): Group
    suspend fun createGroup(params: GroupCreateParams): Group
    suspend fun updateGroup(groupId: String, params: GroupUpdateParams): Group
    suspend fun deleteGroup(groupId: String)
    suspend fun sendGroupMessage(groupId: String, request: MessageCreateRequest): LettaResponse
    suspend fun sendGroupMessageStream(groupId: String, request: MessageCreateRequest): ByteReadChannel
    suspend fun updateGroupMessage(groupId: String, messageId: String, request: JsonElement): LettaMessage
    suspend fun listGroupMessages(
        groupId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
    ): List<LettaMessage>

    suspend fun resetGroupMessages(groupId: String)
}

/**
 * Iroh admin_rpc group list surface.
 */
interface GroupIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listGroups(
        managerType: String? = null,
        projectId: String? = null,
        showHiddenGroups: Boolean? = null,
    ): List<Group>
}
