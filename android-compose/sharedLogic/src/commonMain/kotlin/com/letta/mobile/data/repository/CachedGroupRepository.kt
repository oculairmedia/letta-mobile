package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupId
import com.letta.mobile.data.model.GroupIrohListParams
import com.letta.mobile.data.model.GroupListParams
import com.letta.mobile.data.model.GroupMessagesListParams
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.repository.api.GroupIrohSource
import com.letta.mobile.data.repository.api.GroupRemoteSource
import com.letta.mobile.data.repository.api.IGroupRepository
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * Phase 5i: platform-neutral cached group repository.
 */
open class CachedGroupRepository(
    private val remote: GroupRemoteSource,
    private val irohGroupSource: GroupIrohSource? = null,
) : IGroupRepository {
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    override val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    override suspend fun refreshGroups(managerType: String?, projectId: ProjectId?, showHiddenGroups: Boolean?) {
        val irohSource = irohGroupSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _groups.value = irohSource.listGroups(
                GroupIrohListParams(
                    managerType = managerType,
                    projectId = projectId?.value,
                    showHiddenGroups = showHiddenGroups,
                ),
            )
            return
        }
        _groups.value = exhaustCursorPages(
            pageSize = PaginationConstants.DEFAULT_PAGE_SIZE,
            maxPages = PaginationConstants.DEFAULT_MAX_PAGES,
            fetch = { limit, after ->
                remote.listGroups(
                    GroupListParams(
                        managerType = managerType,
                        after = after,
                        limit = limit,
                        projectId = projectId?.value,
                        showHiddenGroups = showHiddenGroups,
                    ),
                )
            },
            extractCursor = { group -> group.id.value },
            dedupKey = { group -> group.id.value },
        )
    }

    override suspend fun countGroups(): Int = remote.countGroups()

    override suspend fun getGroup(groupId: GroupId): Group = remote.retrieveGroup(groupId.value)

    override suspend fun createGroup(params: GroupCreateParams): Group {
        val group = remote.createGroup(params)
        upsertGroup(group)
        return group
    }

    override suspend fun updateGroup(groupId: GroupId, params: GroupUpdateParams): Group {
        val group = remote.updateGroup(groupId.value, params)
        upsertGroup(group)
        return group
    }

    override suspend fun deleteGroup(groupId: GroupId) {
        remote.deleteGroup(groupId.value)
        _groups.update { current -> current.filterNot { it.id == groupId } }
    }

    override suspend fun sendGroupMessage(groupId: GroupId, request: MessageCreateRequest): LettaResponse =
        remote.sendGroupMessage(groupId.value, request)

    override suspend fun sendGroupMessageStream(groupId: GroupId, request: MessageCreateRequest): ByteReadChannel =
        remote.sendGroupMessageStream(groupId.value, request)

    override suspend fun updateGroupMessage(groupId: GroupId, messageId: String, request: JsonElement): LettaMessage =
        remote.updateGroupMessage(groupId.value, messageId, request)

    override suspend fun listGroupMessages(groupId: GroupId): List<LettaMessage> {
        return exhaustCursorPages(
            pageSize = PaginationConstants.DEFAULT_PAGE_SIZE,
            maxPages = PaginationConstants.BOUNDED_MAX_PAGES,
            fetch = { limit, after ->
                remote.listGroupMessages(
                    GroupMessagesListParams(
                        groupId = groupId.value,
                        limit = limit,
                        after = after,
                    ),
                )
            },
            extractCursor = { message -> message.id },
            dedupKey = { message -> message.id },
        )
    }

    override suspend fun resetGroupMessages(groupId: GroupId) {
        remote.resetGroupMessages(groupId.value)
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
