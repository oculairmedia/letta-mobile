package com.letta.mobile.data.repository

import com.letta.mobile.data.api.GroupApi
import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.repository.api.IGroupRepository
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val groupApi: GroupApi,
) : IGroupRepository {
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    override val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    override suspend fun refreshGroups(managerType: String?, projectId: String?, showHiddenGroups: Boolean?) {
        _groups.value = groupApi.listGroups(limit = 1000, managerType = managerType, projectId = projectId, showHiddenGroups = showHiddenGroups)
    }

    override suspend fun countGroups(): Int = groupApi.countGroups()

    override suspend fun getGroup(groupId: String): Group {
        return groupApi.retrieveGroup(groupId)
    }

    override suspend fun createGroup(params: GroupCreateParams): Group {
        val group = groupApi.createGroup(params)
        upsertGroup(group)
        return group
    }

    override suspend fun updateGroup(groupId: String, params: GroupUpdateParams): Group {
        val group = groupApi.updateGroup(groupId, params)
        upsertGroup(group)
        return group
    }

    override suspend fun deleteGroup(groupId: String) {
        groupApi.deleteGroup(groupId)
        _groups.update { current -> current.filterNot { it.id == groupId } }
    }

    override suspend fun sendGroupMessage(groupId: String, request: MessageCreateRequest): LettaResponse {
        return groupApi.sendGroupMessage(groupId, request)
    }

    override suspend fun sendGroupMessageStream(groupId: String, request: MessageCreateRequest): ByteReadChannel {
        return groupApi.sendGroupMessageStream(groupId, request)
    }

    override suspend fun updateGroupMessage(groupId: String, messageId: String, request: JsonElement): LettaMessage {
        return groupApi.updateGroupMessage(groupId, messageId, request)
    }

    override suspend fun listGroupMessages(groupId: String): List<LettaMessage> {
        return groupApi.listGroupMessages(groupId = groupId, limit = 1000)
    }

    override suspend fun resetGroupMessages(groupId: String) {
        groupApi.resetGroupMessages(groupId)
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
