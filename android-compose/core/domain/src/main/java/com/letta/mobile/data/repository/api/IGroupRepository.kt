package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupId
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ProjectId
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

interface IGroupRepository {
    val groups: StateFlow<List<Group>>
    suspend fun refreshGroups(managerType: String? = null, projectId: ProjectId? = null, showHiddenGroups: Boolean? = null)
    suspend fun countGroups(): Int
    suspend fun getGroup(groupId: GroupId): Group
    suspend fun createGroup(params: GroupCreateParams): Group
    suspend fun updateGroup(groupId: GroupId, params: GroupUpdateParams): Group
    suspend fun deleteGroup(groupId: GroupId)
    suspend fun sendGroupMessage(groupId: GroupId, request: MessageCreateRequest): LettaResponse
    suspend fun sendGroupMessageStream(groupId: GroupId, request: MessageCreateRequest): ByteReadChannel
    suspend fun updateGroupMessage(groupId: GroupId, messageId: String, request: JsonElement): LettaMessage
    suspend fun listGroupMessages(groupId: GroupId): List<LettaMessage>
    suspend fun resetGroupMessages(groupId: GroupId)
}
