package com.letta.mobile.data.repository

import com.letta.mobile.data.api.GroupApi
import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupId
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.repository.api.IGroupRepository
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

class GroupRepository(
    private val groupApi: GroupApi,
) : IGroupRepository {
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    override val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    override suspend fun refreshGroups(managerType: String?, projectId: ProjectId?, showHiddenGroups: Boolean?) {
        _groups.value = groupApi.listGroups(limit = 1000, managerType = managerType, projectId = projectId?.value, showHiddenGroups = showHiddenGroups)
    }

    override suspend fun countGroups(): Int = groupApi.countGroups()

    override suspend fun getGroup(groupId: GroupId): Group {
        return groupApi.retrieveGroup(groupId.value)
    }

    override suspend fun createGroup(params: GroupCreateParams): Group {
        val group = groupApi.createGroup(params)
        upsertGroup(group)
        return group
    }

    override suspend fun updateGroup(groupId: GroupId, params: GroupUpdateParams): Group {
        val group = groupApi.updateGroup(groupId.value, params)
        upsertGroup(group)
        return group
    }

    override suspend fun deleteGroup(groupId: GroupId) {
        groupApi.deleteGroup(groupId.value)
        _groups.update { current -> current.filterNot { it.id == groupId } }
    }

    override suspend fun sendGroupMessage(groupId: GroupId, request: MessageCreateRequest): LettaResponse {
        return groupApi.sendGroupMessage(groupId.value, request)
    }

    override suspend fun sendGroupMessageStream(groupId: GroupId, request: MessageCreateRequest): ByteReadChannel {
        return groupApi.sendGroupMessageStream(groupId.value, request)
    }

    override suspend fun updateGroupMessage(groupId: GroupId, messageId: String, request: JsonElement): LettaMessage {
        return groupApi.updateGroupMessage(groupId.value, messageId, request)
    }

    override suspend fun listGroupMessages(groupId: GroupId): List<LettaMessage> {
        return groupApi.listGroupMessages(groupId = groupId.value, limit = 1000)
    }

    override suspend fun resetGroupMessages(groupId: GroupId) {
        groupApi.resetGroupMessages(groupId.value)
    }

    private fun upsertGroup(group: Group) {
        _groups.update { current ->
            val index = current.indexOfFirst { it.id == group.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = group }
            } else {
                current + group
            }
        }
    }
}
