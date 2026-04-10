package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ScheduleApi
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleListResponse
import com.letta.mobile.data.model.ScheduledMessage
import io.mockk.mockk

class FakeScheduleApi : ScheduleApi(mockk(relaxed = true)) {
    var schedules = mutableMapOf<String, MutableList<ScheduledMessage>>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listSchedules(agentId: String, limit: Int?, after: String?): ScheduleListResponse {
        calls.add("listSchedules:$agentId")
        if (shouldFail) throw ApiException(500, "Server error")
        return ScheduleListResponse(
            hasNextPage = false,
            scheduledMessages = schedules[agentId].orEmpty(),
        )
    }

    override suspend fun retrieveSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage {
        calls.add("retrieveSchedule:$agentId:$scheduledMessageId")
        if (shouldFail) throw ApiException(500, "Server error")
        return schedules[agentId]?.firstOrNull { it.id == scheduledMessageId }
            ?: throw ApiException(404, "Not found")
    }

    override suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage {
        calls.add("createSchedule:$agentId")
        if (shouldFail) throw ApiException(500, "Server error")
        val scheduled = ScheduledMessage(
            id = "schedule-${schedules[agentId]?.size ?: 0}",
            agentId = agentId,
            message = com.letta.mobile.data.model.SchedulePayload(
                messages = params.messages,
                callbackUrl = params.callbackUrl,
                includeReturnMessageTypes = params.includeReturnMessageTypes ?: emptyList(),
                maxSteps = params.maxSteps,
            ),
            schedule = params.schedule,
        )
        schedules.getOrPut(agentId) { mutableListOf() }.add(scheduled)
        return scheduled
    }

    override suspend fun deleteSchedule(agentId: String, scheduledMessageId: String) {
        calls.add("deleteSchedule:$agentId:$scheduledMessageId")
        if (shouldFail) throw ApiException(500, "Server error")
        schedules[agentId]?.removeAll { it.id == scheduledMessageId }
    }
}
