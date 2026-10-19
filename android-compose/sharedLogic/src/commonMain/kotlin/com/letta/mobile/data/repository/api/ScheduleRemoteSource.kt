package com.letta.mobile.data.repository.api

import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleListResponse
import com.letta.mobile.data.model.ScheduledMessage

/**
 * Per-agent scheduled messages a non-App-Server backend would serve. this is
 * the seam for one; there is no production implementation since the Letta REST backend was
 * removed. Iroh hosts serve schedules through
 * [com.letta.mobile.data.repository.iroh.IrohScheduleRepository].
 * Every call fails unless overridden.
 */
interface ScheduleRemoteSource {
    suspend fun listSchedules(
        agentId: String,
        limit: Int? = null,
        after: String? = null,
    ): ScheduleListResponse = noRoute("listSchedules")

    suspend fun retrieveSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage = noRoute("retrieveSchedule")

    suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage = noRoute("createSchedule")

    suspend fun deleteSchedule(agentId: String, scheduledMessageId: String): Unit = noRoute("deleteSchedule")
}

private fun noRoute(operation: String): Nothing =
    throw AppServerRouteUnavailable(SessionRoute.SCHEDULES, "ScheduleRemoteSource.$operation")
