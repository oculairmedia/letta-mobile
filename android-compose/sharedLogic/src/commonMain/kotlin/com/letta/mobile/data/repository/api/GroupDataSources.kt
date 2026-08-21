package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupIrohListParams
import com.letta.mobile.data.model.GroupListParams
import com.letta.mobile.data.model.GroupMessagesListParams
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
    suspend fun listGroups(params: GroupListParams = GroupListParams()): List<Group>
    suspend fun countGroups(): Int
    suspend fun retrieveGroup(groupId: String): Group
    suspend fun createGroup(params: GroupCreateParams): Group
    suspend fun updateGroup(groupId: String, params: GroupUpdateParams): Group
    suspend fun deleteGroup(groupId: String)
    suspend fun sendGroupMessage(groupId: String, request: MessageCreateRequest): LettaResponse
    suspend fun sendGroupMessageStream(groupId: String, request: MessageCreateRequest): ByteReadChannel
    suspend fun updateGroupMessage(groupId: String, messageId: String, request: JsonElement): LettaMessage
    suspend fun listGroupMessages(params: GroupMessagesListParams): List<LettaMessage>
    suspend fun resetGroupMessages(groupId: String)
}

/**
 * Iroh admin_rpc group list surface.
 */
interface GroupIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listGroups(params: GroupIrohListParams = GroupIrohListParams()): List<Group>
}
