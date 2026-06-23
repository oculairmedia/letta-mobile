package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ScheduleApi
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleListResponse
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.schedules.CronTask
import io.mockk.mockk

class FakeScheduleApi : ScheduleApi(mockk(relaxed = true)) {
    var schedules = mutableMapOf<String, MutableList<ScheduledMessage>>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    /**
     * Crons served by the `/v1/crons` fallback. When [cronRouteAvailable]
     * is false (the default), [listCrons] throws — mirroring a backend
     * that serves neither the native schedule route nor the cron route, so
     * tests that expect the "admin unavailable" state keep passing.
     */
    var crons = mutableListOf<CronTask>()
    var cronRouteAvailable = false

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

    override suspend fun listCrons(agentId: String?): List<CronTask> {
        calls.add("listCrons:${agentId ?: "all"}")
        if (!cronRouteAvailable) throw ApiException(404, "Not found")
        return if (agentId == null) {
            crons
        } else {
            crons.filter { it.agentId == null || it.agentId == agentId }
        }
    }
}
