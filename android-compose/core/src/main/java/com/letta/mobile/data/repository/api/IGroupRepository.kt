package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

interface IGroupRepository {
    val groups: StateFlow<List<Group>>
    suspend fun refreshGroups(managerType: String? = null, projectId: String? = null, showHiddenGroups: Boolean? = null)
    suspend fun countGroups(): Int
    suspend fun getGroup(groupId: String): Group
    suspend fun createGroup(params: GroupCreateParams): Group
    suspend fun updateGroup(groupId: String, params: GroupUpdateParams): Group
    suspend fun deleteGroup(groupId: String)
    suspend fun sendGroupMessage(groupId: String, request: MessageCreateRequest): LettaResponse
    suspend fun sendGroupMessageStream(groupId: String, request: MessageCreateRequest): ByteReadChannel
    suspend fun updateGroupMessage(groupId: String, messageId: String, request: JsonElement): LettaMessage
    suspend fun listGroupMessages(groupId: String): List<LettaMessage>
    suspend fun resetGroupMessages(groupId: String)
}
