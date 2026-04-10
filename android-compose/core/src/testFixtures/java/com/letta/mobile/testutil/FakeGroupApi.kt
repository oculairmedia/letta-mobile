package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.GroupApi
import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.StopReason
import com.letta.mobile.data.model.UsageStatistics
import io.ktor.utils.io.ByteReadChannel
import io.mockk.mockk
import kotlinx.serialization.json.JsonElement

class FakeGroupApi : GroupApi(mockk(relaxed = true)) {
    var groups = mutableListOf<Group>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listGroups(managerType: String?, before: String?, after: String?, limit: Int?, order: String?, projectId: String?, showHiddenGroups: Boolean?): List<Group> {
        calls.add("listGroups")
        if (shouldFail) throw ApiException(500, "Server error")
        return groups.filter { managerType == null || it.managerType == managerType }
    }

    override suspend fun countGroups(): Int {
        calls.add("countGroups")
        if (shouldFail) throw ApiException(500, "Server error")
        return groups.size
    }

    override suspend fun retrieveGroup(groupId: String): Group {
        calls.add("retrieveGroup:$groupId")
        if (shouldFail) throw ApiException(500, "Server error")
        return groups.firstOrNull { it.id == groupId } ?: throw ApiException(404, "Not found")
    }

    override suspend fun createGroup(params: GroupCreateParams): Group {
        calls.add("createGroup:${params.description}")
        if (shouldFail) throw ApiException(500, "Server error")
        val group = Group(
            id = "group-${groups.size + 1}",
            managerType = "round_robin",
            agentIds = params.agentIds,
            description = params.description,
            projectId = params.projectId,
            sharedBlockIds = params.sharedBlockIds ?: emptyList(),
            hidden = params.hidden,
        )
        groups.add(group)
        return group
    }

    override suspend fun updateGroup(groupId: String, params: GroupUpdateParams): Group {
        calls.add("updateGroup:$groupId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = groups.indexOfFirst { it.id == groupId }
        if (index < 0) throw ApiException(404, "Not found")
        val updated = groups[index].copy(
            description = params.description ?: groups[index].description,
            agentIds = params.agentIds ?: groups[index].agentIds,
            projectId = params.projectId ?: groups[index].projectId,
            sharedBlockIds = params.sharedBlockIds ?: groups[index].sharedBlockIds,
            hidden = params.hidden ?: groups[index].hidden,
        )
        groups[index] = updated
        return updated
    }

    override suspend fun deleteGroup(groupId: String) {
        calls.add("deleteGroup:$groupId")
        if (shouldFail) throw ApiException(500, "Server error")
        groups.removeAll { it.id == groupId }
    }

    override suspend fun sendGroupMessage(groupId: String, request: MessageCreateRequest): LettaResponse {
        calls.add("sendGroupMessage:$groupId")
        if (shouldFail) throw ApiException(500, "Server error")
        return LettaResponse(messages = emptyList(), stopReason = StopReason(reason = "completed"), usage = UsageStatistics())
    }

    override suspend fun sendGroupMessageStream(groupId: String, request: MessageCreateRequest): ByteReadChannel {
        calls.add("sendGroupMessageStream:$groupId")
        if (shouldFail) throw ApiException(500, "Server error")
        return ByteReadChannel.Empty
    }

    override suspend fun updateGroupMessage(groupId: String, messageId: String, request: JsonElement): LettaMessage {
        calls.add("updateGroupMessage:$groupId:$messageId")
        if (shouldFail) throw ApiException(500, "Server error")
        return TestMessageFactory.userMessage(id = messageId, content = "updated")
    }

    override suspend fun listGroupMessages(groupId: String, limit: Int?, before: String?, after: String?, order: String?): List<LettaMessage> {
        calls.add("listGroupMessages:$groupId")
        if (shouldFail) throw ApiException(500, "Server error")
        return listOf(TestMessageFactory.userMessage(id = "message-1", content = "hello"))
    }

    override suspend fun resetGroupMessages(groupId: String) {
        calls.add("resetGroupMessages:$groupId")
        if (shouldFail) throw ApiException(500, "Server error")
    }
}
